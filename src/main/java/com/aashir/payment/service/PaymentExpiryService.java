package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.PaymentRepository;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpiryService {

    private final PaymentRepository paymentRepository;
    private final RazorpayGateway razorpayGateway;
    private final OutboxService outboxService;

    @Transactional
    public boolean expire(Long paymentId, long expiryMinutes) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);

        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
            return false;
        }

        if (mightHaveMoneyAtGateway(payment)) {
            return false;
        }

        payment.getAttempts().stream()
                .filter(attempt -> !attempt.isTerminal())
                .forEach(PaymentAttempt::abandon);

        payment.markFailed();

        outboxService.savePaymentFailed(
                payment,
                "Payment not completed within " + expiryMinutes + " minutes"
        );

        paymentRepository.save(payment);
        return true;
    }

    private boolean mightHaveMoneyAtGateway(Payment payment) {
        for (PaymentAttempt attempt : payment.getAttempts()) {
            String gatewayOrderId = attempt.getGatewayOrderId();

            if (gatewayOrderId == null) {
                continue;
            }

            try {
                for (com.razorpay.Payment gatewayPayment
                        : razorpayGateway.fetchOrderPayments(gatewayOrderId)) {
                    String status = gatewayPayment.toJson().optString("status", "");

                    if ("captured".equalsIgnoreCase(status)
                            || "authorized".equalsIgnoreCase(status)) {
                        log.error(
                                "Not failing paymentId={} because Razorpay shows a {} payment for order {}. Needs manual review.",
                                payment.getId(),
                                status,
                                gatewayOrderId
                        );
                        return true;
                    }
                }
            } catch (RazorpayException exception) {
                log.warn(
                        "Could not check Razorpay for paymentId={}. Will retry on the next run.",
                        payment.getId(),
                        exception
                );
                return true;
            }
        }

        return false;
    }
}
