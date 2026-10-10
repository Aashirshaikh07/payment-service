package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.entity.ProcessedEvent;
import com.aashir.payment.event.*;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.kafka.PaymentKafkaProducer;
import com.aashir.payment.provider.PaymentProvider;
import com.aashir.payment.repository.CheckoutRequestRepository;
import com.aashir.payment.repository.PaymentAttemptRepository;
import com.aashir.payment.repository.PaymentRepository;
import com.aashir.payment.repository.ProcessedEventRepository;
import com.aashir.payment.security.AuthenticatedUser;
import com.aashir.payment.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentKafkaProducer paymentKafkaProducer;
    private final PaymentProvider paymentProvider;
    private final ProcessedEventRepository processedEventRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final CheckoutRequestRepository checkoutRequestRepository;
    private final RazorpayGateway razorpayGateway;

    public Payment createPayment(Payment payment,String idempotencyKey) {

        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> paymentRepository.save(
                        Payment.create(
                                payment.getOrderId(),
                                payment.getUserId(),
                                payment.getAmount(),
                                "INR",
                                idempotencyKey
                        )
                ));
    }

    public Payment updatePayment(Long paymentId, PaymentStatus status) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        Optional<PaymentAttempt> latestAttempt =
                paymentAttemptRepository.findTopByPaymentIdOrderByAttemptNumberDesc(paymentId);

        if(status == PaymentStatus.SUCCESS){
            throw new UnsupportedOperationException(
                    "Payment success must be confirmed through verified gateway processing"
            );
        }
        if (status == PaymentStatus.FAILED) {
            payment.markFailed();
        } else {
            throw new IllegalArgumentException(
                    "Unsupported payment status update: " + status
            );
        }
        Payment savedPayment = paymentRepository.save(payment);

        return savedPayment;
    }

    public Payment processPayment(Payment payment) {
        throw new UnsupportedOperationException(
                "Mock payment processing is retired. Use the Razorpay checkout flow."
        );
    }

    @Transactional
    public void handleOrderCreated(OrderCreatedKafkaEvent event){
        if(processedEventRepository.existsById(event.eventId())){
            return;
        }

        Payment payment = Payment.create(
                event.orderId(),
                event.userId(),
                event.totalAmount(),
                "INR",
                "ORDER-" + event.orderId()
        );
        createPayment(payment,"ORDER-" + event.orderId());
        ProcessedEvent processedEvent = new ProcessedEvent();
        processedEvent.setEventId(event.eventId());
        processedEvent.setProcessedAt(LocalDateTime.now());

        processedEventRepository.save(processedEvent);

    }

    private Long getUserId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        AuthenticatedUser user =
                (AuthenticatedUser) authentication.getPrincipal();

        return user.getUserId();
    }

    @Transactional
    public PaymentSuccessResponse getPaymentProccess(PaymentRequest paymentRequest) {

        Long userId = getUserId();

        Payment payment = paymentRepository.findByOrderId(paymentRequest.orderId())
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        if(!Objects.equals(payment.getUserId(), userId)){
            throw new  RuntimeException(String.format("Payment not found for orderId=%d", paymentRequest.orderId()));
        }

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            throw new RuntimeException("Payment already completed");
        }

        throw new UnsupportedOperationException(
                "Payment initiation must use the Razorpay checkout flow."
        );

    }
}
