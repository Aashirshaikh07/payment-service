package com.aashir.payment.service;

import com.aashir.payment.entity.OutboxEvent;
import com.aashir.payment.event.PaymentFailedEvent;
import com.aashir.payment.event.PaymentSucceededEvent;
import com.aashir.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {
        List<OutboxEvent> pending =
                outboxEventRepository.findTop50ByProcessedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent outboxEvent : pending) {
            try {
                Object event = toEvent(outboxEvent);

                kafkaTemplate
                        .send(outboxEvent.getTopic(), outboxEvent.getMessageKey(), event)
                        .get(10, TimeUnit.SECONDS);

                outboxEvent.setProcessedAt(Instant.now());
                outboxEventRepository.save(outboxEvent);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                log.error("Failed to publish outbox event {}", outboxEvent.getId(), exception);
                return;
            }
        }
    }

    private Object toEvent(OutboxEvent outboxEvent) {
        return switch (outboxEvent.getEventType()) {
            case OutboxService.PAYMENT_SUCCEEDED -> objectMapper.readValue(
                    outboxEvent.getPayload(),
                    PaymentSucceededEvent.class
            );
            case OutboxService.PAYMENT_FAILED -> objectMapper.readValue(
                    outboxEvent.getPayload(),
                    PaymentFailedEvent.class
            );
            default -> throw new IllegalStateException(
                    "Unknown outbox event type: " + outboxEvent.getEventType()
            );
        };
    }
}