package com.aashir.payment.repository;

import com.aashir.payment.entity.Payment;
import com.aashir.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
  Optional<Payment> findByIdempotencyKey(String idempotencyKey);
  Optional<Payment> findByOrderId(Long orderId);
  List<Payment> findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
          PaymentStatus status,
          Instant cutoff
  );
}
