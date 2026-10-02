package com.aashir.payment.provider;

import com.aashir.payment.entity.PaymentMethod;

import java.math.BigDecimal;

public interface PaymentProvider {

    PaymentResult processPayment(
            Long orderId,
            BigDecimal amount,
            PaymentMethod payment,
            String idempotencyKey
    );

}
