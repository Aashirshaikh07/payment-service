package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryJob {

    private final PaymentRepository paymentRepository;
    private final PaymentExpiryService paymentExpiryService;

    @Value("${payment.expiry.minutes:30}")
    private long expiryMinutes;

    @Scheduled(fixedDelayString = "${payment.expiry.scan-interval-ms:60000}")
    public void expireStalePayments() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(expiryMinutes));

        List<Payment> stalePayments =
                paymentRepository.findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        PaymentStatus.PENDING,
                        cutoff
                );

        for (Payment payment : stalePayments) {
            try {
                boolean expired = paymentExpiryService.expire(payment.getId(), expiryMinutes);

                if (expired) {
                    log.info("Expired paymentId={}", payment.getId());
                }
            } catch (Exception exception) {
                log.error("Failed to expire paymentId={}", payment.getId(), exception);
            }
        }
    }
}