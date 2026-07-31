package com.nivas.billing;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.billing.domain.SalesPackage;
import com.nivas.billing.domain.Subscription;
import com.nivas.billing.dto.BillingDtos.CheckoutRequest;
import com.nivas.billing.dto.BillingDtos.CheckoutResponse;
import com.nivas.billing.dto.BillingDtos.EntitlementsResponse;
import com.nivas.billing.dto.BillingDtos.OrderResponse;
import com.nivas.billing.dto.BillingDtos.PackageResponse;
import com.nivas.common.PageResponse;
import com.nivas.placement.PlacementService;
import com.nivas.security.AppUserPrincipal;
import com.nivas.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Billing", description = "Packages, checkout and entitlements")
public class BillingController {

    private final BillingService billingService;
    private final OrderService orderService;
    private final PlacementService placementService;

    public BillingController(BillingService billingService, OrderService orderService,
            PlacementService placementService) {
        this.billingService = billingService;
        this.orderService = orderService;
        this.placementService = placementService;
    }

    /**
     * The pricing page.
     *
     * <p>Passing a city turns this into a live inventory view: placement
     * packages report how many slots are actually free, so a sold-out audience
     * maximizer can be shown as sold out rather than discovered at checkout.
     */
    @GetMapping("/packages")
    @Operation(summary = "Everything available to buy, with live slot availability")
    public List<PackageResponse> packages(@RequestParam(required = false) String city) {
        LocalDate from = LocalDate.now();

        return billingService.catalogue().stream()
                .map(salesPackage -> PackageResponse.from(salesPackage, freeSlots(salesPackage, city, from)))
                .toList();
    }

    private Map<String, Integer> freeSlots(SalesPackage salesPackage, String city, LocalDate from) {
        if (salesPackage.getSlotsPerSurface().isEmpty()) {
            return null;
        }
        LocalDate to = from.plusDays(salesPackage.getDurationDays());

        Map<String, Integer> free = new LinkedHashMap<>();
        salesPackage.getSlotsPerSurface().keySet().forEach(surface -> {
            // A city-scoped surface with no city given has no meaningful answer.
            if (surface.isCityScoped() && (city == null || city.isBlank())) {
                return;
            }
            free.put(surface.name(), placementService.availability(surface, city, from, to).freeCount());
        });
        return free;
    }

    @GetMapping("/me/entitlements")
    @Operation(summary = "The caller's plan and how much of its quota is used")
    public EntitlementsResponse entitlements(@CurrentUser AppUserPrincipal principal) {
        Subscription subscription = billingService.currentSubscription(principal.getId());
        BillingService.Quota quota = billingService.listingQuota(principal.getId());
        return EntitlementsResponse.from(subscription, quota.used(), quota.remaining());
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT', 'ADMIN')")
    @Operation(summary = "Create an order and get what the payment widget needs")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request,
            @CurrentUser AppUserPrincipal principal) {
        return orderService.checkout(principal.getId(), request);
    }

    @GetMapping("/orders")
    @Operation(summary = "The caller's order history")
    public PageResponse<OrderResponse> orders(@CurrentUser AppUserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return orderService.history(principal.getId(), pageable);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "One order, for polling after checkout")
    public OrderResponse order(@PathVariable UUID orderId, @CurrentUser AppUserPrincipal principal) {
        return orderService.get(orderId, principal.getId());
    }

    /**
     * The provider's webhook.
     *
     * <p>Unauthenticated by necessity — the gateway has no bearer token — and
     * therefore authenticated by signature instead. The raw body is taken as a
     * String rather than a parsed object because the signature is computed over
     * the exact bytes sent; re-serialising a parsed object would change
     * whitespace and key order and break verification.
     *
     * <p>Always answers 200. A provider that receives an error will retry, and
     * for a body we have already processed or deliberately ignore, retrying
     * forever helps nobody. What happened is recorded in {@code payment_events}.
     */
    @PostMapping(value = "/payments/webhook", consumes = "application/json")
    @Operation(summary = "Payment provider webhook (signature-verified, idempotent)")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String razorpaySignature,
            @RequestHeader(value = "X-Nivas-Signature", required = false) String mockSignature) {

        String signature = razorpaySignature != null ? razorpaySignature : mockSignature;
        boolean fulfilled = orderService.handleWebhook(rawBody, signature);

        return ResponseEntity.ok(Map.of("received", true, "fulfilled", fulfilled));
    }
}
