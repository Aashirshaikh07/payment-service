package com.aashir.payment.service;

import java.math.BigDecimal;

public record CheckoutPreparation (
        boolean replay,
        Long paymentId,
        Long paymentAttemptId,
        Long checkoutRequestId,
        BigDecimal amount,
        String currency,
        Integer attemptNumber,
        String razorpayOrderId
) {
    public static CheckoutPreparation replay(Long paymentId, Long paymentAttemptId ,Long checkoutRequestId, String razorpayOrderId) {
        return new CheckoutPreparation(
                true,
                paymentId,
                paymentAttemptId,
                checkoutRequestId,
                null,
                null,
                null,
                razorpayOrderId
        );
    }

    public static CheckoutPreparation newCheckout(
            Long paymentId,
            Long paymentAttemptId,
            Long checkoutRequestId,
            BigDecimal amount,
            String currency,
            Integer attemptNumber
    ){
        return new CheckoutPreparation(
                false,
                paymentId,
                paymentAttemptId,
                checkoutRequestId,
                amount,
                currency,
                attemptNumber,
                null
        );
    }

}
