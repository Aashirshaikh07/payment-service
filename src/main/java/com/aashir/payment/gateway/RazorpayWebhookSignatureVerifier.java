
package com.aashir.payment.gateway;

import com.aashir.payment.config.RazorpayProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class RazorpayWebhookSignatureVerifier {

    private final RazorpayProperties properties;

    public RazorpayWebhookSignatureVerifier(RazorpayProperties properties) {
        this.properties = properties;
    }

    public boolean verify(String rawBody, String signature) {

        if (rawBody == null || signature == null
                || !signature.matches("[0-9a-fA-F]{64}")) {
            return false;
        }

        String webhookSecret = properties.webhookSecret();

        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "Razorpay webhook secret is not configured"
            );
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            SecretKeySpec secretKey = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );

            mac.init(secretKey);

            byte[] expectedSignature = mac.doFinal(
                    rawBody.getBytes(StandardCharsets.UTF_8)
            );

            byte[] actualSignature = HexFormat.of().parseHex(signature);

            return MessageDigest.isEqual(
                    expectedSignature,
                    actualSignature
            );

        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to verify Razorpay webhook signature",
                    exception
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
