package com.aashir.payment.controller;

import com.aashir.payment.gateway.RazorpayWebhookSignatureVerifier;
import com.aashir.payment.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RazorpayWebhookControllerTest {
    @Mock
    private RazorpayWebhookSignatureVerifier signatureVerifier;

    @Mock
    private WebhookEventRepository webhookEventRepository;

    private RazorpayWebhookController controller;

    private static final String SIGNATURE = "valid-signature";
    private static final String EVENT_ID = "evt_test_123";
    private static final String RAW_BODY = "{\"event\":\"payment.captured\"}";

    @BeforeEach
     void setup() {
        controller = new RazorpayWebhookController(
                signatureVerifier,
                webhookEventRepository,
                new ObjectMapper()
        );
    }

    @Test
    void shouldAcceptAndStoreValidWebhook(){
        when(signatureVerifier.verify(RAW_BODY,SIGNATURE)).thenReturn(true);

        when(webhookEventRepository.insertIfAbsent(
                eq(EVENT_ID),
                eq("payment.captured"),
                eq(RAW_BODY),
                any(Instant.class)
        )).thenReturn(1);

        ResponseEntity<Map<String,String>> response = controller.receiveWebhook(RAW_BODY,SIGNATURE,EVENT_ID);

        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals("accepted",response.getBody().get("status"));

        verify(webhookEventRepository).insertIfAbsent(
                eq(EVENT_ID),
                eq("payment.captured"),
                eq(RAW_BODY),
                any(Instant.class)
        );
    }


    @Test
    void shouldRejectWebhookWhenSignatureIsInvalid() {
        when(signatureVerifier.verify(RAW_BODY, SIGNATURE))
                .thenReturn(false);

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(RAW_BODY, SIGNATURE, EVENT_ID);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("invalid_signature", response.getBody().get("status"));

        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void shouldRejectWebhookWhenEventIdIsNull() {
        stubValidSignature();

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(RAW_BODY, SIGNATURE, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_event_id", response.getBody().get("status"));

        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void shouldRejectWebhookWhenEventIdIsBlank() {
        stubValidSignature();

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(RAW_BODY, SIGNATURE, " ");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_event_id", response.getBody().get("status"));

        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void shouldRejectWebhookWhenEventIdExceedsMaximumLength() {
        stubValidSignature();

        String longEventId = "e".repeat(101);

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(RAW_BODY, SIGNATURE, longEventId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_event_id", response.getBody().get("status"));

        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void shouldRejectWebhookWhenPayloadIsInvalidJson() {
        String invalidPayload = "{invalid-json";

        when(signatureVerifier.verify(invalidPayload, SIGNATURE))
                .thenReturn(true);

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(
                        invalidPayload,
                        SIGNATURE,
                        EVENT_ID
                );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_payload", response.getBody().get("status"));

        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void shouldRejectWebhookWhenEventTypeIsMissing() {
        String payloadWithoutEvent =
                "{\"payload\":{\"payment\":{}}}";

        when(signatureVerifier.verify(payloadWithoutEvent, SIGNATURE))
                .thenReturn(true);

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(
                        payloadWithoutEvent,
                        SIGNATURE,
                        EVENT_ID
                );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_event_type", response.getBody().get("status"));

        verifyNoInteractions(webhookEventRepository);
    }

    @Test
    void shouldReturnDuplicateWhenEventAlreadyExists() {
        stubValidSignature();

        when(webhookEventRepository.insertIfAbsent(
                eq(EVENT_ID),
                eq("payment.captured"),
                eq(RAW_BODY),
                any(Instant.class)
        )).thenReturn(0);

        ResponseEntity<Map<String, String>> response =
                controller.receiveWebhook(RAW_BODY, SIGNATURE, EVENT_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("duplicate", response.getBody().get("status"));
    }

    private void stubValidSignature() {
        when(signatureVerifier.verify(RAW_BODY, SIGNATURE))
                .thenReturn(true);
    }

}
