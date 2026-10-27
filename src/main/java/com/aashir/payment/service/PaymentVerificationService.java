package com.aashir.payment.service;

import com.aashir.payment.dto.PaymentVerificationResponse;
import com.aashir.payment.dto.VerifyPaymentRequest;
import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.entity.PaymentAttemptStatus;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.exception.CheckoutGatewayException;
import com.aashir.payment.gateway.RazorpayGateway;
import com.aashir.payment.repository.PaymentAttemptRepository;
import com.aashir.payment.repository.PaymentRepository;
import com.aashir.payment.security.AuthenticatedUser;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentVerificationService {
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RazorpayGateway razorpayGateway;
    private final OutboxService outboxService;

    @Transactional
    public PaymentVerificationResponse verify(VerifyPaymentRequest request){
        Long userId = getAuthenticatedUserId();
        Payment payment = paymentRepository.findById(request.paymentId())
                .orElseThrow(()->new IllegalArgumentException("Payment not found"));

        if(!Objects.equals(payment.getUserId(),userId)) {
            throw new IllegalArgumentException("Payment not found");
        }

        PaymentAttempt attempt = paymentAttemptRepository
                .findByGatewayOrderId(request.razorpayOrderId())
                .orElseThrow(()->new IllegalArgumentException("Payment attempt not found"));

        if(!Objects.equals(attempt.getPayment().getId(),payment.getId())) {
            throw new IllegalArgumentException("Payment attempt does not mathc payment");
        }

        if(attempt.getStatus() == PaymentAttemptStatus.SUCCESS) {
            if(payment.getStatus() ==  PaymentStatus.SUCCESS
            && Objects.equals(
                    attempt.getGatewayPaymentId(),
                    request.razorpayPaymentId()
            )
            && Objects.equals(
                    payment.getTransactionId(),
                    request.razorpayPaymentId()
            )) {
                return new PaymentVerificationResponse(
                        payment.getId(),payment.getOrderId(),
                        payment.getStatus().name(),"Payment already verified"
                  );
                }
            throw new IllegalStateException("Payment attempt already succeeded");
        }
        if (!razorpayGateway.verifyPaymentSignature(
                request.razorpayOrderId(),
                request.razorpayPaymentId(),
                request.razorpaySignature())) {
            throw new IllegalArgumentException("Invalid Razorpay payment signature");
        }

        try{
            com.razorpay.Payment getewayPayment =
                    razorpayGateway.fetchPayment(request.razorpayPaymentId());

            JSONObject details = getewayPayment.toJson();

            String gatewayOrderId = details.optString("order_id", "");
            String gatewayStatus = details.optString("status", "");
            String gatewayCurrency = details.optString("currency", "");
            long gatewayAmount = details.optLong("amount", -1);

            long expectedAmount = razorpayGateway.toPaise(payment.getAmount());

            if (!request.razorpayOrderId().equals(gatewayOrderId)
                    || expectedAmount != gatewayAmount
                    || !payment.getCurrency().equalsIgnoreCase(gatewayCurrency)) {
                throw new IllegalArgumentException(
                        "Gateway payment details do not match the stored payment"
                );
            }

            if("captured".equalsIgnoreCase(gatewayStatus)){
                attempt.markSuccess(
                        request.razorpayPaymentId(),attempt.getPaymentMethod()
                );
                payment.markSuccess(request.razorpayPaymentId());

                paymentAttemptRepository.save(attempt);
                paymentRepository.save(payment);
                outboxService.savePaymentSucceeded(payment);
                return new PaymentVerificationResponse(
                        payment.getId(), payment.getOrderId(),
                        PaymentStatus.SUCCESS.name(), "Payment verified successfully"
                );
            }

            if("authorized".equalsIgnoreCase(gatewayStatus)
             || "created".equalsIgnoreCase(gatewayStatus)){
                attempt.markPending();

                return new PaymentVerificationResponse(
                        payment.getId(),payment.getOrderId(),
                        PaymentStatus.PENDING.name(),
                        "Payment verified pending awaiting confirmation"
                );
            }

            if("failed".equalsIgnoreCase(gatewayStatus)){
                attempt.markFailed("Razorpay payment failed");
                paymentRepository.save(payment);
                paymentAttemptRepository.save(attempt);

                return new PaymentVerificationResponse(
                        payment.getId(), payment.getOrderId(),
                        PaymentStatus.PENDING.name(),
                        "Payment failed; payment state requires retry or reconciliation"
                );
            }
            return new PaymentVerificationResponse(
                    payment.getId(),payment.getOrderId(),
                    PaymentStatus.PENDING.name(),"Payment status is awaiting reconciliation"
            );
        }catch (RazorpayException exception){
            throw new CheckoutGatewayException(
                    "Unable to retrieve payment details from Razorpay", exception
            );
        }
    }

    private Long getAuthenticatedUserId(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof AuthenticatedUser user)){
            throw new AuthenticationServiceException("An authenticated customer is required");
        }
        return user.getUserId();
    }
}
