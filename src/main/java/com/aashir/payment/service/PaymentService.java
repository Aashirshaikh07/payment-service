package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.event.PaymentSucceededEvent;
import com.aashir.payment.kafka.PaymentKafkaProducer;
import com.aashir.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentKafkaProducer paymentKafkaProducer;

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
            PaymentSucceededEvent event =
                    new PaymentSucceededEvent(
                            savedPayment.getId(),
                            savedPayment.getOrderId(),
                            savedPayment.getAmount()
                    );
            paymentKafkaProducer.publishPaymentSucceeded(event);
        }
        return savedPayment;
    }
}
