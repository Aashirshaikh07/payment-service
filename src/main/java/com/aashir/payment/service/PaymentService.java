package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.entity.ProcessedEvent;
import com.aashir.payment.event.*;
import com.aashir.payment.kafka.PaymentKafkaProducer;
import com.aashir.payment.provider.PaymentProvider;
import com.aashir.payment.provider.PaymentResult;
import com.aashir.payment.provider.PaymentResultStatus;
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

    public Payment createPayment(Payment payment, String idempotencyKey) {

        Optional<Payment> existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey);

        if (existingPayment.isPresent()) {
            return existingPayment.get();
        }
        payment.setIdempotencyKey(idempotencyKey);
        payment.setStatus(PaymentStatus.UNPAID);
        return paymentRepository.save(payment);
    }

    public Payment updatePayment(Long paymentId, PaymentStatus status) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        payment.setStatus(status);
        Payment savedPayment = paymentRepository.save(payment);

        if(status == PaymentStatus.SUCCESS) {
            //it's problem here for UUID
            PaymentSucceededEvent event =
                    new PaymentSucceededEvent(
                            UUID.randomUUID(),
                            savedPayment.getId(),
                            savedPayment.getOrderId(),
                            savedPayment.getAmount()
                    );
            paymentKafkaProducer.publishPaymentSucceeded(event);
        }
        return savedPayment;
    }

    public Payment processPayment(Payment payment) {

        if(payment.getStatus() == PaymentStatus.SUCCESS) {
            return payment;
        }
        if(payment.getStatus() == PaymentStatus.FAILED) {
            return payment;
        }
        PaymentResult result = paymentProvider.processPayment(
                payment.getOrderId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getIdempotencyKey()
        );

        PaymentStatus status = switch (result.status()) {
            case PENDING -> PaymentStatus.PENDING;
            case SUCCESS -> PaymentStatus.SUCCESS;
            case FAILED -> PaymentStatus.FAILED;
        };

        payment.setStatus(status);
        payment.setTransactionId(result.transactionId());
        payment.setFailureReason(result.failureReason());

        Payment savedPayment = paymentRepository.save(payment);

        if(result.status() == PaymentResultStatus.SUCCESS){
            payment.setStatus(PaymentStatus.SUCCESS);

            if (status == PaymentStatus.SUCCESS) {
                PaymentSucceededEvent event = new PaymentSucceededEvent(
                        UUID.randomUUID(),
                        savedPayment.getId(),
                        savedPayment.getOrderId(),
                        savedPayment.getAmount()
                );
                paymentKafkaProducer.publishPaymentSucceeded(event);
            }else if (status == PaymentStatus.FAILED) {
                PaymentFailedEvent event = new PaymentFailedEvent(
                        UUID.randomUUID(),
                        savedPayment.getId(),
                        savedPayment.getOrderId(),
                        result.failureReason()
                );
                paymentKafkaProducer.publishPaymentFailed(event);

            }
        }
        return savedPayment;
    }

    @Transactional
    public void handleOrderCreated(OrderCreatedKafkaEvent event){
        if(processedEventRepository.existsById(event.eventId())){
            return;
        }

        Payment payment = new Payment();
        payment.setOrderId(event.orderId());
        payment.setAmount(event.totalAmount());
        payment.setUserId(event.userId());

        Payment createdPayment = createPayment(
                payment,
                "ORDER-" + event.orderId()
        );
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

        payment.setPaymentMethod(paymentRequest.method());
        Payment savedPayment = processPayment(payment);

        return new PaymentSuccessResponse(
                savedPayment.getId(),
                savedPayment.getAmount(),
                savedPayment.getStatus()
        );

    }
}
