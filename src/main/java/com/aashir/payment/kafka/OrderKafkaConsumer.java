package com.aashir.payment.kafka;

import com.aashir.payment.event.OrderCreatedKafkaEvent;
import com.aashir.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderKafkaConsumer {

    private final PaymentService paymentService;

    @KafkaListener(
            topics = "order-events",
            groupId = "payment-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderCreated(OrderCreatedKafkaEvent event){
        paymentService.handleOrderCreated(event);
    }
}
