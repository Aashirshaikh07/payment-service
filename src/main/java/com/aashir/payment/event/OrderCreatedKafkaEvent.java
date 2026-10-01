package com.aashir.payment.event;

import com.aashir.payment.entity.PaymentMethod;

import java.math.BigDecimal;

public record OrderCreatedKafkaEvent(
        Long orderId,
        String orderNumber,
        Long userId,
        BigDecimal totalAmount,
        PaymentMethod paymentMethod
) {
}
