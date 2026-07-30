package com.nivas.lead;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.UserRepository;
import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;
import com.nivas.catalog.PropertyRepository;
import com.nivas.catalog.domain.Property;
import com.nivas.common.PageResponse;
import com.nivas.common.exception.ForbiddenException;
import com.nivas.common.exception.NotFoundException;
import com.nivas.lead.domain.Lead;
import com.nivas.lead.domain.LeadStatus;
import com.nivas.lead.dto.LeadDtos.CreateLeadRequest;
import com.nivas.lead.dto.LeadDtos.LeadResponse;
import com.nivas.security.AppUserPrincipal;

@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    private final LeadRepository leadRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;

    public LeadService(LeadRepository leadRepository, PropertyRepository propertyRepository,
            UserRepository userRepository) {
        this.leadRepository = leadRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
    }

    /**
     * Records an enquiry.
     *
     * <p>Idempotency is enforced by a partial unique index, not by a
     * check-then-insert: two concurrent retries would both pass a pre-check and
     * both insert. Letting the database reject the second and treating that
     * rejection as success is the only version that is actually safe.
     */
    @Transactional
    public Lead create(UUID propertyId, CreateLeadRequest request, AppUserPrincipal principal,
            String idempotencyKey) {

        if (idempotencyKey != null) {
            var existing = leadRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));

        // Enquiring about a draft or an archived listing makes no sense and is a
        // common shape for scraping probes.
        if (!property.getStatus().isSearchable()) {
            throw NotFoundException.of("Listing", propertyId);
        }

        User seeker = principal == null ? null : userRepository.getReferenceById(principal.getId());

        Lead lead = new Lead(property, seeker, request.name().trim(), request.email().trim(),
                request.phone().trim(), request.message(), request.source(), idempotencyKey);

        try {
            Lead saved = leadRepository.saveAndFlush(lead);
            log.info("Lead {} captured for listing {}", saved.getId(), propertyId);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent retry with the same key. The
            // other request already created it, so return that one.
            if (idempotencyKey != null) {
                return leadRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> ex);
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> listForProperty(UUID propertyId, AppUserPrincipal principal,
            Pageable pageable) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> NotFoundException.of("Listing", propertyId));
        requireCanSeeLeads(property, principal);

        Page<Lead> page = leadRepository.findByPropertyIdOrderByCreatedAtDesc(propertyId, pageable);
        return PageResponse.from(page, LeadResponse::from);
    }

    /** The owner's whole inbox, across every listing they own. */
    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> listForOwner(UUID ownerId, Pageable pageable) {
        return PageResponse.from(leadRepository.findByOwnerId(ownerId, pageable), LeadResponse::from);
    }

    @Transactional
    public LeadResponse updateStatus(UUID leadId, LeadStatus status, AppUserPrincipal principal) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> NotFoundException.of("Lead", leadId));
        requireCanSeeLeads(lead.getProperty(), principal);

        lead.moveTo(status);
        return LeadResponse.from(leadRepository.save(lead));
    }

    private static void requireCanSeeLeads(Property property, AppUserPrincipal principal) {
        if (principal != null
                && (principal.getRole() == Role.ADMIN || property.isOwnedBy(principal.getId()))) {
            return;
        }
        // Leads carry the enquirer's phone and email. Anyone but the listing's
        // owner reading them would be a straightforward data leak.
        throw new ForbiddenException("Only the listing owner can see its enquiries");
    }
}
