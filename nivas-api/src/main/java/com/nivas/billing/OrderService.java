package com.nivas.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.UserRepository;
import com.nivas.auth.domain.User;
import com.nivas.billing.domain.PackageType;
import com.nivas.billing.domain.PaymentEvent;
import com.nivas.billing.domain.PaymentOrder;
import com.nivas.billing.domain.SalesPackage;
import com.nivas.billing.dto.BillingDtos.CheckoutRequest;
import com.nivas.billing.dto.BillingDtos.CheckoutResponse;
import com.nivas.billing.dto.BillingDtos.OrderResponse;
import com.nivas.billing.gateway.PaymentGateway;
import com.nivas.catalog.PropertyRepository;
import com.nivas.common.PageResponse;
import com.nivas.common.exception.BadRequestException;
import com.nivas.common.exception.ConflictException;
import com.nivas.common.exception.ForbiddenException;
import com.nivas.common.exception.NotFoundException;
import com.nivas.placement.PlacementService;
import com.nivas.placement.domain.Surface;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Money changing hands.
 *
 * <p>Two rules shape everything here.
 *
 * <p>First, <b>fulfilment is driven by the webhook, never by the client</b>. The
 * browser's "payment succeeded" callback is a hint that the user should be shown
 * a success page; it is not evidence. Anyone can POST it. Only a signed webhook
 * from the provider grants anything.
 *
 * <p>Second, <b>webhook delivery is at-least-once</b>. Every path here has to be
 * safe to run twice, and the guarantee is a unique constraint rather than a
 * check — two concurrent redeliveries would both pass any check we wrote.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final PaymentOrderRepository orderRepository;
    private final PaymentEventRecorder eventRecorder;
    private final SalesPackageRepository packageRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final PaymentGateway gateway;
    private final BillingService billingService;
    private final PlacementService placementService;
    private final ObjectMapper objectMapper;

    private final Counter paidCounter;
    private final Counter replayedCounter;
    private final Counter rejectedCounter;

    public OrderService(PaymentOrderRepository orderRepository, PaymentEventRecorder eventRecorder,
            SalesPackageRepository packageRepository, PropertyRepository propertyRepository,
            UserRepository userRepository, PaymentGateway gateway, BillingService billingService,
            PlacementService placementService, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.eventRecorder = eventRecorder;
        this.packageRepository = packageRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.gateway = gateway;
        this.billingService = billingService;
        this.placementService = placementService;
        this.objectMapper = objectMapper;

        this.paidCounter = Counter.builder("nivas.payments.fulfilled")
                .description("Orders fulfilled from a verified webhook").register(meterRegistry);
        this.replayedCounter = Counter.builder("nivas.payments.replayed")
                .description("Webhooks that were already processed").register(meterRegistry);
        this.rejectedCounter = Counter.builder("nivas.payments.rejected_signature")
                .description("Webhooks rejected for a bad signature").register(meterRegistry);
    }

    // -- checkout ------------------------------------------------------------

    /**
     * Creates an order and registers it with the provider.
     *
     * <p>Availability is checked before taking payment. It is not a guarantee —
     * the slot can go while the buyer is on the checkout page — but charging
     * somebody for inventory that was visibly sold out is a refund and an
     * apology, so it is worth the query.
     */
    @Transactional
    public CheckoutResponse checkout(UUID userId, CheckoutRequest request) {
        SalesPackage salesPackage = billingService.requirePackage(request.packageCode());

        if (salesPackage.isFree()) {
            throw new BadRequestException(
                    "The free plan does not need paying for. It is granted automatically.");
        }

        UUID propertyId = null;
        String city = null;

        if (salesPackage.getPackageType() != PackageType.LISTING) {
            propertyId = requireOwnedProperty(request.propertyId(), userId);
            city = requireCityFor(salesPackage, request.city(), propertyId);
            assertInventoryAvailable(salesPackage, city);
        }

        User user = userRepository.getReferenceById(userId);
        PaymentOrder order = orderRepository.save(
                new PaymentOrder(user, salesPackage, propertyId, city, gateway.name()));

        PaymentGateway.GatewayOrder gatewayOrder = gateway.createOrder(
                order.getId().toString(),
                order.getAmountPaise(),
                order.getCurrency(),
                salesPackage.getName());

        order.attachGatewayOrder(gatewayOrder.gatewayOrderId());
        orderRepository.save(order);

        log.info("Checkout started: order {} for {} by user {}", order.getId(), salesPackage.getCode(), userId);

        return new CheckoutResponse(
                order.getId(),
                gatewayOrder.gatewayOrderId(),
                gatewayOrder.publicKey(),
                order.getAmountPaise(),
                order.getCurrency(),
                salesPackage.getCode(),
                salesPackage.getName());
    }

    private UUID requireOwnedProperty(UUID propertyId, UUID userId) {
        if (propertyId == null) {
            throw new BadRequestException("This package promotes a listing, so it needs a propertyId");
        }
        return propertyRepository.findByIdAndOwnerId(propertyId, userId)
                .orElseThrow(() -> new ForbiddenException("You can only promote your own listings"))
                .getId();
    }

    /** City-scoped surfaces need one; it defaults to the listing's own city. */
    private String requireCityFor(SalesPackage salesPackage, String requested, UUID propertyId) {
        boolean needsCity = salesPackage.getSlotsPerSurface().keySet().stream()
                .anyMatch(Surface::isCityScoped);
        if (!needsCity) {
            return null;
        }
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return propertyRepository.findById(propertyId)
                .map(property -> property.getCity())
                .orElseThrow(() -> new BadRequestException("This package needs a city"));
    }

    private void assertInventoryAvailable(SalesPackage salesPackage, String city) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(salesPackage.getDurationDays());

        salesPackage.getSlotsPerSurface().forEach((surface, wanted) -> {
            var availability = placementService.availability(surface, city, from, to);
            if (availability.freeCount() < wanted) {
                throw new ConflictException(
                        ("%s is not available for those dates — %d of %d slots free on %s. "
                                + "Try a later start date.")
                                .formatted(salesPackage.getName(), availability.freeCount(), wanted, surface));
            }
        });
    }

    // -- webhook -------------------------------------------------------------

    /**
     * Handles a provider webhook.
     *
     * <p>Order of operations is deliberate: verify, record, then act. The event
     * is written down even when its signature fails, because a forged or
     * misconfigured webhook is exactly the thing worth being able to inspect
     * afterwards.
     *
     * @return true when this call fulfilled something; false for a replay,
     *         an unrecognised event, or a rejected signature
     */
    @Transactional
    public boolean handleWebhook(String rawBody, String signatureHeader) {
        boolean signatureValid = gateway.verifyWebhookSignature(rawBody, signatureHeader);

        Optional<PaymentGateway.WebhookEvent> parsed = gateway.parseWebhook(rawBody);
        if (parsed.isEmpty()) {
            // Providers send far more event types than any application acts on.
            log.debug("Ignoring a webhook we do not act on");
            return false;
        }
        PaymentGateway.WebhookEvent event = parsed.get();

        // Recorded in its own transaction — see PaymentEventRecorder for why the
        // duplicate cannot be caught inline.
        Optional<PaymentEvent> recorded = eventRecorder.recordIfNew(
                gateway.name(), event.eventId(), event.eventType(), rawBody, signatureValid);

        if (recorded.isEmpty()) {
            replayedCounter.increment();
            log.info("Webhook {} already processed; ignoring the replay", event.eventId());
            return false;
        }
        PaymentEvent record = recorded.get();

        if (!signatureValid) {
            rejectedCounter.increment();
            record.markFailed("Signature verification failed");
            eventRecorder.save(record);
            log.warn("Rejected a webhook with an invalid signature (event {})", event.eventId());
            return false;
        }

        PaymentOrder order = orderRepository
                .findByGatewayAndGatewayOrderId(gateway.name(), event.gatewayOrderId())
                .orElse(null);

        if (order == null) {
            record.markFailed("No order for gateway order id " + event.gatewayOrderId());
            eventRecorder.save(record);
            log.warn("Webhook referenced unknown gateway order {}", event.gatewayOrderId());
            return false;
        }

        if (!event.paid()) {
            order.markFailed(event.failureReason());
            orderRepository.save(order);
            // Whatever it was holding goes back on sale.
            placementService.cancelForOrder(order.getId());
            record.markProcessed(order.getId());
            eventRecorder.save(record);
            return false;
        }

        // markPaid returns false when the order was already paid — a different
        // event id for the same payment, which the event-level check cannot see.
        if (!order.markPaid(event.gatewayPaymentId(), Instant.now())) {
            replayedCounter.increment();
            record.markProcessed(order.getId());
            eventRecorder.save(record);
            log.info("Order {} was already paid; not fulfilling twice", order.getId());
            return false;
        }

        orderRepository.save(order);
        billingService.fulfil(order);

        record.markProcessed(order.getId());
        eventRecorder.save(record);
        paidCounter.increment();

        log.info("Fulfilled order {} ({} paise) for user {}",
                order.getId(), order.getAmountPaise(), order.getUser().getId());
        return true;
    }

    // -- reads ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> history(UUID userId, Pageable pageable) {
        return PageResponse.from(
                orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable),
                OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId, UUID userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    /**
     * Releases slots held by orders that were never paid.
     *
     * <p>Without this an abandoned checkout keeps inventory off the market
     * indefinitely, which costs real revenue on a scarce surface.
     */
    @Transactional
    public int sweepAbandoned(java.time.Duration ttl) {
        var stale = orderRepository.findStale(Instant.now().minus(ttl));
        stale.forEach(order -> {
            order.markFailed("Abandoned at checkout");
            placementService.cancelForOrder(order.getId());
        });
        orderRepository.saveAll(stale);
        if (!stale.isEmpty()) {
            log.info("Swept {} abandoned order(s)", stale.size());
        }
        return stale.size();
    }
}
