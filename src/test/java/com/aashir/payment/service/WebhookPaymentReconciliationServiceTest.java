
package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.PaymentAttemptRepository;
import com.aashir.payment.service.OutboxService;
import com.razorpay.RazorpayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WebhookPaymentReconciliationServiceTest {

    private RazorpayGateway razorpayGateway;
    private PaymentAttemptRepository paymentAttemptRepository;
    private OutboxService outboxService;
    private WebhookPaymentReconciliationService reconciliationService;

    private PaymentAttempt attempt;
    private Payment payment;
    private com.razorpay.Payment gatewayPayment;

    @BeforeEach
    void setUp() throws RazorpayException {
        razorpayGateway = mock(RazorpayGateway.class);
        paymentAttemptRepository = mock(PaymentAttemptRepository.class);
        outboxService = mock(OutboxService.class);

        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);

        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        reconciliationService = new WebhookPaymentReconciliationService(
                razorpayGateway,
                paymentAttemptRepository,
                outboxService,
                transactionTemplate
        );

        attempt = mock(PaymentAttempt.class);
        payment = mock(Payment.class);
        gatewayPayment = mock(com.razorpay.Payment.class);

        when(attempt.getPayment()).thenReturn(payment);
        when(payment.getAmount()).thenReturn(new BigDecimal("2500.00"));
        when(payment.getCurrency()).thenReturn("INR");

        when(razorpayGateway.toPaise(new BigDecimal("2500.00")))
                .thenReturn(250000L);
    }

    @Test
    void shouldReconcileCapturedPaymentAndPublishSuccessEvent()
            throws Exception {

        String webhookPayload = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_123",
                        "order_id": "order_123"
                      }
                    }
                  }
                }
                """;

        String gatewayResponse = """
                {
                  "id": "pay_123",
                  "order_id": "order_123",
                  "amount": 250000,
                  "currency": "INR",
                  "status": "captured",
                  "method": "card"
                }
                """;

        when(razorpayGateway.fetchPayment("pay_123"))
                .thenReturn(gatewayPayment);
        when(gatewayPayment.toJson()).thenReturn(
                new org.json.JSONObject(gatewayResponse)
        );

        when(paymentAttemptRepository.findByGatewayOrderId("order_123"))
                .thenReturn(Optional.of(attempt));

        assertDoesNotThrow(() ->
                reconciliationService.reconcile(
                        "payment.captured",
                        webhookPayload
                )
        );

        verify(attempt).reconcileSuccess("pay_123",
                com.aashir.payment.entity.PaymentMethod.CARD);
        verify(payment).reconcileSuccess("pay_123");
        verify(paymentAttemptRepository).save(attempt);
        verify(outboxService).savePaymentSucceeded(payment);
    }

    @Test
    void shouldReconcileFailedPayment()
            throws Exception {

        String webhookPayload = """
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_123",
                        "order_id": "order_123"
                      }
                    }
                  }
                }
                """;

        String gatewayResponse = """
                {
                  "id": "pay_123",
                  "order_id": "order_123",
                  "amount": 250000,
                  "currency": "INR",
                  "status": "failed",
                  "error_description": "Payment declined"
                }
                """;

        when(razorpayGateway.fetchPayment("pay_123"))
                .thenReturn(gatewayPayment);
        when(gatewayPayment.toJson()).thenReturn(
                new org.json.JSONObject(gatewayResponse)
        );

        when(paymentAttemptRepository.findByGatewayOrderId("order_123"))
                .thenReturn(Optional.of(attempt));

        assertDoesNotThrow(() ->
                reconciliationService.reconcile(
                        "payment.failed",
                        webhookPayload
                )
        );

        verify(attempt).markFailed("Payment declined");
        verify(paymentAttemptRepository).save(attempt);
        verify(outboxService, never()).savePaymentSucceeded(any());
    }

    @Test
    void shouldIgnoreUnsupportedWebhookEvent() {
        String webhookPayload = """
                {
                  "event": "order.paid"
                }
                """;

        assertDoesNotThrow(() ->
                reconciliationService.reconcile(
                        "order.paid",
                        webhookPayload
                )
        );

        verifyNoInteractions(razorpayGateway);
        verifyNoInteractions(paymentAttemptRepository);
        verifyNoInteractions(outboxService);
    }

    @Test
    void shouldRejectMismatchedWebhookEventType() {
        String webhookPayload = """
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_123",
                        "order_id": "order_123"
                      }
                    }
                  }
                }
                """;

        assertThrows(IllegalArgumentException.class, () ->
                reconciliationService.reconcile(
                        "payment.captured",
                        webhookPayload
                )
        );

        verifyNoInteractions(razorpayGateway);
    }

    @Test
    void shouldRejectMissingPaymentEntity() {
        String webhookPayload = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {}
                  }
                }
                """;

        assertThrows(IllegalArgumentException.class, () ->
                reconciliationService.reconcile(
                        "payment.captured",
                        webhookPayload
                )
        );

        verifyNoInteractions(razorpayGateway);
    }
}
