package com.aashir.payment.service;

import com.aashir.payment.dto.VerifyPaymentRequest;
import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentAttemptStatus;
import com.aashir.payment.entity.PaymentMethod;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.PaymentAttemptRepository;
import com.aashir.payment.repository.PaymentRepository;
import com.aashir.payment.security.AuthenticatedUser;
import com.razorpay.RazorpayException;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentVerificationServiceTest {

    private final VerifyPaymentRequest request =
            new VerifyPaymentRequest(42L, "order_123", "pay_123", "signature");
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentAttemptRepository attemptRepository = mock(PaymentAttemptRepository.class);
    private final RazorpayGateway gateway = mock(RazorpayGateway.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final PaymentVerificationService service =
            new PaymentVerificationService(paymentRepository, attemptRepository, gateway, outboxService);

    private Payment payment;
    private PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        var user = new AuthenticatedUser(1L, "customer@example.com", "CUSTOMER");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())
        );
        payment = Payment.create(101L, user.getUserId(), new BigDecimal("2500.00"), "INR", "ORDER-101");
        ReflectionTestUtils.setField(payment, "id", request.paymentId());
        attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.attachGatewayOrder(request.razorpayOrderId());

        when(paymentRepository.findById(request.paymentId())).thenReturn(Optional.of(payment));
        when(attemptRepository.findByGatewayOrderId(request.razorpayOrderId()))
                .thenReturn(Optional.of(attempt));
        when(gateway.verifyPaymentSignature(
                request.razorpayOrderId(), request.razorpayPaymentId(), request.razorpaySignature()
        )).thenReturn(true);
        when(gateway.toPaise(payment.getAmount())).thenReturn(250000L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"FAILED", "ABANDONED"})
    void shouldRecoverLocallyFailedPaymentAfterVerifiedCapture(PaymentAttemptStatus localStatus)
            throws RazorpayException, JSONException {
        if (localStatus == PaymentAttemptStatus.FAILED) {
            attempt.markFailed("Local failure");
        } else {
            attempt.abandon();
        }
        payment.markFailed();
        stubGatewayPayment(request.razorpayPaymentId(), "captured");

        assertEquals("SUCCESS", service.verify(request).status());
        assertEquals("SUCCESS", service.verify(request).status());
        VerifyPaymentRequest conflictingRequest = new VerifyPaymentRequest(
                request.paymentId(), request.razorpayOrderId(), "pay_other", request.razorpaySignature()
        );
        assertThrows(IllegalStateException.class, () -> service.verify(conflictingRequest));

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals(request.razorpayPaymentId(), payment.getTransactionId());
        assertEquals(request.razorpayPaymentId(), attempt.getGatewayPaymentId());
        assertEquals(PaymentMethod.UPI, attempt.getPaymentMethod());
        assertNull(attempt.getFailureReason());
        verify(gateway).fetchPayment(request.razorpayPaymentId());
        verify(attemptRepository).save(attempt);
        verify(paymentRepository).save(payment);
        verify(outboxService).savePaymentSucceeded(payment);
    }

    @Test
    void shouldLeaveLocalFailureIntactWhenGatewayHasOnlyAuthorizedPayment()
            throws RazorpayException, JSONException {
        attempt.markFailed("Local failure");
        payment.markFailed();
        stubGatewayPayment(request.razorpayPaymentId(), "authorized");

        assertThrows(IllegalStateException.class, () -> service.verify(request));

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(PaymentAttemptStatus.FAILED, attempt.getStatus());
        assertEquals("Local failure", attempt.getFailureReason());
        verify(paymentRepository, never()).save(any());
        verify(attemptRepository, never()).save(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void shouldRejectCaptureWhenGatewayReturnsDifferentPaymentId() throws RazorpayException, JSONException {
        stubGatewayPayment("pay_other", "captured");

        assertThrows(IllegalArgumentException.class, () -> service.verify(request));

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(PaymentAttemptStatus.CHECKOUT_CREATED, attempt.getStatus());
        verify(paymentRepository, never()).save(any());
        verify(attemptRepository, never()).save(any());
        verifyNoInteractions(outboxService);
    }

    private void stubGatewayPayment(String paymentId, String status) throws RazorpayException, JSONException {
        com.razorpay.Payment gatewayPayment = mock(com.razorpay.Payment.class);
        when(gateway.fetchPayment(request.razorpayPaymentId())).thenReturn(gatewayPayment);
        when(gatewayPayment.toJson()).thenReturn(new JSONObject()
                .put("id", paymentId)
                .put("order_id", request.razorpayOrderId())
                .put("amount", 250000)
                .put("currency", "INR")
                .put("status", status));
    }
}
