package com.nivas.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.nivas.billing.domain.OrderStatus;
import com.nivas.billing.domain.PackageType;
import com.nivas.billing.domain.PaymentOrder;
import com.nivas.billing.domain.SalesPackage;
import com.nivas.billing.domain.Subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class BillingDtos {

    private BillingDtos() {
    }

    /**
     * A package as the pricing page shows it.
     *
     * <p>Carries both paise and rupees: the former is what any further API call
     * uses, the latter is for display. Formatting money in the client from a
     * minor-unit integer is a reliable source of off-by-100 bugs.
     */
    public record PackageResponse(
            UUID id,
            String code,
            String name,
            String description,
            PackageType packageType,
            long pricePaise,
            BigDecimal priceRupees,
            String currency,
            int durationDays,
            Integer listingQuota,
            boolean autoVerify,
            Map<String, Short> slotsPerSurface,
            /** Null unless the request named a city — inventory is per city. */
            Map<String, Integer> freeSlots) {

        public static PackageResponse from(SalesPackage salesPackage, Map<String, Integer> freeSlots) {
            return new PackageResponse(
                    salesPackage.getId(),
                    salesPackage.getCode(),
                    salesPackage.getName(),
                    salesPackage.getDescription(),
                    salesPackage.getPackageType(),
                    salesPackage.getPricePaise(),
                    salesPackage.priceInRupees(),
                    salesPackage.getCurrency(),
                    salesPackage.getDurationDays(),
                    salesPackage.getListingQuota(),
                    salesPackage.isAutoVerify(),
                    salesPackage.getSlotsPerSurface().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    entry -> entry.getKey().name(), Map.Entry::getValue)),
                    freeSlots);
        }
    }

    public record CheckoutRequest(
            @NotBlank @Size(max = 48) String packageCode,
            /** Required for BOOST and AUDIENCE_MAXIMIZER; ignored otherwise. */
            UUID propertyId,
            /** Defaults to the listing's own city when omitted. */
            @Size(max = 80) String city) {
    }

    /**
     * Everything the client needs to open the provider's checkout.
     *
     * <p>{@code publicKey} is the publishable key, safe to expose. The secret
     * never leaves the server, and no card detail ever reaches it.
     */
    public record CheckoutResponse(
            UUID orderId,
            String gatewayOrderId,
            String publicKey,
            long amountPaise,
            String currency,
            String packageCode,
            String packageName) {
    }

    public record OrderResponse(
            UUID id,
            String packageCode,
            String packageName,
            OrderStatus status,
            long amountPaise,
            BigDecimal amountRupees,
            String currency,
            UUID propertyId,
            String city,
            String failureReason,
            Instant paidAt,
            Instant createdAt) {

        public static OrderResponse from(PaymentOrder order) {
            return new OrderResponse(
                    order.getId(),
                    order.getSalesPackage().getCode(),
                    order.getSalesPackage().getName(),
                    order.getStatus(),
                    order.getAmountPaise(),
                    BigDecimal.valueOf(order.getAmountPaise()).movePointLeft(2),
                    order.getCurrency(),
                    order.getPropertyId(),
                    order.getCity(),
                    order.getFailureReason(),
                    order.getPaidAt(),
                    order.getCreatedAt());
        }
    }

    /** The "what am I on, and how much of it have I used" panel. */
    public record EntitlementsResponse(
            String packageCode,
            String packageName,
            Instant expiresAt,
            int listingsAllowed,
            long listingsUsed,
            long listingsRemaining,
            boolean autoVerify) {

        public static EntitlementsResponse from(Subscription subscription, long used, long remaining) {
            SalesPackage salesPackage = subscription.getSalesPackage();
            return new EntitlementsResponse(
                    salesPackage.getCode(),
                    salesPackage.getName(),
                    subscription.getExpiresAt(),
                    subscription.listingQuota(),
                    used,
                    remaining,
                    salesPackage.isAutoVerify());
        }
    }
}
