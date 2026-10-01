package com.aashir.payment.controller;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentStatus;
import com.aashir.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Payment createPayment(
            @RequestHeader("Idempotency-key") String idempotencyKey,
            @RequestBody Payment payment
    ) {
        return paymentService.createPayment(payment,idempotencyKey);
    }

    @PutMapping("/{paymentId}/status")
    public Payment updatePaymentStatus(
            @PathVariable Long paymentId,
            @RequestParam PaymentStatus status
    ){
        return paymentService.updatePayment(paymentId, status);
    }
}
