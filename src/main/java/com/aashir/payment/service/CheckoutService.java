package com.aashir.payment.service;

import com.aashir.payment.entity.PaymentMethod;
import com.aashir.payment.event.CheckoutResponse;
import com.aashir.payment.exception.CheckoutGatewayException;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.security.AuthenticatedUser;
import com.razorpay.Order;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CheckoutPreparationService preparationService;
    private final CheckoutCompletionService completionService;
    private final RazorpayGateway razorpayGateway;

    public CheckoutResponse createCheckout(Long paymentId, String idempotencyKey, PaymentMethod method){
        validateIdempotencyKey(idempotencyKey);

        Long userId = getAuthenticatedUserId();

        CheckoutPreparation preparation = preparationService.prepare(
                paymentId,
                userId,
                idempotencyKey,
                method
        );

        if (preparation.replay()) {
            return new CheckoutResponse(
                    preparation.paymentId(),
                    preparation.paymentAttemptId(),
                    preparation.razorpayOrderId(),
                    razorpayGateway.getKeyId(),
                    null,
                    null,
                    "CHECKOUT_CREATED"
            );
        }

        try{
            long amountInPaise = razorpayGateway.toPaise(preparation.amount());

            Order order = razorpayGateway.createOrder(
                    amountInPaise,
                    preparation.currency(),
                    "payment-" + preparation.paymentId()
                    + "-attempt-" + preparation.attemptNumber()
            );

            String razorpayOrderId = order.get("id");

            if(razorpayOrderId == null || razorpayOrderId.isBlank()){
                throw new IllegalStateException(
                        "Razorpay returned an invalid order ID"
                );
            }

            completionService.complete(
                    preparation.paymentAttemptId(),
                    preparation.checkoutRequestId(),
                    razorpayOrderId
            );
            return new CheckoutResponse(
                    preparation.paymentId(),
                    preparation.paymentAttemptId(),
                    razorpayOrderId,
                    razorpayGateway.getKeyId(),
                    preparation.amount(),
                    preparation.currency(),
                    "CHECKOUT_CREATED"
            );

        }catch (RazorpayException exception){
            log.error("Razorpay order creation failed", exception);

            // We must not automatically mark the attempt FAILED here:
            // a network error may occur after Razorpay creates the order.
            throw new CheckoutGatewayException(
                    "Unable to confirm Razorpay order creation. "
                            + "The checkout may require reconciliation.",
                    exception
            );
        }
    }

    private Long getAuthenticatedUserId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                instanceof AuthenticatedUser user)) {
            throw new IllegalStateException(
                    "An authenticated customer is required");
        }

        return user.getUserId();
    }
    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException(
                    "Idempotency key must contain 1 to 100 characters");
        }
    }
}
