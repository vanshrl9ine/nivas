package com.nivas.billing.domain;

/** Where a payment attempt got to. */
public enum OrderStatus {

    /** Created locally and at the gateway; the buyer has not paid yet. */
    CREATED,

    /** Money confirmed by a signed webhook — never by the client's callback. */
    PAID,

    FAILED,
    REFUNDED;

    public boolean isTerminal() {
        return this != CREATED;
    }

    /** Only a CREATED order may be fulfilled, which is what makes replay safe. */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target != CREATED;
            case PAID -> target == REFUNDED;
            case FAILED, REFUNDED -> false;
        };
    }
}
