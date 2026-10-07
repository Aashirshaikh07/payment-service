package com.aashir.payment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {

    @PostMapping("/process")
    public ResponseEntity<String> processPayments(){
        return ResponseEntity.ok("Payment service accepted the internal request");

    }
}
