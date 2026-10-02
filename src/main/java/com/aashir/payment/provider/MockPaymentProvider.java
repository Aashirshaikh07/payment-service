package com.aashir.payment.provider;

import com.aashir.payment.entity.PaymentMethod;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockPaymentProvider implements PaymentProvider {

    private final Map<String, PaymentResult> processedPayments =
            new ConcurrentHashMap<>();

    @Override
    public PaymentResult processPayment(
            Long orderId,
            BigDecimal amount,
            PaymentMethod payment,
            String idempotencyKey
    ){
        PaymentResult existingResult =
                processedPayments.get(idempotencyKey);

        if(existingResult != null){
            System.out.println(
                    "Duplicate payment request detected:" +
                            idempotencyKey
            );
            return existingResult;
        }
        System.out.println("Processing Payment with provider: " + idempotencyKey);

        PaymentResult result = new PaymentResult(
                true,
                "MOCK-TXN-" + orderId
        );

        processedPayments.put(idempotencyKey,result);

        return result;
    }
}
