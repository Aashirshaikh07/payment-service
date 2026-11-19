
package com.aashir.payment.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

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
    void shouldRejectFailureAndNewAttemptsAfterNormalSuccess() {
        payment.markSuccess("pay_123");
        payment.markSuccess("pay_123");

        assertThrows(IllegalStateException.class, () -> payment.markSuccess("pay_456"));
        assertThrows(IllegalStateException.class, payment::markFailed);
        assertThrows(IllegalStateException.class, () -> payment.startAttempt(PaymentMethod.UPI));

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("pay_123", payment.getTransactionId());
        assertTrue(payment.getAttempts().isEmpty());
    }

    @Test
    void shouldFailOnlyAfterOpenAttemptIsResolved() {
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);

        assertThrows(IllegalStateException.class, payment::markFailed);
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(PaymentAttemptStatus.CREATED, attempt.getStatus());

        attempt.markFailed("Payment failed");
        payment.markFailed();
        payment.markFailed();

        assertThrows(IllegalStateException.class, () -> payment.startAttempt(PaymentMethod.UPI));
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertNull(payment.getTransactionId());
        assertEquals(1, payment.getAttempts().size());
    }

    @Test
    void shouldAllowAnotherAttemptAfterFailureWhilePaymentIsPending() {
        PaymentAttempt first = payment.startAttempt(PaymentMethod.UPI);

        assertThrows(IllegalStateException.class, () -> payment.startAttempt(PaymentMethod.CARD));
        assertEquals(1, payment.getAttempts().size());

        first.markFailed("Payment failed");
        PaymentAttempt second = payment.startAttempt(PaymentMethod.CARD);

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(2, second.getAttemptNumber());
        assertSame(payment, second.getPayment());
        assertEquals(PaymentAttemptStatus.CREATED, second.getStatus());
    }

    @Test
    void shouldReconcileSuccessfulPaymentWhenPaymentIsPending() {
        payment.reconcileSuccess("pay_123");

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("pay_123", payment.getTransactionId());
        assertTrue(payment.isSuccessful());
    }

    @Test
    void shouldRecoverFailedPaymentWhenGatewayConfirmsCapture() {
        payment.markFailed();

        assertEquals(PaymentStatus.FAILED, payment.getStatus());

        payment.reconcileSuccess("pay_123");

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("pay_123", payment.getTransactionId());
    }

    @Test
    void shouldAllowDuplicateReconciliationWithSameTransactionId() {
        payment.reconcileSuccess("pay_123");

        assertDoesNotThrow(() -> payment.reconcileSuccess("pay_123"));

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("pay_123", payment.getTransactionId());
    }

    @Test
    void shouldRejectReconciliationWithDifferentTransactionId() {
        payment.reconcileSuccess("pay_123");

        assertThrows(
                IllegalStateException.class,
                () -> payment.reconcileSuccess("pay_456")
        );

        assertEquals("pay_123", payment.getTransactionId());
    }

    @Test
    void shouldRejectNormalSuccessTransitionAfterPaymentFailed() {
        payment.markFailed();

        assertThrows(
                IllegalStateException.class,
                () -> payment.markSuccess("pay_123")
        );
    }

    @Test
    void shouldRejectReconciliationWhenTransactionIdIsBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> payment.reconcileSuccess(" ")
        );
    }

    @Test
    void shouldNotChangeAnAlreadySuccessfulPayment() {
        payment.reconcileSuccess("pay_123");

        payment.reconcileSuccess("pay_123");

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("pay_123", payment.getTransactionId());
    }


}
