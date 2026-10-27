package com.aashir.payment.dto;
import java.math.BigDecimal;

public record PaymentStatusResponse(
        Long paymentId,
        Long orderId,
        String status,
        BigDecimal amount,
        String currency
) {
}