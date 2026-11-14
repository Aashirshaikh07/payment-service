package com.aashir.payment.repository;

import com.aashir.payment.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    boolean existsByEventId(String eventId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT IGNORE INTO webhook_events
                (event_id, event_type, raw_payload, status, received_at)
            VALUES
                (:eventId, :eventType, :rawPayload, 'RECEIVED', :receivedAt)
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("rawPayload") String rawPayload,
            @Param("receivedAt") Instant receivedAt
    );
}
