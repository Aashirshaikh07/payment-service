package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.PaymentRepository;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentExpiryServiceTest {
    private static final long EXPIRY_MINUTES = 30;
    private static final String EXPECTED_REASON = "Payment not completed within 30 minutes";


    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RazorpayGateway razorpayGateway;
    @Mock
    private OutboxService outboxService;

    private PaymentExpiryService service;

    @BeforeEach
    void setUp() {
        service = new PaymentExpiryService(paymentRepository, razorpayGateway, outboxService);

    }

    private PaymentAttempt attempt(String gatewayOrderId,boolean terminal){
        PaymentAttempt attempt = mock(PaymentAttempt.class);
        when(attempt.getGatewayOrderId()).thenReturn(gatewayOrderId);
        lenient().when(attempt.isTerminal()).thenReturn(terminal);
        return attempt;
    }

    private Payment pendingPaymentWith(PaymentAttempt... attempts){
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getAttempts()).thenReturn(List.of(attempts));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        return payment;
    }

    private com.razorpay.Payment gatewayPayment(String status) {
        JSONObject json = mock(JSONObject.class);
        when(json.optString("status", "")).thenReturn(status);

        com.razorpay.Payment gatewayPayment = mock(com.razorpay.Payment.class);
        when(gatewayPayment.toJson()).thenReturn(json);
        return gatewayPayment;
    }

    private void gatewayReturns(String gatewayOrderId, String... statuses) throws RazorpayException {
        List<com.razorpay.Payment> payments = new ArrayList<>();
        for (String status : statuses) {
            payments.add(gatewayPayment(status));
        }
        when(razorpayGateway.fetchOrderPayments(gatewayOrderId)).thenReturn(payments);
    }

    @Test
    void returnFalseWhenPaymentDoesNotExist(){
        when(paymentRepository.findById(1L)).thenReturn(Optional.empty());

        assertFalse(service.expire(1L, EXPIRY_MINUTES));

        verifyNoInteractions(razorpayGateway, outboxService);
        verify(paymentRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    void skipsPaymentsThatAreNotPending(PaymentStatus status) {
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(status);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertFalse(service.expire(1L, EXPIRY_MINUTES));

        verify(payment, never()).markFailed();
        verifyNoInteractions(razorpayGateway, outboxService);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void expiresARealPendingPaymentThatHasNotAttempts(){
        Payment payment = Payment.create(1L,2L,new BigDecimal("999.00"),"INR","key-1");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertTrue(service.expire(1L, EXPIRY_MINUTES));

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        verify(outboxService).savePaymentFailed(payment,EXPECTED_REASON);
        verify(paymentRepository).save(payment);
        verifyNoInteractions(razorpayGateway);

    }

    @Test
    void abandonsOpenAttemptsBeforeMarkingThePaymentFailed(){
        PaymentAttempt open = attempt(null,false);
        Payment payment = pendingPaymentWith(open);

        assertTrue(service.expire(1L, EXPIRY_MINUTES));

        InOrder order = inOrder(open,payment);
        order.verify(open).abandon();
        order.verify(payment).markFailed();
        verify(outboxService).savePaymentFailed(payment,EXPECTED_REASON);
        verify(paymentRepository).save(payment);
        verifyNoInteractions(razorpayGateway);
    }

    @Test
    void leavesAlreadyClosedAttemptsAlone() {
        PaymentAttempt closed = attempt(null, true);
        Payment payment = pendingPaymentWith(closed);

        assertTrue(service.expire(1L, EXPIRY_MINUTES));

        verify(closed, never()).abandon();
        verify(payment).markFailed();
    }

    @ParameterizedTest
    @ValueSource(strings =  {"captured", "authorized", "CAPTURED"})
    void doesNotExpireWhenTheGatewayHoldsMoney(String gatewayStatus) throws Exception {
        PaymentAttempt attempt = attempt("order_1", false);
        Payment payment = pendingPaymentWith(attempt);
        gatewayReturns("order_1", gatewayStatus);

        assertFalse(service.expire(1L, EXPIRY_MINUTES));

        verify(payment, never()).markFailed();
        verify(attempt, never()).abandon();
        verifyNoInteractions(outboxService);
        verify(paymentRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"created","failed","refunded"})
    void expiresWhenTheGatewayShowsNoMoneyHeld(String gatewayStatus) throws Exception {
        PaymentAttempt attempt = attempt("order_1", false);
        Payment payment = pendingPaymentWith(attempt);
        gatewayReturns("order_1", gatewayStatus);

        assertTrue(service.expire(1L, EXPIRY_MINUTES));

        verify(attempt).abandon();
        verify(payment).markFailed();
        verify(outboxService).savePaymentFailed(payment, EXPECTED_REASON);
    }
    @Test
    void expiresWhenTheGatewayHasNoPaymentsForTheOrder() throws Exception {
        PaymentAttempt attempt = attempt("order_1", false);
        Payment payment = pendingPaymentWith(attempt);
        gatewayReturns("order_1");

        assertTrue(service.expire(1L, EXPIRY_MINUTES));

        verify(payment).markFailed();
    }

    @Test
    void doesNotExpireWhenTheGatewayCannotBeChecked() throws Exception {
        PaymentAttempt attempt = attempt("order_1", false);
        Payment payment = pendingPaymentWith(attempt);
        when(razorpayGateway.fetchOrderPayments("order_1")).thenThrow(new RazorpayException("timeout"));

        assertFalse(service.expire(1L, EXPIRY_MINUTES));

        verify(payment, never()).markFailed();
        verifyNoInteractions(outboxService);
        verify(paymentRepository, never()).save(any());
    }
    @Test
    void checksEveryAttemptAndBlocksWhenALaterOneHoldsMoney() throws Exception {
        PaymentAttempt first = attempt("order_1", true);
        PaymentAttempt second = attempt("order_2", false);
        Payment payment = pendingPaymentWith(first, second);
        gatewayReturns("order_1", "failed");
        gatewayReturns("order_2", "captured");

        assertFalse(service.expire(1L, EXPIRY_MINUTES));

        verify(razorpayGateway).fetchOrderPayments("order_1");
        verify(razorpayGateway).fetchOrderPayments("order_2");
        verify(payment, never()).markFailed();
        verifyNoInteractions(outboxService);
    }
}
