package com.aashir.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Entity
@Table(
        name = "webhook_events",
        indexes = {
                @Index(
                        name = "idx_webhook_status_received",
                        columnList = "status, received_at"
                ),
                @Index(
                        name = "idx_webhook_status_retry",
                        columnList = "status,next_retry_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

    private static final int MAX_EVENT_ID_LENGTH = 100;
    private static final int MAX_EVENT_TYPE_LENGTH = 100;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_ATTEMPTS = 6;

    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(45),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5)
    );

    @Id
    @Column(name = "event_id", length = MAX_EVENT_ID_LENGTH, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", length = MAX_EVENT_TYPE_LENGTH, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WebhookEventStatus status;

    @Column(name = "processing_at")
    private Instant processingAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    public static WebhookEvent receive(
            String eventId,
            String eventType,
            String rawPayload
    ) {
        requireText(eventId, "Webhook event ID");
        requireText(eventType, "Webhook event type");
        requireText(rawPayload, "Webhook payload");

        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new IllegalArgumentException("Webhook event ID is too long");
        }

        if (eventType.length() > MAX_EVENT_TYPE_LENGTH) {
            throw new IllegalArgumentException("Webhook event type is too long");
        }

        WebhookEvent event = new WebhookEvent();
        event.eventId = eventId;
        event.eventType = eventType;
        event.rawPayload = rawPayload;
        event.status = WebhookEventStatus.RECEIVED;
        event.receivedAt = Instant.now();
        event.attemptCount = 0;
        return event;
    }

    public void markProcessing() {
        if (status != WebhookEventStatus.RECEIVED
                && status != WebhookEventStatus.FAILED) {
            throw new IllegalStateException(
                    "Cannot process a webhook event in status " + status
            );
        }

        if (attemptCount >= MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "Webhook event has exhausted its processing attempts"
            );
        }
        this.attemptCount++;
        this.status = WebhookEventStatus.PROCESSING;
        this.processingAt = Instant.now();
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void markProcessed() {
        if (status != WebhookEventStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only a processing webhook event can be marked processed"
            );
        }

        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.processingAt = null;
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void markFailed(String reason) {
        if (status != WebhookEventStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only a processing webhook event can be marked failed"
            );
        }

        this.processingAt = null;
        this.lastError = truncate(reason, MAX_ERROR_LENGTH);

        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = WebhookEventStatus.DEAD;
            this.nextRetryAt = null;
            return;
        }

        Duration retryDelay = RETRY_DELAYS.get(attemptCount - 1);

        this.status = WebhookEventStatus.FAILED;
        this.nextRetryAt = Instant.now().plus(retryDelay);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}