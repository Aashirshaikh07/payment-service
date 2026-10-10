package com.aashir.payment.dto;

public record VerifyPaymentRequest (
        Long paymentId,
        String razorpayOrderId,
        String razorpayPaymentId,
        String razorpaySignature
){
}
