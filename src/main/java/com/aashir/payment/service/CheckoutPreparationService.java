package com.aashir.payment.service;

import com.aashir.payment.entity.CheckoutRequest;
import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentMethod;
import com.aashir.payment.event.CheckoutRequestStatus;
import com.aashir.payment.exception.IdempotencyConflictException;
import com.aashir.payment.exception.PaymentNotFoundException;
import com.aashir.payment.repository.CheckoutRequestRepository;
import com.aashir.payment.repository.PaymentAttemptRepository;
import com.aashir.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckoutPreparationService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final CheckoutRequestRepository checkoutRequestRepository;
    private final CheckoutRequestFingerprint fingerprint;

    @Transactional
    public CheckoutPreparation prepare(Long paymentId, Long userId,
                                       String idempotencyKey, PaymentMethod method){
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));

        if (!payment.getUserId().equals(userId)) {
            throw new PaymentNotFoundException("Payment not found");
        }

        String requestFingerprint = fingerprint.create(paymentId, userId);

        var existingRequest = checkoutRequestRepository.findByUserIdAndIdempotencyKey(
                        userId, idempotencyKey);

        if (existingRequest.isPresent()) {
            CheckoutRequest existing = existingRequest.get();

            if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
                throw new IdempotencyConflictException(
                        "Idempotency key was already used for another request");
            }

            if (existing.getStatus() == CheckoutRequestStatus.COMPLETED) {
                return CheckoutPreparation.replay(
                        existing.getPayment().getId(),
                        existing.getPaymentAttempt().getId(),
                        existing.getId(),
                        existing.getRazorpayOrderId());
            }

            throw new IllegalStateException(
                    "Checkout request is already being processed or requires reconciliation");
        }

        if (method == null) {
            throw new IllegalArgumentException("Payment method is required");
        }

        PaymentAttempt attempt = payment.startAttempt(method);
        PaymentAttempt savedAttempt = paymentAttemptRepository.saveAndFlush(attempt);

        CheckoutRequest checkoutRequest = CheckoutRequest.create(
                payment,
                userId,
                idempotencyKey,
                requestFingerprint);

        checkoutRequest.attachAttempt(savedAttempt);

        CheckoutRequest savedRequest =
                checkoutRequestRepository.saveAndFlush(checkoutRequest);

        return CheckoutPreparation.newCheckout(
                payment.getId(),
                savedAttempt.getId(),
                savedRequest.getId(),
                payment.getAmount(),
                payment.getCurrency(),
                savedAttempt.getAttemptNumber());

    }
}
