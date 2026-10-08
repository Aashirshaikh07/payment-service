package com.aashir.payment.event;

import com.aashir.payment.entity.PaymentStatus;

import java.math.BigDecimal;

public record PaymentSuccessResponse(
        Long paymentId,
        BigDecimal totalAmount,
        PaymentStatus status
) {
}
