
package com.aashir.payment.service;

import com.aashir.payment.entity.WebhookEvent;
import com.aashir.payment.entity.WebhookEventStatus;
import com.aashir.payment.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebhookEventProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(WebhookEventProcessor.class);

    private static final List<WebhookEventStatus> CLAIMABLE_STATUSES =
            List.of(
                    WebhookEventStatus.RECEIVED,
                    WebhookEventStatus.FAILED
            );

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookPaymentReconciliationService reconciliationService;

    public WebhookEventProcessor(
            WebhookEventRepository webhookEventRepository,
            WebhookPaymentReconciliationService reconciliationService
    ) {
        this.webhookEventRepository = webhookEventRepository;
        this.reconciliationService = reconciliationService;
    }

    public void processPendingEvents() {

        List<WebhookEvent> events =
                webhookEventRepository.findTop50ByStatusInOrderByReceivedAtAsc(
                        CLAIMABLE_STATUSES
                );

        for (WebhookEvent candidate : events) {
            processEvent(candidate.getEventId());
        }
    }

    private void processEvent(String eventId) {

        int claimed = webhookEventRepository.claimForProcessing(
                eventId,
                WebhookEventStatus.PROCESSING,
                CLAIMABLE_STATUSES
        );

        if (claimed == 0) {
            return;
        }

        try {
            WebhookEvent event = webhookEventRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Claimed webhook event not found: " + eventId
                    ));

            reconciliationService.reconcile(
                    event.getEventType(),
                    event.getRawPayload()
            );

            event.markProcessed();
            webhookEventRepository.save(event);

            log.info("Webhook event processed successfully: {}", eventId);

        } catch (Exception exception) {
            markFailed(eventId, exception);
        }
    }

    private void markFailed(String eventId, Exception exception) {

        try {
            WebhookEvent event = webhookEventRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook event not found: " + eventId
                    ));

            event.markFailed(exception.getMessage());
            webhookEventRepository.save(event);

            log.error(
                    "Webhook event processing failed: {}",
                    eventId,
                    exception
            );

        } catch (Exception failureException) {
            log.error(
                    "Unable to mark webhook event as FAILED: {}",
                    eventId,
                    failureException
            );
        }
    }
}
