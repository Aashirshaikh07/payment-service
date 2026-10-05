package com.aashir.payment.event;

import com.aashir.payment.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderCreatedKafkaEvent(
        UUID eventId,
        Long orderId,
        String orderNumber,
        Long userId,
        BigDecimal totalAmount,
        PaymentMethod paymentMethod
) {
}
