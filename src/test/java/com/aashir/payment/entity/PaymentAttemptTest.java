
package com.aashir.payment.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PaymentAttemptTest {

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = Payment.create(
                101L,
                1L,
                new BigDecimal("2500.00"),
                "INR",
                "ORDER-101"
        );
    }

    @Test
    void shouldReconcileSuccessfulAttemptWhenAttemptIsCreated() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);

        attempt.reconcileSuccess("pay_123", PaymentMethod.UPI);

        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals("pay_123", attempt.getGatewayPaymentId());
        assertEquals(PaymentMethod.UPI, attempt.getPaymentMethod());
        assertTrue(attempt.isSuccessful());
    }

    @Test
    void shouldRecoverFailedAttemptWhenGatewayConfirmsCapture() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.CARD);
        attempt.markFailed("Payment timed out");

        attempt.reconcileSuccess("pay_123", PaymentMethod.CARD);

        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals("pay_123", attempt.getGatewayPaymentId());
        assertNull(attempt.getFailureReason());
    }

    @Test
    void shouldRecoverAbandonedAttemptWhenGatewayConfirmsCapture() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.abandon();

        attempt.reconcileSuccess("pay_123", PaymentMethod.UPI);

        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals("pay_123", attempt.getGatewayPaymentId());
    }

    @Test
    void shouldReconcileSuccessfulAttemptWhenCheckoutIsCreated() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.attachGatewayOrder("order_123");

        attempt.reconcileSuccess("pay_123", PaymentMethod.UPI);

        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals("pay_123", attempt.getGatewayPaymentId());
    }

    @Test
    void shouldReconcileSuccessfulPendingAttempt() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.attachGatewayOrder("order_123");
        attempt.markPending();

        attempt.reconcileSuccess("pay_123", PaymentMethod.UPI);

        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals("pay_123", attempt.getGatewayPaymentId());
    }

    @Test
    void shouldAllowDuplicateReconciliationWithSameGatewayPaymentId() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);

        attempt.reconcileSuccess("pay_123", PaymentMethod.UPI);

        assertDoesNotThrow(
                () -> attempt.reconcileSuccess("pay_123", PaymentMethod.UPI)
        );

        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals("pay_123", attempt.getGatewayPaymentId());
    }

    @Test
    void shouldRejectDifferentGatewayPaymentIdAfterSuccess() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.reconcileSuccess("pay_123", PaymentMethod.UPI);

        assertThrows(
                IllegalStateException.class,
                () -> attempt.reconcileSuccess("pay_456", PaymentMethod.UPI)
        );

        assertEquals("pay_123", attempt.getGatewayPaymentId());
    }

    @Test
    void shouldPreserveExistingPaymentMethodWhenReconciliationMethodIsNull() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.CARD);

        attempt.reconcileSuccess("pay_123", null);

        assertEquals(PaymentMethod.CARD, attempt.getPaymentMethod());
    }

    @Test
    void shouldRejectBlankGatewayPaymentId() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);

        assertThrows(
                IllegalArgumentException.class,
                () -> attempt.reconcileSuccess(" ", PaymentMethod.UPI)
        );
    }

    @Test
    void shouldKeepNormalSuccessTransitionStrictForFailedAttempt() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.markFailed("Payment failed");

        assertThrows(
                IllegalStateException.class,
                () -> attempt.markSuccess("pay_123", PaymentMethod.UPI)
        );
    }

    @Test
    void shouldKeepNormalSuccessTransitionStrictForAbandonedAttempt() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.abandon();

        assertThrows(
                IllegalStateException.class,
                () -> attempt.markSuccess("pay_123", PaymentMethod.UPI)
        );
    }
}
