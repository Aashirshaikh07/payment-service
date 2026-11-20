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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @ParameterizedTest
    @CsvSource({
            "outbox_events, event_type, PAYMENT_SUCCEEDED",
            "payments, status, SUCCESS"
    })
    void shouldRollBackCaptureWhenDatabaseRejectsAWrite(String table, String column, String value)
            throws Exception {
        Payment payment = newPayment();
        PaymentAttempt attempt = payment.startAttempt(PaymentMethod.UPI);
        attempt.attachGatewayOrder(FIRST_ORDER_ID);
        attempt.markFailed("Local failure");
        payment.markFailed();
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        stubCapture(FIRST_ORDER_ID, FIRST_PAYMENT_ID);

        String event = capturedWebhook("evt_capture", FIRST_ORDER_ID, FIRST_PAYMENT_ID);
        jdbcTemplate.execute("ALTER TABLE %s ADD CONSTRAINT stage5_reject_write CHECK (%s <> '%s')"
                .formatted(table, column, value));
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> reconciliationService.reconcile(CAPTURED_EVENT, event));
            assertTrue(NestedExceptionUtils.getMostSpecificCause(failure).getMessage()
                    .contains("stage5_reject_write"), failure::toString);

            Payment storedPayment = paymentRepository.findById(paymentId).orElseThrow();
            PaymentAttempt storedAttempt = attemptRepository.findByGatewayOrderId(FIRST_ORDER_ID).orElseThrow();
            assertEquals(PaymentStatus.FAILED, storedPayment.getStatus());
            assertNull(storedPayment.getTransactionId());
            assertEquals(PaymentAttemptStatus.FAILED, storedAttempt.getStatus());
            assertNull(storedAttempt.getGatewayPaymentId());
            assertEquals("Local failure", storedAttempt.getFailureReason());
            assertEquals(0, outboxRepository.count());
        } finally {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CHECK stage5_reject_write");
        }

        reconciliationService.reconcile(CAPTURED_EVENT, event);
        assertCapturedPayment(paymentId);
        assertSingleSuccessEvent(paymentId);
    }

    @Test
    void shouldRejectConcurrentFailureUsingAnAttemptLoadedBeforeCapture() throws Exception {
        String failedPaymentId = "pay_failed";
        Payment payment = newPayment();
        payment.startAttempt(PaymentMethod.UPI).attachGatewayOrder(FIRST_ORDER_ID);
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        stubCapture(FIRST_ORDER_ID, FIRST_PAYMENT_ID);
        stubGatewayPayment(FIRST_ORDER_ID, failedPaymentId, "failed");
        String failedEvent = webhook("payment.failed", "evt_failure", FIRST_ORDER_ID, failedPaymentId);

        CountDownLatch attemptLoaded = new CountDownLatch(1);
        CountDownLatch captureCommitted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var staleFailure = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                PaymentAttempt staleAttempt = attemptRepository.findByGatewayOrderId(FIRST_ORDER_ID).orElseThrow();
                assertEquals(PaymentAttemptStatus.CHECKOUT_CREATED, staleAttempt.getStatus());
                attemptLoaded.countDown();
                await(captureCommitted);
                reconciliationService.reconcile("payment.failed", failedEvent);
            }));

            await(attemptLoaded);
            reconciliationService.reconcile(
                    CAPTURED_EVENT, capturedWebhook("evt_capture", FIRST_ORDER_ID, FIRST_PAYMENT_ID)
            );
            captureCommitted.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> staleFailure.get(30, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, failure.getCause());
        } finally {
            captureCommitted.countDown();
            executor.shutdownNow();
        }

        reconciliationService.reconcile("payment.failed", failedEvent);
        assertCapturedPayment(paymentId);
        assertSingleSuccessEvent(paymentId);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for the other transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for the other transaction", exception);
        }
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
        stubGatewayPayment(orderId, paymentId, "captured");
    }

    private void stubGatewayPayment(String orderId, String paymentId, String status) throws Exception {
        com.razorpay.Payment gatewayPayment = mock(com.razorpay.Payment.class);
        when(gateway.fetchPayment(paymentId)).thenReturn(gatewayPayment);
        when(gatewayPayment.toJson()).thenReturn(new JSONObject()
                .put("id", paymentId)
                .put("order_id", orderId)
                .put("amount", 250000)
                .put("currency", "INR")
                .put("status", status));
    }

    private String capturedWebhook(String eventId, String orderId, String paymentId) {
        return webhook(CAPTURED_EVENT, eventId, orderId, paymentId);
    }

    private String webhook(String eventType, String eventId, String orderId, String paymentId) {
        return """
                {"id":"%s","event":"%s","payload":{"payment":{"entity":{
                  "id":"%s","order_id":"%s"
                }}}}
                """.formatted(eventId, eventType, paymentId, orderId);
    }
}
