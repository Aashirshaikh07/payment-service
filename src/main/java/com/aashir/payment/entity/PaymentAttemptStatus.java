package com.aashir.payment.entity;

public enum PaymentAttemptStatus {
    CREATED,
    CHECKOUT_CREATED,
    PENDING,
    SUCCESS,
    FAILED,
    ABANDONED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == ABANDONED;
    }
}
