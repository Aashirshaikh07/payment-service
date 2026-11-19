package com.aashir.payment.repository;

import com.aashir.payment.entity.WebhookEvent;
import com.aashir.payment.entity.WebhookEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
public class WebhookEventRepositoryIntegrationTest {
    private static final String EVENT_ID = "evt_test_123";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Container
    static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>(
            DockerImageName.parse("mysql:8")
    )
            .withDatabaseName("payment_test_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL_CONTAINER::getDriverClassName);

    }
    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Test
    void shouldInsertNewWebhookEvent() {
        String payload = """
                {"id":"evt_test_123","type":"payment.captured"}
                """;
        Instant receivedAt = Instant.parse("2026-11-12T10:00:00Z");

        int insertedRows = webhookEventRepository.insertIfAbsent(
                EVENT_ID,
                "payment.captured",
                payload,
                receivedAt
        );

        assertEquals(1,insertedRows);

        WebhookEvent savedEvent = webhookEventRepository.findById(EVENT_ID)
                .orElseThrow();
        assertEquals(EVENT_ID, savedEvent.getEventId());
        assertEquals("payment.captured", savedEvent.getEventType());
        assertEquals(payload, savedEvent.getRawPayload());
        assertEquals(WebhookEventStatus.RECEIVED, savedEvent.getStatus());
        assertEquals(receivedAt, savedEvent.getReceivedAt());

        assertEquals(0, savedEvent.getAttemptCount());
        assertNull(savedEvent.getNextRetryAt());

        assertNull(savedEvent.getProcessedAt());
        assertNull(savedEvent.getLastError());

    }

    @Test
    void shouldIgnoreDuplicateWebhookEventId() {

        Instant receivedAt = Instant.parse("2026-11-12T10:00:00Z");

        int firstInsert =  webhookEventRepository.insertIfAbsent(
                EVENT_ID,
                "payment.captured",
                "{\"id\":\"evt_test_123\"}",
                receivedAt
        );

        int duplicateInsert = webhookEventRepository.insertIfAbsent(
                EVENT_ID,
                "payment.captured",
                "{\"id\":\"evt_test_123\"}",
                receivedAt
        );

        assertEquals(1,firstInsert);
        assertEquals(0,duplicateInsert);

        assertEquals(1,webhookEventRepository.count());
    }

    @Test
    void shouldPreserveOriginalEventWhenDuplicateArrivesWithDifferentPayload() {
        Instant firstReceivedAt  = Instant.parse("2026-11-12T10:00:00Z");

        String originalPayload = """
                {"id":"evt_test_123","amount":500}
                """;

        String duplicatePayload = """
                {"id":"evt_test_123","amount":999}
                """;

        webhookEventRepository.insertIfAbsent(
                EVENT_ID,
                "payment.captured",
                originalPayload,
                firstReceivedAt
        );

        int duplicateInsert = webhookEventRepository.insertIfAbsent(
                EVENT_ID,
                "payment.failed",
                duplicatePayload,
                Instant.parse("2026-11-12T10:05:00Z")
        );

        assertEquals(0, duplicateInsert);

        WebhookEvent savedEvent = webhookEventRepository
                .findById(EVENT_ID)
                .orElseThrow();

        assertEquals("payment.captured", savedEvent.getEventType());
        assertEquals(originalPayload, savedEvent.getRawPayload());
        assertEquals(firstReceivedAt, savedEvent.getReceivedAt());
        assertEquals(WebhookEventStatus.RECEIVED, savedEvent.getStatus());
    }

    //Retry testing

    @Test
    void shouldIncrementAttemptCountWhenEventIsClaimed() {
        String eventId = "evt_claim_001";
        insertTestEvent(eventId);

        int firstClaim = webhookEventRepository.claimForProcessing(eventId);
        int secondClaim = webhookEventRepository.claimForProcessing(eventId);

        assertEquals(1, firstClaim);
        assertEquals(0, secondClaim);

        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow();

        assertEquals(WebhookEventStatus.PROCESSING, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getNextRetryAt());
    }


