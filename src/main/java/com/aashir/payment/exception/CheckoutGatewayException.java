package com.aashir.payment.exception;

public class CheckoutGatewayException extends RuntimeException {
    public CheckoutGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
