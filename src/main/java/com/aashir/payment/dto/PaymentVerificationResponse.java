package com.aashir.payment.dto;

public record PaymentVerificationResponse(
        Long paymentId,
        Long orderId,
        String status,
        String message
) {
}
