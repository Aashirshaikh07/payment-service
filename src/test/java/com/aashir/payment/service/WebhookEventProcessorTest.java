package com.aashir.payment.service;

import com.aashir.payment.entity.WebhookEvent;
import com.aashir.payment.entity.WebhookEventStatus;
import com.aashir.payment.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookEventProcessorTest {

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private WebhookPaymentReconciliationService reconciliationService;

    @InjectMocks
    private WebhookEventProcessor webhookEventProcessor;

    private static final String EVENT_ID = "event_123";
    private static final String EVENT_TYPE = "payment.captured";
    private static final String RAW_PAYLOAD =
            "{\"payment\":{\"id\":\"pay_123\"}}";

    private WebhookEvent createProcessingEvent() {
        WebhookEvent event = WebhookEvent.receive(
                EVENT_ID,
                EVENT_TYPE,
                RAW_PAYLOAD
        );

        // Simulates the event after a successful database claim.
        event.markProcessing();

        return event;
    }

    @Test
    void processPendingEvents_shouldRecoverStaleProcessingEvents() {

        when(webhookEventRepository.recoverStaleProcessingEvents(
                any(),
                anyString()
        )).thenReturn(2);

        when(webhookEventRepository.findEligibleEvents())
                .thenReturn(List.of());

        webhookEventProcessor.processPendingEvents();

        verify(webhookEventRepository).recoverStaleProcessingEvents(
                any(),
                anyString()
        );

        verify(webhookEventRepository).findEligibleEvents();

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void processPendingEvents_shouldMarkEventProcessedAfterSuccessfulReconciliation() {

        WebhookEvent event = createProcessingEvent();

        when(webhookEventRepository.findEligibleEvents())
                .thenReturn(List.of(event));

        when(webhookEventRepository.claimForProcessing(EVENT_ID))
                .thenReturn(1);

        when(webhookEventRepository.findById(EVENT_ID))
                .thenReturn(Optional.of(event));

        webhookEventProcessor.processPendingEvents();

        verify(reconciliationService).reconcile(
                EVENT_TYPE,
                RAW_PAYLOAD
        );

        verify(webhookEventRepository).save(event);

        assertEquals(WebhookEventStatus.PROCESSED, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getProcessingAt());
        assertNull(event.getNextRetryAt());
        assertNull(event.getLastError());
    }

    @Test
    void processPendingEvents_shouldMarkEventFailedWhenReconciliationFails() {

        WebhookEvent event = createProcessingEvent();

        when(webhookEventRepository.findEligibleEvents())
                .thenReturn(List.of(event));

        when(webhookEventRepository.claimForProcessing(EVENT_ID))
                .thenReturn(1);

        when(webhookEventRepository.findById(EVENT_ID))
                .thenReturn(Optional.of(event));

        doThrow(new IllegalStateException("Gateway reconciliation failed"))
                .when(reconciliationService)
                .reconcile(EVENT_TYPE, RAW_PAYLOAD);

        webhookEventProcessor.processPendingEvents();

        verify(reconciliationService).reconcile(
                EVENT_TYPE,
                RAW_PAYLOAD
        );

        verify(webhookEventRepository).save(event);

        assertEquals(WebhookEventStatus.FAILED, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getProcessingAt());
        assertNotNull(event.getNextRetryAt());

        assertEquals(
                "Gateway reconciliation failed",
                event.getLastError()
        );
    }

    @Test
    void processPendingEvents_shouldNotReconcileEventWhenClaimFails() {

        WebhookEvent candidate = WebhookEvent.receive(
                EVENT_ID,
                EVENT_TYPE,
                RAW_PAYLOAD
        );

        when(webhookEventRepository.findEligibleEvents())
                .thenReturn(List.of(candidate));

        when(webhookEventRepository.claimForProcessing(EVENT_ID))
                .thenReturn(0);

        webhookEventProcessor.processPendingEvents();

        verify(webhookEventRepository).claimForProcessing(EVENT_ID);

        verify(webhookEventRepository, never()).findById(anyString());
        verify(webhookEventRepository, never()).save(any());

        verifyNoInteractions(reconciliationService);

        assertEquals(0, candidate.getAttemptCount());
        assertEquals(WebhookEventStatus.RECEIVED, candidate.getStatus());
    }

    @Test
    void processPendingEvents_shouldContinueWhenNoEventsAreEligible() {

        when(webhookEventRepository.findEligibleEvents())
                .thenReturn(List.of());

        webhookEventProcessor.processPendingEvents();

        verify(webhookEventRepository).findEligibleEvents();

        verify(webhookEventRepository, never())
                .claimForProcessing(anyString());

        verifyNoInteractions(reconciliationService);
    }
}
