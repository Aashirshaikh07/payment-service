package com.aashir.payment.service;

import com.aashir.payment.entity.CheckoutRequest;
import com.aashir.payment.entity.PaymentAttempt;
import com.aashir.payment.repository.CheckoutRequestRepository;
import com.aashir.payment.repository.PaymentAttemptRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckoutCompletionService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final CheckoutRequestRepository  checkoutRequestRepository;

    @Transactional
    public void complete(Long checkoutAttemptId,Long checkoutRequestId,String razorpayOrderId){

        PaymentAttempt paymentAttempt = paymentAttemptRepository.findById(checkoutAttemptId)
                .orElseThrow(()->
                        new EntityNotFoundException("PaymentAttempt request not found with id " + checkoutAttemptId));

        CheckoutRequest checkoutRequest = checkoutRequestRepository.findById(checkoutRequestId)
                .orElseThrow(()->
                        new EntityNotFoundException("CheckoutRequest request not found with id " + checkoutRequestId));

        paymentAttempt.attachGatewayOrder(razorpayOrderId);
        checkoutRequest.complete(razorpayOrderId);
    }
}
