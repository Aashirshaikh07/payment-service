package com.aashir.payment.controller;

import com.aashir.payment.gateway.RazorpayWebhookSignatureVerifier;
import com.aashir.payment.repository.WebhookEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class RazorpayWebhookController {

    private static final int MAX_EVENT_ID_LENGTH = 100;
    private static final int MAX_EVENT_TYPE_LENGTH = 100;

    private final RazorpayWebhookSignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookController(RazorpayWebhookSignatureVerifier signatureVerifier, WebhookEventRepository webhookEventRepository, ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Map<String,String>> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false)  String signature,
            @RequestHeader(value = "x-razorpay-event-id",required = false) String eventId
    ) {
        // 1. Verify signature against the exact raw request body.
        if (!signatureVerifier.verify(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "invalid_signature"));
        }

        // 2. Validate the delivery ID.
        if (eventId == null || eventId.isBlank()
                || eventId.length() > MAX_EVENT_ID_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "invalid_event_id"));
        }

        // 3. Parse the event type only after signature verification.
        final String eventType;

        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            eventType = payload.path("event").asText(null);
        } catch (JacksonException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "invalid_payload"));
        }

        if (eventType == null || eventType.isBlank()
                || eventType.length() > MAX_EVENT_TYPE_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "invalid_event_type"));
        }

        // 4. Atomically store the event, ignoring duplicate event IDs.
        int inserted = webhookEventRepository.insertIfAbsent(
                eventId,
                eventType,
                rawBody,
                Instant.now()
        );

        if (inserted == 0) {
            return ResponseEntity.ok(
                    Map.of("status", "duplicate")
            );
        }

        return ResponseEntity.ok(
                Map.of("status", "accepted")
        );
    }
}
