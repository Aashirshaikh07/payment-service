package com.aashir.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "payment_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_attempt_number",
                        columnNames = {"payment_id", "attempt_number"}
                ),
                @UniqueConstraint(
                        name = "uk_payment_attempt_gateway_order",
                        columnNames = "gateway_order_id"
                ),
                @UniqueConstraint(
                        name = "uk_payment_attempt_gateway_payment",
                        columnNames = "gateway_payment_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, hidden from callers
public class PaymentAttempt {

    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    // ---------- Identity ----------
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: protects against webhook + client callback updating the same attempt
    @Version
    private Long version;

    // ---------- Relationships ----------
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    // ---------- Attempt data ----------
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PaymentMethod paymentMethod;

    // ---------- Gateway references ----------
    @Column(name = "gateway_order_id")
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id")
    private String gatewayPaymentId;

    @Column(length = MAX_FAILURE_REASON_LENGTH)
    private String failureReason;

    // ---------- Audit ----------
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // ---------- Factory ----------
    public static PaymentAttempt create(Payment payment, int attemptNumber, PaymentMethod method) {
        if (payment == null) {
            throw new IllegalArgumentException("Payment is required");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be positive");
        }

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.payment = payment;
        attempt.attemptNumber = attemptNumber;
        attempt.paymentMethod = method;
        attempt.status = PaymentAttemptStatus.CREATED;
        return attempt;
    }

    // ---------- State transitions ----------
    public void attachGatewayOrder(String gatewayOrderId) {
        requireText(gatewayOrderId, "Gateway order ID");

        if (gatewayOrderId.equals(this.gatewayOrderId)) {
            return;
        }

        if (status != PaymentAttemptStatus.CREATED) {
            throw new IllegalStateException(
                    "Gateway order can only be attached to a newly created attempt");
        }
        if (this.gatewayOrderId != null) {
            throw new IllegalStateException("A different gateway order is already attached");
        }
        this.gatewayOrderId = gatewayOrderId;
        this.status = PaymentAttemptStatus.CHECKOUT_CREATED;
    }

    public void markSuccess(String gatewayPaymentId, PaymentMethod method) {
        requireText(gatewayPaymentId, "Gateway payment ID");

        if (status == PaymentAttemptStatus.SUCCESS) {
            if (!gatewayPaymentId.equals(this.gatewayPaymentId)) {
                throw new IllegalStateException(
                        "Attempt already succeeded with a different gateway payment ID");
            }
            return; // genuine duplicate notification
        }
        if (status != PaymentAttemptStatus.CREATED
                && status != PaymentAttemptStatus.CHECKOUT_CREATED
                && status != PaymentAttemptStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot mark a " + status + " attempt as successful without reconciliation");
        }

        this.gatewayPaymentId = gatewayPaymentId;
        if (method != null) {
            this.paymentMethod = method; // don't wipe the method chosen at creation
        }
        this.status = PaymentAttemptStatus.SUCCESS;
        this.failureReason = null;
    }

    public void markPending() {
        if (status == PaymentAttemptStatus.PENDING) {
            return;
        }

        if (status != PaymentAttemptStatus.CHECKOUT_CREATED) {
            throw new IllegalStateException(
                    "Cannot mark a " + status + " attempt as pending"
            );
        }

        this.status = PaymentAttemptStatus.PENDING;
    }

    public void markFailed(String reason) {
        if (status == PaymentAttemptStatus.SUCCESS) {
            throw new IllegalStateException("A successful attempt cannot be marked failed");
        }
        if (status == PaymentAttemptStatus.FAILED
                || status == PaymentAttemptStatus.ABANDONED) {
            return; // already FAILED or ABANDONED
        }
        this.status = PaymentAttemptStatus.FAILED;
        this.failureReason = truncate(reason, MAX_FAILURE_REASON_LENGTH);
    }

    public void abandon() {
        if (status == PaymentAttemptStatus.ABANDONED) {
            return;
        }
        if (status == PaymentAttemptStatus.SUCCESS
                || status == PaymentAttemptStatus.FAILED) {
            throw new IllegalStateException("Cannot abandon an attempt in terminal state " + status);
        }
        this.status = PaymentAttemptStatus.ABANDONED;
    }


    // ---------- Queries ----------
    public boolean isSuccessful() {
        return status == PaymentAttemptStatus.SUCCESS;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    // ---------- Helpers ----------
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    // ---------- Equality (id-based, proxy-safe) ----------
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentAttempt other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}