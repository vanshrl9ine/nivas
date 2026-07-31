package com.nivas.catalog;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.UserRepository;
import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;
import com.nivas.billing.BillingService;
import com.nivas.catalog.domain.Locality;
import com.nivas.catalog.domain.Property;
import com.nivas.catalog.domain.PropertyImage;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.catalog.dto.PropertyDtos.ImagePayload;
import com.nivas.catalog.dto.PropertyDtos.OwnerListingResponse;
import com.nivas.catalog.dto.PropertyDtos.PropertyDetailResponse;
import com.nivas.catalog.dto.PropertyDtos.UpsertPropertyRequest;
import com.nivas.common.PageResponse;
import com.nivas.common.exception.ForbiddenException;
import com.nivas.common.exception.NotFoundException;
import com.nivas.config.CacheNames;
import com.nivas.outbox.OutboxRecorder;
import com.nivas.outbox.PropertyChangedEvent;
import com.nivas.security.AppUserPrincipal;

import java.util.EnumSet;
import java.util.Set;

/**
 * The write side of the catalogue.
 *
 * <p>Every mutating method follows the same shape: load, apply through the
 * aggregate's own methods, then record an outbox event <em>inside the same
 * transaction</em>. That last part is the invariant the whole search pipeline
 * rests on — see {@link OutboxRecorder}.
 */
