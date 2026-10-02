package com.aashir.payment.kafka;

import com.aashir.payment.entity.Payment;
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
            groupId = "payment-service-group"
    )
    public void handleOrderCreated(OrderCreatedKafkaEvent event){
        Payment payment = new Payment();
        System.out.println("I am listening");

        payment.setOrderId(event.orderId());
        payment.setAmount(event.totalAmount());
        payment.setPaymentMethod(event.paymentMethod());

      Payment createdPayment = paymentService.createPayment(
                payment,
                "ORDER-" + event.orderId()
        );

        paymentService.processPayment(createdPayment.getId());
    }
}
