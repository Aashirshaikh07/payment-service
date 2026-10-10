package com.aashir.payment.entity;

import com.aashir.payment.event.CheckoutRequestStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "checkout_requests",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_checkout_user_idempotency_key",
                        columnNames = {"user_id", "idempotency_key"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_checkout_payment_id",
                        columnList = "payment_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckoutRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_id",
            nullable = false,
            updatable = false
    )
    private Payment payment;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckoutRequestStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_attempt_id")
    private PaymentAttempt paymentAttempt;

    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static CheckoutRequest create(
            Payment payment,
            Long userId,
            String idempotencyKey,
            String requestFingerprint
    ) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment is required");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Valid user ID is required");
        }
        if (payment.getUserId() == null
                || !payment.getUserId().equals(userId)) {
            throw new IllegalArgumentException(
                    "User does not own this payment"
            );
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "Idempotency key must contain 1 to 100 characters"
            );
        }
        if (requestFingerprint == null
                || !requestFingerprint.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Request fingerprint must be a SHA-256 hex digest"
            );
        }

        CheckoutRequest request = new CheckoutRequest();
        request.payment = payment;
        request.userId = userId;
        request.idempotencyKey = idempotencyKey;
        request.requestFingerprint = requestFingerprint;
        request.status = CheckoutRequestStatus.PROCESSING;

        return request;
    }

    public void attachAttempt(PaymentAttempt attempt) {
        if (attempt == null) {
            throw new IllegalArgumentException("Payment attempt is required");
        }
        if (!payment.getId().equals(attempt.getPayment().getId())) {
            throw new IllegalArgumentException(
                    "Payment attempt belongs to a different payment"
            );
        }
        if (paymentAttempt != null) {
            if (paymentAttempt.getId().equals(attempt.getId())) {
                return;
            }
            throw new IllegalStateException(
                    "A different payment attempt is already attached"
            );
        }
        if (status != CheckoutRequestStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Cannot attach an attempt to a " + status + " request"
            );
        }

        this.paymentAttempt = attempt;
    }

    public void complete(String razorpayOrderId) {
        if (razorpayOrderId == null || razorpayOrderId.isBlank()) {
            throw new IllegalArgumentException(
                    "Razorpay order ID is required"
            );
        }

        if (status == CheckoutRequestStatus.COMPLETED) {
            if (razorpayOrderId.equals(this.razorpayOrderId)) {
                return;
            }
            throw new IllegalStateException(
                    "Checkout request was completed with a different order"
            );
        }

        if (status != CheckoutRequestStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Cannot complete a " + status + " request"
            );
        }

        if (paymentAttempt == null) {
            throw new IllegalStateException(
                    "A payment attempt must be attached before completion"
            );
        }

        this.razorpayOrderId = razorpayOrderId;
        this.status = CheckoutRequestStatus.COMPLETED;
    }

    public void markFailed() {
        if (status == CheckoutRequestStatus.FAILED) {
            return;
        }
        if (status == CheckoutRequestStatus.COMPLETED) {
            throw new IllegalStateException(
                    "A completed checkout request cannot be marked failed"
            );
        }

        this.status = CheckoutRequestStatus.FAILED;
    }
}