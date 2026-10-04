package com.aashir.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "order_id")
    private String orderId;

    @Column(name = "processed_at",insertable = false,updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}
    public ProcessedEvent(String orderId) {
        this.orderId = orderId;
    }
}
