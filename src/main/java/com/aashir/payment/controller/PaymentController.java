package com.aashir.payment.controller;

import com.aashir.payment.entity.PaymentMethod;
import com.aashir.payment.event.CheckoutResponse;
import com.aashir.payment.security.AuthenticatedUser;
import com.aashir.payment.security.JwtService;
import com.aashir.payment.service.CheckoutService;
import com.aashir.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final CheckoutService checkoutService;
    private final PaymentService paymentService;
    private final JwtService jwtService;
/*
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
    }*/

    @GetMapping("/me")
    public ResponseEntity<Long> getCurrentUser(
            HttpServletRequest request
    ) {
        String header = request.getHeader("Authorization");

        String token = header.substring(7);

        Long userId = jwtService.extractUserId(token);

        return ResponseEntity.ok(userId);
    }

    @GetMapping("/mail")
    public ResponseEntity<?> getCurrentUserEmail(HttpServletRequest request) {

        String token = request
                .getHeader("Authorization")
                .substring(7);

        Long userId = jwtService.extractUserId(token);
        String email = jwtService.extractEmail(token);

        return ResponseEntity.ok(
                Map.of(
                        "userId", userId,
                        "email", email
                )
        );
    }
    @GetMapping("/security-test")
    public ResponseEntity<?> securityTest(Authentication authentication) {

        AuthenticatedUser user =
                (AuthenticatedUser) authentication.getPrincipal();

        return ResponseEntity.ok(
                Map.of(
                        "userId", user.getUserId(),
                        "role", user.getRole()
                )
        );
    }

    @PostMapping("/{paymentId}/checkout")
    public ResponseEntity<CheckoutResponse> createCheckout(
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam PaymentMethod method
    ) {
        CheckoutResponse response = checkoutService.createCheckout(
                paymentId, idempotencyKey,method);

        return ResponseEntity.ok(response);
    }
 /*
    public ResponseEntity<PaymentSuccessResponse> getPaymentRequest(PaymentRequest paymentRequest,HttpServletRequest request) {

        PaymentSuccessResponse response = new PaymentSuccessResponse();
        return ResponseEntity.ok();
    }*/
}
