package com.aashir.payment.provider;

import com.aashir.payment.entity.PaymentMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MockPaymentProvider implements PaymentProvider {

    private final Map<String, PaymentResult> processedPayments =
            new ConcurrentHashMap<>();

    @Override
    public PaymentResult processPayment(
            Long orderId,
            BigDecimal amount,
            PaymentMethod paymentMethod,
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
        PaymentResult result;

        if(paymentMethod == PaymentMethod.CARD && amount.compareTo(BigDecimal.valueOf(10000)) < 0){
            result = new PaymentResult(
                    PaymentResultStatus.PENDING,
                    "MOCK-TXN-" + orderId,
                    "CARD_DECLINED"
            );
        } else if (paymentMethod == PaymentMethod.CARD && amount.compareTo(BigDecimal.valueOf(10000))>0) {
            result = new PaymentResult(
                    PaymentResultStatus.FAILED,
                    "MOCK-TXN-" + orderId,
                    "CARD_DECLINED"
            );

        } else {
            result = new PaymentResult(
                    PaymentResultStatus.SUCCESS,
                    "MOCK-TXN-" + orderId,
                    null
            );
        }

        processedPayments.put(idempotencyKey,result);

        return result;
    }
}
