
package com.aashir.payment.repository;

import com.aashir.payment.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    boolean existsByEventId(String eventId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT IGNORE INTO webhook_events
                (event_id, event_type, raw_payload, status,
                 received_at, attempt_count)
            VALUES
                (:eventId, :eventType, :rawPayload, 'RECEIVED',
                 :receivedAt, 0)
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("rawPayload") String rawPayload,
            @Param("receivedAt") Instant receivedAt
    );

    @Query(value = """
        SELECT *
        FROM webhook_events
        WHERE status = 'RECEIVED'
           OR (
                status = 'FAILED'
                AND next_retry_at <= UTC_TIMESTAMP(6)
           )
        ORDER BY received_at ASC
        LIMIT 50
        """, nativeQuery = true)
    List<WebhookEvent> findEligibleEvents();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE webhook_events
        SET status = 'PROCESSING',
            processing_at = UTC_TIMESTAMP(6),
            attempt_count = attempt_count + 1,
            next_retry_at = NULL,
            last_error = NULL
        WHERE event_id = :eventId
          AND attempt_count < 6
          AND (
                status = 'RECEIVED'
                OR (
                    status = 'FAILED'
                    AND next_retry_at <= UTC_TIMESTAMP(6)
                )
          )
        """, nativeQuery = true)
    int claimForProcessing(@Param("eventId") String eventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE webhook_events
            SET status = CASE
                    WHEN attempt_count >= 6 THEN 'DEAD'
                    ELSE 'FAILED'
                END,
                processing_at = NULL,
                next_retry_at = CASE
                    WHEN attempt_count >= 6 THEN NULL
                    ELSE TIMESTAMPADD(
                        SECOND,
                        CASE attempt_count
                            WHEN 1 THEN 5
                            WHEN 2 THEN 15
                            WHEN 3 THEN 45
                            WHEN 4 THEN 120
                            WHEN 5 THEN 300
                        END,
                        UTC_TIMESTAMP(6)
                    )
                END,
                last_error = :reason
            WHERE status = 'PROCESSING'
              AND processing_at IS NOT NULL
              AND processing_at < :staleBefore
            """, nativeQuery = true)
    int recoverStaleProcessingEvents(
            @Param("staleBefore") Instant staleBefore,
            @Param("reason") String reason
    );
}