@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);

    private final PropertyRepository propertyRepository;
    private final LocalityRepository localityRepository;
    private final UserRepository userRepository;
    private final OutboxRecorder outboxRecorder;
    private final BillingService billingService;

    public PropertyService(PropertyRepository propertyRepository, LocalityRepository localityRepository,
            UserRepository userRepository, OutboxRecorder outboxRecorder, BillingService billingService) {
        this.propertyRepository = propertyRepository;
        this.localityRepository = localityRepository;
        this.userRepository = userRepository;
        this.outboxRecorder = outboxRecorder;
        this.billingService = billingService;
    }

    // -- reads ---------------------------------------------------------------

    /**
     * The public detail payload, cached.
     *
     * <p>This is by far the most-requested endpoint and its result changes only
     * when the owner edits the listing, which makes it close to the ideal
     * cache-aside candidate. Invalidation is explicit on every write path below
     * rather than left to the TTL.
     */
    @Cacheable(cacheNames = CacheNames.PROPERTY_DETAIL, key = "#propertyId")
    @Transactional(readOnly = true)
    public PropertyDetailResponse getPublicDetail(UUID propertyId) {
        Property property = propertyRepository.findByIdWithDetail(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));

        // A draft or archived listing must not be readable by its URL just
        // because someone guessed the id.
        if (!property.getStatus().isSearchable()) {
            throw NotFoundException.of("Listing", propertyId);
        }
        return PropertyDetailResponse.from(property);
    }

    /** The owner's own view, which unlike the public one includes drafts. */
    @Transactional(readOnly = true)
    public PropertyDetailResponse getForOwner(UUID propertyId, AppUserPrincipal principal) {
        Property property = propertyRepository.findByIdWithDetail(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));
        requireCanManage(property, principal);
        return PropertyDetailResponse.from(property);
    }

    @Transactional(readOnly = true)
    public PageResponse<OwnerListingResponse> listMine(UUID ownerId, PropertyStatus status, Pageable pageable) {
        Page<Property> page = status == null
                ? propertyRepository.findByOwnerId(ownerId, pageable)
                : propertyRepository.findByOwnerIdAndStatus(ownerId, status, pageable);
        return PageResponse.from(page, OwnerListingResponse::from);
    }

    // -- writes --------------------------------------------------------------

    @Transactional
    public PropertyDetailResponse create(UpsertPropertyRequest request, AppUserPrincipal principal) {
        User owner = userRepository.getReferenceById(principal.getId());

        Property property = new Property(owner, request.title().trim(), request.listingType(),
                request.propertyType(), request.price(), request.city().trim(), request.state().trim());

        apply(property, request);
        Property saved = propertyRepository.save(property);

        log.info("Created listing {} for owner {}", saved.getId(), principal.getId());
        // Created as a DRAFT, which is not searchable, so nothing is emitted yet.
        return PropertyDetailResponse.from(saved);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.PROPERTY_DETAIL, key = "#propertyId")
    public PropertyDetailResponse update(UUID propertyId, UpsertPropertyRequest request,
            AppUserPrincipal principal) {
        Property property = propertyRepository.findByIdWithDetail(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));
        requireCanManage(property, principal);

        apply(property, request);
        propertyRepository.save(property);

        // Only emit when something a searcher could see actually changed — the
        // aggregate tracks that itself so an edit to a private note is free.
        if (property.needsReindex() && property.getStatus().isSearchable()) {
            emitUpsert(property);
        }
        return PropertyDetailResponse.from(property);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.PROPERTY_DETAIL, key = "#propertyId")
    public PropertyDetailResponse changeStatus(UUID propertyId, PropertyStatus target,
            AppUserPrincipal principal) {
        Property property = propertyRepository.findByIdWithDetail(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));
        requireCanManage(property, principal);

        if (target == PropertyStatus.ACTIVE) {
            /*
             * The quota gate. Checked only when going live, and only when the
             * listing is not already live — re-publishing something that is
             * already ACTIVE must not be counted as a second listing.
             *
             * Placed before publish() so a seller over quota is told why rather
             * than being handed a completeness error about a listing that is
             * perfectly complete.
             */
            if (property.getStatus() != PropertyStatus.ACTIVE) {
                billingService.requireListingCapacity(property.getOwner().getId());
            }
            // publish() runs the completeness checks; a bare transition would not.
            property.publish();

            // Paid plans carry a verified badge; the free one does not.
            if (billingService.grantsAutoVerify(property.getOwner().getId()) && !property.isVerified()) {
                property.verify();
            }
        } else {
            property.transitionTo(target);
        }
        propertyRepository.save(property);

        if (property.getStatus().isSearchable()) {
            emitUpsert(property);
        } else {
            emitRemove(property);
        }

        log.info("Listing {} moved to {}", propertyId, property.getStatus());
        return PropertyDetailResponse.from(property);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.PROPERTY_DETAIL, key = "#propertyId")
    public void archive(UUID propertyId, AppUserPrincipal principal) {
        Property property = propertyRepository.findByIdWithDetail(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));
        requireCanManage(property, principal);

        property.archive();
        propertyRepository.save(property);
        emitRemove(property);
    }

    /** Admin-only: marks a listing as checked by a human. */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.PROPERTY_DETAIL, key = "#propertyId")
    public PropertyDetailResponse verify(UUID propertyId) {
        Property property = propertyRepository.findByIdWithDetail(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));

        property.verify();
        propertyRepository.save(property);

        if (property.getStatus().isSearchable()) {
            emitUpsert(property);
        }
        return PropertyDetailResponse.from(property);
    }

    // -- internals -----------------------------------------------------------

    private void apply(Property property, UpsertPropertyRequest request) {
        property.updateBasics(request.title().trim(), request.description(),
                request.propertyType(), request.listingType());

        property.updatePricing(request.price(), request.maintenanceMonthly(),
                request.securityDeposit(), request.priceNegotiable());

        property.updateLayout(request.bedrooms(), request.bathrooms(), request.balconies(),
                request.furnishing(), request.carpetAreaSqft(), request.builtUpAreaSqft(),
                request.floorNumber(), request.totalFloors(), request.ageYears());

        Locality locality = request.localityId() == null ? null
                : localityRepository.findById(request.localityId())
                        .orElseThrow(() -> NotFoundException.of("Locality", request.localityId()));

        property.updateLocation(locality, request.addressLine(), request.city().trim(),
                request.state().trim(), request.pincode(), request.latitude(), request.longitude());

        property.setAvailableFrom(request.availableFrom());
        property.setReraId(request.reraId());

        Set<com.nivas.catalog.domain.Amenity> amenities = request.amenities() == null
                ? EnumSet.noneOf(com.nivas.catalog.domain.Amenity.class)
                : request.amenities();
        property.replaceAmenities(amenities);

        if (request.images() != null) {
            List<PropertyImage> images = request.images().stream()
                    .map(PropertyService::toImage)
                    .toList();
            property.replaceImages(images);
        }
    }

    private static PropertyImage toImage(ImagePayload payload) {
        // Position is assigned by the aggregate from list order, so it is not
        // taken from the request — a client cannot create a positional collision.
        return new PropertyImage(payload.url().trim(), payload.caption(), (short) 0,
                payload.width(), payload.height());
    }

    private void emitUpsert(Property property) {
        outboxRecorder.recordPropertyChange(PropertyChangedEvent.upsert(
                property.getId(), property.getVersion(), property.getStatus().name()));
    }

    private void emitRemove(Property property) {
        outboxRecorder.recordPropertyChange(PropertyChangedEvent.remove(
                property.getId(), property.getVersion(), property.getStatus().name()));
    }

    /** Owners manage their own listings; admins manage anyone's. */
    private static void requireCanManage(Property property, AppUserPrincipal principal) {
        if (principal.getRole() == Role.ADMIN || property.isOwnedBy(principal.getId())) {
            return;
        }
        throw new ForbiddenException("You can only manage your own listings");
    }
}
