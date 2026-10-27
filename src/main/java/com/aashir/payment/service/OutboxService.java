package com.aashir.payment.service;

import com.aashir.payment.entity.OutboxEvent;
import com.aashir.payment.entity.Payment;
import com.aashir.payment.event.PaymentFailedEvent;
import com.aashir.payment.event.PaymentSucceededEvent;
import com.aashir.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_SUCCEEDED_TOPIC = "payment-succeeded";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_FAILED_TOPIC = "payment-failed";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePaymentSucceeded(Payment payment) {
        String dedupeKey = "payment-" + payment.getId() + "-succeeded";

        if (outboxEventRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }

        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = new PaymentSucceededEvent(
                eventId,
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount()
        );

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(eventId);
        outboxEvent.setDedupeKey(dedupeKey);
        outboxEvent.setEventType(PAYMENT_SUCCEEDED);
        outboxEvent.setTopic(PAYMENT_SUCCEEDED_TOPIC);
        outboxEvent.setMessageKey(payment.getOrderId().toString());
        outboxEvent.setPayload(objectMapper.writeValueAsString(event));
        outboxEvent.setCreatedAt(Instant.now());

        outboxEventRepository.save(outboxEvent);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePaymentFailed(Payment payment, String reason) {
        String dedupeKey = "payment-" + payment.getId() + "-failed";

        if (outboxEventRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }

        UUID eventId = UUID.randomUUID();

        PaymentFailedEvent event = new PaymentFailedEvent(
                eventId,
                payment.getId(),
                payment.getOrderId(),
                reason
        );

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(eventId);
        outboxEvent.setDedupeKey(dedupeKey);
        outboxEvent.setEventType(PAYMENT_FAILED);
        outboxEvent.setTopic(PAYMENT_FAILED_TOPIC);
        outboxEvent.setMessageKey(payment.getOrderId().toString());
        outboxEvent.setPayload(objectMapper.writeValueAsString(event));
        outboxEvent.setCreatedAt(Instant.now());

        outboxEventRepository.save(outboxEvent);
    }
}