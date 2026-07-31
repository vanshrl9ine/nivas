package com.nivas.billing.gateway;

import java.util.Optional;

/**
 * The payment provider, as the rest of the application sees it.
 *
 * <p>Deliberately narrow. Card details never reach this interface, let alone our
 * servers — the client talks to the provider's hosted checkout directly, and all
 * we ever handle is an order id, a signature, and a webhook. That keeps the
 * application out of PCI scope, which is a compliance decision as much as an
 * architectural one.
 *
 * <p>Two implementations ship: {@link MockPaymentGateway}, so the whole flow runs
 * locally with no account and no keys, and {@link RazorpayPaymentGateway} for
 * real use. Which one is active is a config switch.
 */
public interface PaymentGateway {

    /** Identifier stored on every order, so rows stay attributable after a provider switch. */
    String name();

    /**
     * Registers an order with the provider.
     *
     * @param reference our own order id, echoed back on the webhook
     * @param amountPaise minor units — never a rupee decimal
     */
    GatewayOrder createOrder(String reference, long amountPaise, String currency, String description);

    /**
     * Whether a webhook body genuinely came from the provider.
     *
     * <p>The single most important method here. Without it the webhook endpoint
     * is an unauthenticated "give me a package for free" API, since anyone can
     * POST a payment-succeeded body.
     */
    boolean verifyWebhookSignature(String rawBody, String signatureHeader);

    /**
     * Extracts what we need from a webhook body.
     *
     * @return empty when the event is one we do not act on, which most of them are
     */
    Optional<WebhookEvent> parseWebhook(String rawBody);

    /** What the client needs to open the provider's checkout. */
    record GatewayOrder(String gatewayOrderId, String publicKey, long amountPaise, String currency) {
    }

    /** The normalised subset of a provider event that fulfilment cares about. */
    record WebhookEvent(
            String eventId,
            String eventType,
            String gatewayOrderId,
            String gatewayPaymentId,
            boolean paid,
            String failureReason) {
    }
}
