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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
