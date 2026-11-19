package com.aashir.payment.gateway;

import com.aashir.payment.config.RazorpayProperties;
import com.razorpay.RazorpayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RazorpayWebhookSignatureVerifierTest {

    @Mock
    private RazorpayProperties properties;

    private RazorpayWebhookSignatureVerifier signatureVerifier;

    private static final String WEBHOOK_SECRET = "test-webhook-secret";
    private static final String RAW_BODY =
            "{\"event\":\"payment.captured\"}";

    @BeforeEach
    void setUp() {
        signatureVerifier =
                new RazorpayWebhookSignatureVerifier(properties);

       // when(properties.webhookSecret()).thenReturn(WEBHOOK_SECRET);
    }

    @Test
    void shouldReturnTrueWhenSignatureIsValid() throws Exception {

        when(properties.webhookSecret()).thenReturn(WEBHOOK_SECRET);

        String signature = generateSignature(RAW_BODY);
        boolean result = signatureVerifier.verify(RAW_BODY,signature);

        assertTrue(result);

    }

    @Test
    void shouldReturnFalseWhenSignatureIsInvalid() {
        when(properties.webhookSecret()).thenReturn(WEBHOOK_SECRET);
        String invalidSignature = "a".repeat(64);

        boolean result =
                signatureVerifier.verify(RAW_BODY, invalidSignature);

        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenRawBodyIsModified() throws Exception {
        when(properties.webhookSecret()).thenReturn(WEBHOOK_SECRET);
        String signature = generateSignature(RAW_BODY);

        String modifiedBody =
                "{\"event\":\"payment.failed\"}";

        boolean result =
                signatureVerifier.verify(modifiedBody, signature);

        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenRawBodyIsNull() {
        boolean result =
                signatureVerifier.verify(null, generateDummySignature());

        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenSignatureIsNull() {
        boolean result = signatureVerifier.verify(RAW_BODY, null);

        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenSignatureIsBlank() {
        boolean result = signatureVerifier.verify(RAW_BODY, " ");

        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenSignatureContainsInvalidHexCharacters() {
        String malformedSignature = "z".repeat(64);

        boolean result =
                signatureVerifier.verify(RAW_BODY, malformedSignature);

        assertFalse(result);
    }

    @Test
    void shouldThrowExceptionWhenWebhookSecretIsMissing() {
        when(properties.webhookSecret()).thenReturn(" ");

        assertThrows(
                IllegalStateException.class,
                () -> signatureVerifier.verify(
                        RAW_BODY,
                        generateDummySignature()
                )
        );
    }

    private String generateSignature(String rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");

        SecretKeySpec secretKey = new SecretKeySpec(
                WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );

        mac.init(secretKey);

        byte[] hash = mac.doFinal(
                rawBody.getBytes(StandardCharsets.UTF_8)
        );

        return HexFormat.of().formatHex(hash);
    }

    private String generateDummySignature() {
        return "a".repeat(64);
    }
}
