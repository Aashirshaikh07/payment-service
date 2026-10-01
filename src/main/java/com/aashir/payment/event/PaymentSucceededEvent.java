package com.aashir.payment.event;

import java.math.BigDecimal;

public record PaymentSucceededEvent(
        Long paymentId,
        Long orderId,
        BigDecimal amount
) {
}
