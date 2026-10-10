package com.aashir.payment.event;

import java.math.BigDecimal;

public record CheckoutResponse(
        Long paymentId,
        Long paymentAttemptId,
        String razorpayOrderId,
        String razorpayKeyId,
        BigDecimal amount,
        String currency,
        String status
) {
}
