package com.aashir.payment.provider;

public record PaymentResult(
        boolean successful,
        String transactionId
) {
}
