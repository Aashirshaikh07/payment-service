package com.aashir.payment.dto;

public record PaymentReadinessResponse(
        Long orderId,
        String status,
        boolean checkoutReady,
        String message
) {
}
