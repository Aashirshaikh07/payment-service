package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.entity.ProcessedEvent;
import com.aashir.payment.event.OrderCreatedKafkaEvent;
import com.aashir.payment.event.PaymentFailedEvent;
import com.aashir.payment.event.PaymentSucceededEvent;
import com.aashir.payment.kafka.PaymentKafkaProducer;
import com.aashir.payment.provider.PaymentProvider;
import com.aashir.payment.provider.PaymentResult;
import com.aashir.payment.provider.PaymentResultStatus;
import com.aashir.payment.repository.PaymentRepository;
import com.aashir.payment.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
        payment.setStatus(PaymentStatus.PENDING);
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

    public Payment processPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

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

        if(result.status() == PaymentResultStatus.SUCCESS){
            payment.setStatus(PaymentStatus.SUCCESS);

            Payment savedPayment = paymentRepository.save(payment);

            PaymentSucceededEvent event = new PaymentSucceededEvent(
                    UUID.randomUUID(),
                    savedPayment.getId(),
                    savedPayment.getOrderId(),
                    savedPayment.getAmount()
            );

            paymentKafkaProducer.publishPaymentSucceeded(event);
            return savedPayment;
        }
        payment.setStatus(PaymentStatus.FAILED);
        Payment savedPayment = paymentRepository.save(payment);
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID(),
                savedPayment.getId(),
                savedPayment.getOrderId(),
                result.failureReason()
        );
        paymentKafkaProducer.publishPaymentFailed(event);
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
        payment.setPaymentMethod(event.paymentMethod());

        Payment createdPayment = createPayment(
                payment,
                "ORDER-" + event.orderId()
        );

        processPayment(createdPayment.getId());

        ProcessedEvent processedEvent = new ProcessedEvent();
        processedEvent.setEventId(event.eventId());
        processedEvent.setProcessedAt(LocalDateTime.now());

        processedEventRepository.save(processedEvent);

    }
}
