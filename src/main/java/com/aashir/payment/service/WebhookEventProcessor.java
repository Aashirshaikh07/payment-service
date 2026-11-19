
package com.aashir.payment.service;

import com.aashir.payment.entity.WebhookEvent;
import com.aashir.payment.entity.WebhookEventStatus;
import com.aashir.payment.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class WebhookEventProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(WebhookEventProcessor.class);

    private static final long PROCESSING_LEASE_TIMEOUT_MS = 600_000;
    private static final String STALE_PROCESSING_REASON =
            "Webhook processing lease expired; event recovered for retry";

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookPaymentReconciliationService reconciliationService;

    public WebhookEventProcessor(
            WebhookEventRepository webhookEventRepository,
            WebhookPaymentReconciliationService reconciliationService
    ) {
        this.webhookEventRepository = webhookEventRepository;
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${webhook.processing.scan-interval-ms:5000}")
    public void processPendingEvents() {

        int recovered = webhookEventRepository.recoverStaleProcessingEvents(
                Instant.now().minusMillis(PROCESSING_LEASE_TIMEOUT_MS),
                STALE_PROCESSING_REASON
        );

        if (recovered > 0) {
            log.warn("Recovered {} stale webhook events", recovered);
        }

        List<WebhookEvent> events =
                webhookEventRepository.findEligibleEvents();


        for (WebhookEvent candidate : events) {
            processEvent(candidate.getEventId());
        }
    }

    private void processEvent(String eventId) {

        int claimed = webhookEventRepository.claimForProcessing(eventId);

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
