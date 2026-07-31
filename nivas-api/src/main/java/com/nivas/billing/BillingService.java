package com.nivas.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.UserRepository;
import com.nivas.auth.domain.User;
import com.nivas.billing.domain.PackageType;
import com.nivas.billing.domain.PaymentOrder;
import com.nivas.billing.domain.SalesPackage;
import com.nivas.billing.domain.Subscription;
import com.nivas.billing.domain.SubscriptionStatus;
import com.nivas.billing.gateway.PaymentGateway;
import com.nivas.catalog.PropertyRepository;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.common.exception.BadRequestException;
import com.nivas.common.exception.ConflictException;
import com.nivas.common.exception.NotFoundException;
import com.nivas.placement.PlacementService;

/**
 * Entitlements: what a user is allowed to do, and what they have bought.
 *
 * <p>Kept separate from {@link OrderService}, which deals with money changing
 * hands. This class only answers questions like "may this listing go live" —
 * which is the one the publish path cares about.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** Everyone gets this the first time they need a subscription. */
    public static final String FREE_PACKAGE_CODE = "FREE_LISTING";

    private final SalesPackageRepository packageRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final PlacementService placementService;

    public BillingService(SalesPackageRepository packageRepository,
            SubscriptionRepository subscriptionRepository,
            PropertyRepository propertyRepository,
            UserRepository userRepository,
            PlacementService placementService) {
        this.packageRepository = packageRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.placementService = placementService;
    }

    @Transactional(readOnly = true)
    public List<SalesPackage> catalogue() {
        return packageRepository.findByActiveTrueOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public SalesPackage requirePackage(String code) {
        return packageRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> NotFoundException.of("Package", code));
    }

    /**
     * The user's current plan, creating a free one if they have none.
     *
     * <p>Everyone is on a plan, always — that is what makes the quota check
     * unconditional rather than a special case for people who have never paid.
     */
    @Transactional
    public Subscription currentSubscription(UUID userId) {
        Optional<Subscription> active =
                subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);

        if (active.isPresent() && active.get().isActiveAt(Instant.now())) {
            return active.get();
        }

        // Lapsed. Retire it so the partial unique index lets a new one in.
        active.ifPresent(subscription -> {
            subscription.expire();
            subscriptionRepository.save(subscription);
            log.info("Subscription {} expired for user {}", subscription.getId(), userId);
        });

        User user = userRepository.getReferenceById(userId);
        SalesPackage free = requirePackage(FREE_PACKAGE_CODE);
        return subscriptionRepository.save(new Subscription(user, free, null));
    }

    /**
     * How many more listings this user may publish.
     *
     * <p>Counts what is live rather than what was ever created: archiving a
     * listing should genuinely free a slot, otherwise the free tier becomes
     * "one listing, ever" rather than "one at a time".
     */
    @Transactional
    public Quota listingQuota(UUID userId) {
        Subscription subscription = currentSubscription(userId);
        int allowed = subscription.listingQuota();
        long used = propertyRepository.countByOwnerIdAndStatus(userId, PropertyStatus.ACTIVE);

        return new Quota(subscription.getSalesPackage().getCode(), allowed, used,
                Math.max(0, allowed - used));
    }

    /**
     * Gate for the publish path.
     *
     * @throws ConflictException when the plan is full, with a message that says
     *                           what to do about it
     */
    @Transactional
    public void requireListingCapacity(UUID userId) {
        Quota quota = listingQuota(userId);
        if (quota.remaining() > 0) {
            return;
        }
        throw new ConflictException(
                ("Your %s plan allows %d live listing%s and you already have %d. "
                        + "Archive one, or upgrade to publish more.")
                        .formatted(quota.packageCode(), quota.allowed(),
                                quota.allowed() == 1 ? "" : "s", quota.used()));
    }

    /** Whether the plan grants a verified badge without manual review. */
    @Transactional
    public boolean grantsAutoVerify(UUID userId) {
        return currentSubscription(userId).getSalesPackage().isAutoVerify();
    }

    /**
     * Applies a paid order's entitlements.
     *
     * <p>Called only from the webhook path, only once per order — see
     * {@link OrderService}. Splitting by package type here keeps the payment
     * code free of any knowledge about listings or slots.
     */
    @Transactional
    public void fulfil(PaymentOrder order) {
        SalesPackage salesPackage = order.getSalesPackage();

        if (salesPackage.getPackageType() == PackageType.LISTING) {
            grantSubscription(order, salesPackage);
            return;
        }

        if (order.getPropertyId() == null) {
            throw new BadRequestException("A placement order must name the listing it promotes");
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(salesPackage.getDurationDays());
        placementService.book(salesPackage, order.getPropertyId(), order.getId(),
                order.getCity(), from, to);
    }

    private void grantSubscription(PaymentOrder order, SalesPackage salesPackage) {
        UUID userId = order.getUser().getId();

        /*
         * Retire whatever they were on, and flush before inserting.
         *
         * saveAndFlush, not save. Hibernate's action queue executes inserts
         * before updates within a flush, so a plain save() leaves the new
         * ACTIVE row being inserted while the old one is still ACTIVE — and
         * uq_subscriptions_one_active rejects it. The upgrade then fails with a
         * constraint violation on the very index that exists to protect this
         * invariant.
         *
         * Observed, not theoretical: buying a listing pack failed here every
         * time until the flush was added.
         */
        subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .ifPresent(existing -> {
                    existing.expire();
                    subscriptionRepository.saveAndFlush(existing);
                });

        Subscription granted = subscriptionRepository.save(
                new Subscription(order.getUser(), salesPackage, order.getId()));

        log.info("Granted {} to user {} until {}", salesPackage.getCode(), userId, granted.getExpiresAt());
    }

    /** Retires lapsed subscriptions and finished placements. */
    @Transactional
    public void expireLapsed() {
        int subscriptions = subscriptionRepository.expireLapsed(Instant.now());
        int placements = placementService.expireFinished();
        if (subscriptions > 0 || placements > 0) {
            log.info("Expired {} subscription(s) and {} placement(s)", subscriptions, placements);
        }
    }

    public record Quota(String packageCode, int allowed, long used, long remaining) {

        public boolean isFull() {
            return remaining <= 0;
        }
    }
}
