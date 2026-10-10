package com.aashir.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_order_id",
                        columnNames = "order_id"
                ),
                @UniqueConstraint(
                        name = "uk_payment_idempotency_key",
                        columnNames = "idempotency_key"
                ),
                @UniqueConstraint(
                        name = "uk_payment_transaction_id",
                        columnNames = "transaction_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    private static final String DEFAULT_CURRENCY = "INR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "order_id", nullable = false,updatable = false)
    private Long orderId;

    @Column(nullable = false,precision = 19,scale = 2,updatable = false)
    private BigDecimal amount;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,length = 30)
    private PaymentStatus status;

    @Column(name = "user_id", nullable = false,updatable = false)
    private Long userId;

    @Column(nullable = false, length = 3,updatable = false)
    private String currency;

    @Column(name = "transaction_id",unique = true)
    private String transactionId;

    @CreationTimestamp
    @Column(nullable = false,updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(
            mappedBy = "payment",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @OrderBy("attemptNumber ASC")
    private List<PaymentAttempt> attempts = new ArrayList<>();

    public static Payment create(
            Long orderId,
            Long userId,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    ) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Order ID is required");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        requireText(idempotencyKey, "Idempotency key");

        String resolvedCurrency = currency == null || currency.isBlank()
                ? DEFAULT_CURRENCY
                : currency.trim().toUpperCase();
        if (!DEFAULT_CURRENCY.equals(resolvedCurrency)) {
            throw new IllegalArgumentException("Only INR payments are currently supported");
        }

        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.userId = userId;
        payment.amount = amount;
        payment.currency = resolvedCurrency;
        payment.idempotencyKey = idempotencyKey;
        payment.status = PaymentStatus.PENDING;
        return payment;
    }

    public PaymentAttempt startAttempt(PaymentMethod method) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot start an attempt on a " + status + " payment");
        }
        boolean hasOpenAttempt = attempts.stream()
                .anyMatch(attempt -> !attempt.isTerminal());
        if (hasOpenAttempt) {
            throw new IllegalStateException("An attempt is already in progress");
        }

        int nextAttemptNumber = attempts.stream()
                .mapToInt(PaymentAttempt::getAttemptNumber)
                .max()
                .orElse(0) + 1;

        PaymentAttempt attempt =
                PaymentAttempt.create(this, nextAttemptNumber, method);

        attempts.add(attempt);
        return attempt;
    }

    public void markSuccess(String transactionId) {
        requireText(transactionId, "Transaction ID");

        if (status == PaymentStatus.SUCCESS) {
            if (!transactionId.equals(this.transactionId)) {
                throw new IllegalStateException(
                        "Payment already succeeded with a different transaction ID");
            }
            return;
        }
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot mark a " + status + " payment as successful");
        }

        this.transactionId = transactionId;
        this.status = PaymentStatus.SUCCESS;
    }

    public void markFailed() {
        if (status == PaymentStatus.SUCCESS) {
            throw new IllegalStateException("A successful payment cannot be marked failed");
        }
        if (status == PaymentStatus.FAILED) {
            return;
        }

        boolean hasOpenAttempt = attempts.stream()
                .anyMatch(attempt -> !attempt.isTerminal());

        if (hasOpenAttempt) {
            throw new IllegalStateException(
                    "Cannot fail payment while an attempt is unresolved"
            );
        }
        this.status = PaymentStatus.FAILED;
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.SUCCESS;
    }

    public List<PaymentAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
