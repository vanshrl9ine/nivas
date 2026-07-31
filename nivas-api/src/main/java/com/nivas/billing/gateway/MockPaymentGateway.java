package com.nivas.billing.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.nivas.config.NivasProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A payment provider that exists only in this process.
 *
 * <p>The point is that the entire purchase flow — order creation, signature
 * verification, webhook fulfilment, idempotent replay — runs end to end with no
 * provider account, no API keys, and no money. Tests exercise the real code
 * paths rather than a stub, and a reviewer can clone the repo and buy something.
 *
 * <p>It is genuinely a mock, not a bypass: signatures are real HMAC-SHA256 over
 * the raw body, so a webhook with a wrong signature is rejected here exactly as
 * it would be in production. Only the money is imaginary.
 */
@Component
@ConditionalOnProperty(name = "nivas.payments.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    public static final String NAME = "mock";

    private final NivasProperties properties;
    private final ObjectMapper objectMapper;

    public MockPaymentGateway(NivasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        log.warn("Using the MOCK payment gateway. No real money moves. "
                + "Set nivas.payments.gateway=razorpay with real keys for anything else.");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayOrder createOrder(String reference, long amountPaise, String currency, String description) {
        // Shaped like a provider id so nothing downstream is accidentally
        // written against the mock's format.
        String gatewayOrderId = "order_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        log.info("Mock order {} for reference {} ({} {} paise)", gatewayOrderId, reference, currency, amountPaise);
        return new GatewayOrder(gatewayOrderId, "mock_key_local", amountPaise, currency);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (signatureHeader == null || rawBody == null) {
            return false;
        }
        String expected = sign(rawBody, properties.payments().webhookSecret());
        // Constant-time: a byte-by-byte comparison leaks the correct signature
        // to anyone willing to time enough requests.
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<WebhookEvent> parseWebhook(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventType = root.path("event").asText("");

            if (!eventType.equals("payment.captured") && !eventType.equals("payment.failed")) {
                return Optional.empty();
            }

            JsonNode payment = root.path("payload").path("payment").path("entity");
            return Optional.of(new WebhookEvent(
                    root.path("id").asText(UUID.randomUUID().toString()),
                    eventType,
                    nullable(payment, "order_id"),
                    nullable(payment, "id"),
                    eventType.equals("payment.captured"),
                    nullable(payment, "error_description")));

        } catch (Exception ex) {
            log.warn("Could not parse a mock webhook body", ex);
            return Optional.empty();
        }
    }

    /** Jackson 3 returns "null" rather than null for a missing text node. */
    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    /**
     * Exposed so the demo seeder and tests can produce a correctly signed
     * webhook. Deliberately the same code path the verifier uses — a helper that
     * signed differently would make the tests prove nothing.
     */
    public String signForTesting(String rawBody) {
        return sign(rawBody, properties.payments().webhookSecret());
    }

    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not compute a webhook signature", ex);
        }
    }
}
