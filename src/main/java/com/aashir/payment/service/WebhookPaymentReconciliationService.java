
package com.aashir.payment.service;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentMethod;
import com.aashir.payment.exception.CheckoutGatewayException;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.razorpay.RazorpayException;

@Service
@RequiredArgsConstructor
public class WebhookPaymentReconciliationService {

    private static final String PAYMENT_CAPTURED = "payment.captured";
    private static final String PAYMENT_FAILED = "payment.failed";

    private final RazorpayGateway razorpayGateway;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public void reconcile(String eventType, String rawPayload) {
        if (!PAYMENT_CAPTURED.equals(eventType)
                && !PAYMENT_FAILED.equals(eventType)) {
            return;
        }

        JSONObject webhook = new JSONObject(rawPayload);

        String payloadEventType = webhook.optString("event", null);
        if (!eventType.equals(payloadEventType)) {
            throw new IllegalArgumentException(
                    "Webhook event type does not match the stored event type"
            );
        }

        JSONObject payload = webhook.optJSONObject("payload");
        JSONObject payment = payload == null ? null : payload.optJSONObject("payment");
        JSONObject paymentEntity = payment == null ? null : payment.optJSONObject("entity");

        if (paymentEntity == null) {
            throw new IllegalArgumentException(
                    "Payment entity is missing from the webhook payload"
            );
        }

        String webhookPaymentId = paymentEntity.optString("id", null);
        String webhookOrderId = paymentEntity.optString("order_id", null);

        requireText(webhookPaymentId, "Razorpay payment ID");
        requireText(webhookOrderId, "Razorpay order ID");

        JSONObject gatewayPayment = fetchGatewayPayment(webhookPaymentId);

        validateGatewayPayment(
                gatewayPayment,
                webhookPaymentId,
                webhookOrderId,
                eventType
        );

        if (PAYMENT_CAPTURED.equals(eventType)) {
            reconcileCapturedPayment(gatewayPayment);
        } else {
            reconcileFailedPayment(gatewayPayment);
        }
    }

    private JSONObject fetchGatewayPayment(String paymentId) {
        try {
            return razorpayGateway.fetchPayment(paymentId).toJson();
        } catch (RazorpayException exception) {
            throw new CheckoutGatewayException(
                    "Unable to fetch payment from Razorpay",
                    exception
            );
        }
    }

    private void validateGatewayPayment(
            JSONObject gatewayPayment,
            String webhookPaymentId,
            String webhookOrderId,
            String eventType
    ) {
        String gatewayPaymentId = gatewayPayment.optString("id", null);
        String gatewayOrderId = gatewayPayment.optString("order_id", null);
        String gatewayStatus = gatewayPayment.optString("status", null);

        if (!webhookPaymentId.equals(gatewayPaymentId)
                || !webhookOrderId.equals(gatewayOrderId)) {
            throw new IllegalStateException(
                    "Webhook payment details do not match Razorpay payment details"
            );
        }

        if (PAYMENT_CAPTURED.equals(eventType)
                && !"captured".equalsIgnoreCase(gatewayStatus)) {
            throw new IllegalStateException(
                    "Razorpay payment is not in captured status"
            );
        }
    }

    private void reconcileCapturedPayment(JSONObject gatewayPayment) {
        String gatewayPaymentId = gatewayPayment.getString("id");
        String gatewayOrderId = gatewayPayment.getString("order_id");

        long gatewayAmount = gatewayPayment.getLong("amount");
        String gatewayCurrency = gatewayPayment.getString("currency");
        PaymentMethod method = resolvePaymentMethod(
                gatewayPayment.optString("method", null)
        );

        transactionTemplate.executeWithoutResult(transactionStatus -> {
            PaymentAttempt attempt = findAttempt(gatewayOrderId);
            Payment payment = attempt.getPayment();

            validateLocalPayment(
                    payment,
                    gatewayAmount,
                    gatewayCurrency
            );

            attempt.reconcileSuccess(gatewayPaymentId, method);
            payment.reconcileSuccess(gatewayPaymentId);

            paymentAttemptRepository.save(attempt);

            // Keep the payment update and outbox event in one transaction.
            outboxService.savePaymentSucceeded(payment);
        });
    }

    private void reconcileFailedPayment(JSONObject gatewayPayment) {
        String gatewayOrderId = gatewayPayment.getString("order_id");

        String failureReason = gatewayPayment.optString(
                "error_description",
                "Payment failed at Razorpay"
        );

        transactionTemplate.executeWithoutResult(transactionStatus -> {
            PaymentAttempt attempt = findAttempt(gatewayOrderId);
            Payment payment = attempt.getPayment();

            validateLocalPayment(
                    payment,
                    gatewayPayment.getLong("amount"),
                    gatewayPayment.getString("currency")
            );

            String gatewayStatus = gatewayPayment.optString("status", null);
            if ("captured".equalsIgnoreCase(gatewayStatus)
                    && attempt.isSuccessful()
                    && gatewayPayment.getString("id").equals(attempt.getGatewayPaymentId())) {
                return;
            }
            if (!"failed".equalsIgnoreCase(gatewayStatus)) {
                throw new IllegalStateException("Razorpay payment is not in failed status");
            }
            if (attempt.isTerminal()) {
                return;
            }

            attempt.markFailed(failureReason);

            paymentAttemptRepository.save(attempt);
        });
    }

    private PaymentAttempt findAttempt(String gatewayOrderId) {
        return paymentAttemptRepository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment attempt found for Razorpay order: "
                                + gatewayOrderId
                ));
    }

    private void validateLocalPayment(
            Payment payment,
            long gatewayAmount,
            String gatewayCurrency
    ) {
        long expectedAmount = razorpayGateway.toPaise(payment.getAmount());

        if (expectedAmount != gatewayAmount) {
            throw new IllegalStateException(
                    "Razorpay payment amount does not match local payment amount"
            );
        }

        if (!payment.getCurrency().equalsIgnoreCase(gatewayCurrency)) {
            throw new IllegalStateException(
                    "Razorpay payment currency does not match local payment currency"
            );
        }
    }

    private PaymentMethod resolvePaymentMethod(String gatewayMethod) {
        if (gatewayMethod == null) {
            return null;
        }

        return switch (gatewayMethod.toLowerCase()) {
            case "card" -> PaymentMethod.CARD;
            case "upi" -> PaymentMethod.UPI;
            default -> null;
        };
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " is required"
            );
        }
    }
}
