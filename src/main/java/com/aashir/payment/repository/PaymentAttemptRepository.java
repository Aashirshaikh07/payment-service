package com.aashir.payment.repository;

import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    // ---------- Lookups by payment ----------
    List<PaymentAttempt> findByPaymentIdOrderByAttemptNumberAsc(Long paymentId);

    Optional<PaymentAttempt> findByPaymentIdAndAttemptNumber(Long paymentId, Integer attemptNumber);

    // Latest attempt: use its attemptNumber + 1 when creating a retry
    Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNumberDesc(Long paymentId);

    boolean existsByPaymentIdAndAttemptNumber(Long paymentId, Integer attemptNumber);

    // ---------- Lookups by gateway reference (webhooks / callbacks) ----------
    Optional<PaymentAttempt> findByGatewayOrderId(String gatewayOrderId);

    Optional<PaymentAttempt> findByGatewayPaymentId(String gatewayPaymentId);

    // ---------- Cleanup jobs ----------
    // Stale CREATED attempts that can be passed to attempt.abandon()
    List<PaymentAttempt> findByStatusAndCreatedAtBefore(PaymentAttemptStatus status, Instant cutoff);
}