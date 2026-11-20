package com.aashir.payment.service;

import com.aashir.payment.entity.OutboxEvent;
import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentAttemptStatus;
import com.aashir.payment.entity.PaymentMethod;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.event.PaymentSucceededEvent;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.OutboxEventRepository;
import com.aashir.payment.repository.PaymentAttemptRepository;
import com.aashir.payment.repository.PaymentRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WebhookPaymentReconciliationService.class, OutboxService.class, ObjectMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WebhookPaymentReconciliationIntegrationTest {

    private static final String CAPTURED_EVENT = "payment.captured";
    private static final String FIRST_ORDER_ID = "order_123";
    private static final String FIRST_PAYMENT_ID = "pay_123";
    private static final BigDecimal AMOUNT = new BigDecimal("2500.00");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private WebhookPaymentReconciliationService reconciliationService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RazorpayGateway gateway;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
        attemptRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        when(gateway.toPaise(AMOUNT)).thenReturn(250000L);
    }

    @Test
    void shouldPersistOneSuccessEventWhenTheSameCaptureIsReplayed() throws Exception {
        Payment payment = newPayment();
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.attachGatewayOrder(FIRST_ORDER_ID);
        attempt.abandon();
        payment.markFailed();
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        stubCapture(FIRST_ORDER_ID, FIRST_PAYMENT_ID);
        String firstEvent = capturedWebhook("evt_original", FIRST_ORDER_ID, FIRST_PAYMENT_ID);

        reconciliationService.reconcile(CAPTURED_EVENT, firstEvent);
        assertEquals(1, outboxRepository.count());
        OutboxEvent firstEventRecord = outboxRepository.findAll().getFirst();

        reconciliationService.reconcile(CAPTURED_EVENT, firstEvent);
        reconciliationService.reconcile(
                CAPTURED_EVENT, capturedWebhook("evt_replay", FIRST_ORDER_ID, FIRST_PAYMENT_ID)
        );

        assertCapturedPayment(paymentId);
        OutboxEvent recordedEvent = assertSingleSuccessEvent(paymentId);
        assertEquals(firstEventRecord.getId(), recordedEvent.getId());
        PaymentSucceededEvent payload = objectMapper.readValue(recordedEvent.getPayload(), PaymentSucceededEvent.class);
        assertEquals(recordedEvent.getId(), payload.eventId());
        assertEquals(paymentId, payload.paymentId());
        assertEquals(101L, payload.orderId());
        assertEquals(0, AMOUNT.compareTo(payload.amount()));
    }

    @Test
    void shouldRejectDifferentCaptureForAnAlreadySuccessfulPayment() throws Exception {
        String otherOrderId = "order_456";
        String otherPaymentId = "pay_456";
        Payment payment = newPayment();
        PaymentAttempt firstAttempt = payment.startAttempt(PaymentMethod.UPI);
        firstAttempt.attachGatewayOrder(FIRST_ORDER_ID);
        firstAttempt.markFailed("Local failure");
        payment.startAttempt(PaymentMethod.CARD).attachGatewayOrder(otherOrderId);
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        stubCapture(FIRST_ORDER_ID, FIRST_PAYMENT_ID);
        stubCapture(otherOrderId, otherPaymentId);

        reconciliationService.reconcile(
                CAPTURED_EVENT, capturedWebhook("evt_original", FIRST_ORDER_ID, FIRST_PAYMENT_ID)
        );
        OutboxEvent firstEventRecord = assertSingleSuccessEvent(paymentId);

        assertThrows(IllegalStateException.class, () -> reconciliationService.reconcile(
                CAPTURED_EVENT, capturedWebhook("evt_conflict", otherOrderId, otherPaymentId)
        ));

        assertCapturedPayment(paymentId);
        PaymentAttempt otherAttempt = attemptRepository.findByGatewayOrderId(otherOrderId).orElseThrow();
        assertEquals(PaymentAttemptStatus.CHECKOUT_CREATED, otherAttempt.getStatus());
        assertNull(otherAttempt.getGatewayPaymentId());
        assertEquals(firstEventRecord.getId(), assertSingleSuccessEvent(paymentId).getId());
    }

    private Payment newPayment() {
        return Payment.create(101L, 1L, AMOUNT, "INR", "ORDER-101");
    }

    private void assertCapturedPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        PaymentAttempt attempt = attemptRepository.findByGatewayOrderId(FIRST_ORDER_ID).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(FIRST_PAYMENT_ID, payment.getTransactionId());
        assertEquals(PaymentAttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals(FIRST_PAYMENT_ID, attempt.getGatewayPaymentId());
        assertNull(attempt.getFailureReason());
    }

    private OutboxEvent assertSingleSuccessEvent(Long paymentId) {
        var events = outboxRepository.findAll();
        assertEquals(1, events.size());
        OutboxEvent event = events.getFirst();
        assertEquals("PAYMENT_SUCCEEDED", event.getEventType());
        assertEquals("payment-" + paymentId + "-succeeded", event.getDedupeKey());
        return event;
    }

    private void stubCapture(String orderId, String paymentId) throws Exception {
        com.razorpay.Payment gatewayPayment = mock(com.razorpay.Payment.class);
        when(gateway.fetchPayment(paymentId)).thenReturn(gatewayPayment);
        when(gatewayPayment.toJson()).thenReturn(new JSONObject()
                .put("id", paymentId)
                .put("order_id", orderId)
                .put("amount", 250000)
                .put("currency", "INR")
                .put("status", "captured"));
    }

    private String capturedWebhook(String eventId, String orderId, String paymentId) {
        return """
                {"id":"%s","event":"payment.captured","payload":{"payment":{"entity":{
                  "id":"%s","order_id":"%s"
                }}}}
                """.formatted(eventId, paymentId, orderId);
    }
}
