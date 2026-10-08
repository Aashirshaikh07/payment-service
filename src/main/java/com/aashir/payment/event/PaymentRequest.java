package com.aashir.payment.event;

import com.aashir.payment.entity.PaymentMethod;

public record PaymentRequest(
        PaymentMethod method,
        Long orderId
) {
}
