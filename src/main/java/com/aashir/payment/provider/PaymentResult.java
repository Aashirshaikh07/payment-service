package com.aashir.payment.provider;

public record PaymentResult(
        PaymentResultStatus status,
        String transactionId,
        String failureReason
) {
}