    @Test
    void shouldNotClaimFailedEventBeforeRetryTime() {
        String eventId = "evt_retry_future_001";
        insertTestEvent(eventId);

        assertEquals(1, webhookEventRepository.claimForProcessing(eventId));

        Instant futureRetryTime = Instant.now().plusSeconds(60);

        jdbcTemplate.update("""
            UPDATE webhook_events
            SET status = 'FAILED',
                processing_at = NULL,
                next_retry_at = ?
            WHERE event_id = ?
            """,
                Timestamp.from(futureRetryTime),
                eventId
        );

        assertFalse(
                webhookEventRepository.findEligibleEvents()
                        .stream()
                        .anyMatch(event -> event.getEventId().equals(eventId))
        );

        assertEquals(0, webhookEventRepository.claimForProcessing(eventId));

        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow();

        assertEquals(WebhookEventStatus.FAILED, event.getStatus());
        assertEquals(1, event.getAttemptCount());
    }

    @Test
    void shouldRecoverStaleProcessingEventWithoutIncrementingAttemptCount() {
        String eventId = "evt_stale_001";
        insertTestEvent(eventId);

        assertEquals(1, webhookEventRepository.claimForProcessing(eventId));

        Instant oldProcessingTime = Instant.now().minusSeconds(600);

        jdbcTemplate.update("""
        UPDATE webhook_events
        SET processing_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)
        WHERE event_id = ?
        """,
                eventId
        );

        Instant beforeRecovery = Instant.now();

        int recoveredRows = webhookEventRepository.recoverStaleProcessingEvents(
                Instant.now().minusSeconds(300),
                "Processing lease expired"
        );

        Instant afterRecovery = Instant.now();

        assertEquals(1, recoveredRows);

        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow();

        assertEquals(WebhookEventStatus.FAILED, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getProcessingAt());
        assertEquals("Processing lease expired", event.getLastError());

        assertNotNull(event.getNextRetryAt());

        assertTrue(event.getNextRetryAt().isAfter(
                beforeRecovery.plusSeconds(3)
        ));

        assertTrue(event.getNextRetryAt().isBefore(
                afterRecovery.plusSeconds(7)
        ));
    }

    @Test
    void shouldMarkStaleEventDeadWhenMaximumAttemptsReached() {
        String eventId = "evt_dead_001";
        insertTestEvent(eventId);

        assertEquals(1, webhookEventRepository.claimForProcessing(eventId));

        jdbcTemplate.update("""
        UPDATE webhook_events
        SET attempt_count = 6,
            processing_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)
        WHERE event_id = ?
        """,
                eventId
        );

        int recoveredRows = webhookEventRepository.recoverStaleProcessingEvents(
                Instant.now().minusSeconds(300),
                "Processing lease expired"
        );

        assertEquals(1, recoveredRows);

        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow();

        assertEquals(WebhookEventStatus.DEAD, event.getStatus());
        assertEquals(6, event.getAttemptCount());
        assertNull(event.getProcessingAt());
        assertNull(event.getNextRetryAt());
        assertEquals("Processing lease expired", event.getLastError());
    }

    @Test
    void shouldNotRecoverProcessingEventBeforeLeaseExpires() {
        String eventId = "evt_stale_not_expired_001";
        insertTestEvent(eventId);

        assertEquals(1, webhookEventRepository.claimForProcessing(eventId));

        int recoveredRows = webhookEventRepository.recoverStaleProcessingEvents(
                Instant.now().minusSeconds(600),
                "Processing lease expired"
        );

        assertEquals(0, recoveredRows);

        WebhookEvent event = webhookEventRepository.findById(eventId)
                .orElseThrow();

        assertEquals(WebhookEventStatus.PROCESSING, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNull(event.getNextRetryAt());
    }

    private void insertTestEvent(String eventId) {
        int inserted = webhookEventRepository.insertIfAbsent(
                eventId,
                "payment.captured",
                "{\"id\":\"" + eventId + "\"}",
                Instant.now()
        );

        assertEquals(1, inserted);
    }
}
