
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
    private static final String RAW_PAYLOAD = "{\"payment\":{\"id\":\"pay_123\"}}";

    private WebhookEvent createProcessingEvent() {
        WebhookEvent event = WebhookEvent.receive(
                EVENT_ID,
                EVENT_TYPE,
                RAW_PAYLOAD
        );

        event.markProcessing();
        return event;
    }


    @Test
    void processPendingEvents_shouldRecoverStaleProcessingEvents() {

        when(webhookEventRepository.recoverStaleProcessingEvents(
                eq(WebhookEventStatus.PROCESSING),
                eq(WebhookEventStatus.FAILED),
                any(),
                anyString()
        )).thenReturn(2);

        when(webhookEventRepository.findTop50ByStatusInOrderByReceivedAtAsc(
                anyCollection()
        )).thenReturn(List.of());

        webhookEventProcessor.processPendingEvents();

        verify(webhookEventRepository).recoverStaleProcessingEvents(
                eq(WebhookEventStatus.PROCESSING),
                eq(WebhookEventStatus.FAILED),
                any(),
                anyString()
        );

        verify(webhookEventRepository).findTop50ByStatusInOrderByReceivedAtAsc(
                eq(List.of(
                        WebhookEventStatus.RECEIVED,
                        WebhookEventStatus.FAILED
                ))
        );

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void processPendingEvents_shouldMarkEventProcessedAfterSuccessfulReconciliation() {

        WebhookEvent event = createProcessingEvent();

        when(webhookEventRepository.findTop50ByStatusInOrderByReceivedAtAsc(
                anyCollection()
        )).thenReturn(List.of(event));

        when(webhookEventRepository.claimForProcessing(
                eq(EVENT_ID),
                eq(WebhookEventStatus.PROCESSING),
                anyCollection()
        )).thenReturn(1);

        when(webhookEventRepository.findById(EVENT_ID))
                .thenReturn(Optional.of(event));

        webhookEventProcessor.processPendingEvents();

        verify(reconciliationService).reconcile(
                EVENT_TYPE,
                RAW_PAYLOAD
        );

        verify(webhookEventRepository).save(event);

        assertEquals(WebhookEventStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getProcessingAt());
        assertNull(event.getLastError());
    }

    @Test
    void processPendingEvents_shouldMarkEventFailedWhenReconciliationFails() {

        WebhookEvent event = createProcessingEvent();

        when(webhookEventRepository.findTop50ByStatusInOrderByReceivedAtAsc(
                anyCollection()
        )).thenReturn(List.of(event));

        when(webhookEventRepository.claimForProcessing(
                eq(EVENT_ID),
                eq(WebhookEventStatus.PROCESSING),
                anyCollection()
        )).thenReturn(1);

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
        assertNull(event.getProcessingAt());
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

        when(webhookEventRepository.findTop50ByStatusInOrderByReceivedAtAsc(
                anyCollection()
        )).thenReturn(List.of(candidate));

        when(webhookEventRepository.claimForProcessing(
                eq(EVENT_ID),
                eq(WebhookEventStatus.PROCESSING),
                anyCollection()
        )).thenReturn(0);

        webhookEventProcessor.processPendingEvents();

        verify(webhookEventRepository).claimForProcessing(
                eq(EVENT_ID),
                eq(WebhookEventStatus.PROCESSING),
                anyCollection()
        );

        verify(webhookEventRepository, never()).findById(anyString());
        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(reconciliationService);
    }
}
