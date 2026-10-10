package com.aashir.payment.repository;

import com.aashir.payment.entity.CheckoutRequest;
import com.aashir.payment.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CheckoutRequestRepository extends JpaRepository<CheckoutRequest, Long> {

    Optional<CheckoutRequest> findByUserIdAndIdempotencyKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
