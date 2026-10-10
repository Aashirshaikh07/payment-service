package com.aashir.payment.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class CheckoutRequestFingerprint {
    public String create(Long paymentId,Long userId){
        String request = paymentId + ":" + userId;

        try{
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(request.getBytes(StandardCharsets.UTF_8));

            StringBuilder fingerprint = new StringBuilder();
            for (byte value : hash) {
                fingerprint.append(String.format("%02x", value & 0xff));
            }

            return fingerprint.toString();
        } catch (NoSuchAlgorithmException exception){
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable", exception
            );
        }
    }
}
