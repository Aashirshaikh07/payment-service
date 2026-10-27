package com.aashir.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerifyPaymentRequest (
        @NotNull(message = "Payment ID is required")
        Long paymentId,

        @NotBlank(message = "Razorpay order ID is required")
        String razorpayOrderId,

        @NotBlank(message = "Razorpay payment ID is required")
        String razorpayPaymentId,

        @NotBlank(message = "Razorpay signature is required")
        String razorpaySignature
){
}
