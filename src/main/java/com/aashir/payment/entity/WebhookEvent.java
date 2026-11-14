package com.aashir.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "webhook_events",
        indexes = {
                @Index(
                        name = "idx_webhook_status_received",
                        columnList = "status, received_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

    private static final int MAX_EVENT_ID_LENGTH = 100;
    private static final int MAX_EVENT_TYPE_LENGTH = 100;
    private static final int MAX_ERROR_LENGTH = 1000;

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

        return event;
    }

    public void markProcessing() {
        if (status != WebhookEventStatus.RECEIVED
                && status != WebhookEventStatus.FAILED) {
            throw new IllegalStateException(
                    "Cannot process a webhook event in status " + status
            );
        }

        this.status = WebhookEventStatus.PROCESSING;
        this.processingAt = Instant.now();
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
        this.lastError = null;
    }

    public void markFailed(String reason) {
        if (status != WebhookEventStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only a processing webhook event can be marked failed"
            );
        }

        this.status = WebhookEventStatus.FAILED;
        this.processingAt = null;
        this.lastError = truncate(reason, MAX_ERROR_LENGTH);
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