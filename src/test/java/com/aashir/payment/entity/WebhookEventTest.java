package com.aashir.payment.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WebhookEventTest {

    private WebhookEvent createEvent() {
        return WebhookEvent.receive(
                "evt_test_123",
                "payment.captured",
                "{\"id\":\"evt_test_123\"}"
        );
    }
    @Test
    void shouldStartInReceivedState() {
        WebhookEvent event = createEvent();

        assertEquals(WebhookEventStatus.RECEIVED, event.getStatus());
        assertNotNull(event.getReceivedAt());
        assertNull(event.getProcessedAt());
        assertNull(event.getLastError());
    }

    @Test
    void shouldMoveFromReceivedToProcessing() {
        WebhookEvent event = createEvent();

        event.markProcessing();

        assertEquals(WebhookEventStatus.PROCESSING, event.getStatus());
        assertNull(event.getProcessedAt());
        assertNull(event.getLastError());
    }

    @Test
    void shouldMarkProcessingEventAsProcessed() {
        WebhookEvent event = createEvent();

        event.markProcessing();
        event.markProcessed();

        assertEquals(WebhookEventStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getLastError());
    }

    @Test
    void shouldMarkProcessingEventAsFailed() {
        WebhookEvent event = createEvent();

        event.markProcessing();
        event.markFailed("Payment reconciliation failed");

        assertEquals(WebhookEventStatus.FAILED, event.getStatus());
        assertEquals("Payment reconciliation failed", event.getLastError());
        assertNull(event.getProcessedAt());
    }

    @Test
    void shouldAllowRetryAfterFailure() {
        WebhookEvent event = createEvent();

        event.markProcessing();
        event.markFailed("Temporary gateway error");

        event.markProcessing();

        assertEquals(WebhookEventStatus.PROCESSING, event.getStatus());
        assertNull(event.getLastError());
        assertNull(event.getProcessedAt());
    }

    @Test
    void shouldRejectProcessingWhenAlreadyProcessing() {
        WebhookEvent event = createEvent();

        event.markProcessing();

        assertThrows(
                IllegalStateException.class,
                event::markProcessing
        );

        assertEquals(WebhookEventStatus.PROCESSING, event.getStatus());
    }

    @Test
    void shouldRejectProcessingWhenAlreadyProcessed() {
        WebhookEvent event = createEvent();

        event.markProcessing();
        event.markProcessed();

        assertThrows(
                IllegalStateException.class,
                event::markProcessing
        );

        assertEquals(WebhookEventStatus.PROCESSED, event.getStatus());
    }

    @Test
    void shouldRejectMarkingReceivedEventAsProcessed() {
        WebhookEvent event = createEvent();

        assertThrows(
                IllegalStateException.class,
                event::markProcessed
        );

        assertEquals(WebhookEventStatus.RECEIVED, event.getStatus());
    }

    @Test
    void shouldRejectMarkingReceivedEventAsFailed() {
        WebhookEvent event = createEvent();

        assertThrows(
                IllegalStateException.class,
                () -> event.markFailed("Unexpected failure")
        );

        assertEquals(WebhookEventStatus.RECEIVED, event.getStatus());
    }

}
