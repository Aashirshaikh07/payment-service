package com.aashir.payment.event;

import java.math.BigDecimal;

public record PaymentFailedEvent(
        Long paymentId,
        Long orderId,
        BigDecimal amount,
        String reason
) {
}
