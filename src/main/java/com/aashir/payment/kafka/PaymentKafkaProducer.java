package com.aashir.payment.kafka;

import com.aashir.payment.event.PaymentFailedEvent;
import com.aashir.payment.event.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentKafkaProducer {

    private static final String SUCCESS_TOPIC = "payment-succeeded";
    private static final String FAILURE_TOPIC = "payment-failed";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPaymentSucceeded(PaymentSucceededEvent event) {
        kafkaTemplate.send(SUCCESS_TOPIC, event.orderId().toString(), event);
    }

    public void publishPaymentFailed(PaymentFailedEvent event) {
        kafkaTemplate.send(
                FAILURE_TOPIC,
                event.orderId().toString(),
                event
        );
    }
}
