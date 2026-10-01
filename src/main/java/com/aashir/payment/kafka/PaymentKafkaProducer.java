package com.aashir.payment.kafka;

import com.aashir.payment.event.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentKafkaProducer {

    private static final String TOPIC = "payment-events";
    private final KafkaTemplate<String, PaymentSucceededEvent> kafkaTemplate;

    public void publishPaymentSucceeded(PaymentSucceededEvent event) {
        kafkaTemplate.send(TOPIC, event.paymentId().toString(), event);
    }
}
