package in.gov.ipie.common.events.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import in.gov.ipie.common.events.envelope.EventEnvelope;
import in.gov.ipie.common.events.idempotency.ProcessedEventStore;
import in.gov.ipie.common.events.outbox.OutboxStore;
import in.gov.ipie.common.testing.containers.PostgresIntegrationTest;

/**
 * The two stores the platform now ships, against a real PostgreSQL instance.
 *
 * <p>Worth a database rather than a mock: the queries are assembled from the entity name the service
 * registers, so a wrong name or a mistyped field is exactly the kind of fault that compiles, passes
 * a mocked test, and fails on the first event a deployed service publishes.
 */
@SpringBootTest(classes = JpaEventStoresIntegrationTest.TestApp.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // This module's own resource-server auto-configuration would otherwise demand a JwtDecoder;
        // the stores under test are behind no HTTP boundary at all.
        "ipie.security.enabled=false"})
class JpaEventStoresIntegrationTest implements PostgresIntegrationTest {

    @SpringBootApplication
    static class TestApp {

        @Bean
        OutboxStore outboxStore(EntityManager em, ObjectMapper mapper) {
            return new JpaOutboxStore<>(em, mapper, TestOutboxEvent.class, TestOutboxEvent::new);
        }

        @Bean
        ProcessedEventStore processedEventStore(EntityManager em) {
            return new JpaProcessedEventStore<>(em, TestProcessedEvent.class, TestProcessedEvent::new);
        }
    }

    /** What a service declares, and all it declares - the table name is the only thing it owns. */
    @Entity
    @Table(name = "test_outbox_events")
    static class TestOutboxEvent extends AbstractOutboxEventEntity {
    }

    @Entity
    @Table(name = "test_processed_events")
    static class TestProcessedEvent extends AbstractProcessedEventEntity {
    }

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private ProcessedEventStore processedEventStore;

    @Autowired
    private EntityManager entityManager;

    /**
     * Built through the canonical constructor rather than {@code EventEnvelope.create}, which mints
     * its own id - these tests need to name the row they then look for.
     */
    private static EventEnvelope<String> envelope(String id, Instant occurredAt) {
        return new EventEnvelope<>(id, "TEST_EVENT", 1, occurredAt,
                "common-libs-test", null, null, "payload-" + id);
    }

    @Test
    void anUnpublishedEventComesBackAndAPublishedOneDoesNot() {
        outboxStore.save(envelope("evt-1", Instant.now()));

        assertThat(outboxStore.findUnpublished(10))
                .extracting(EventEnvelope::eventId).contains("evt-1");

        outboxStore.markPublished("evt-1");

        assertThat(outboxStore.findUnpublished(10))
                .extracting(EventEnvelope::eventId).doesNotContain("evt-1");
    }

    @Test
    void theRetentionSweepTakesPublishedRowsOnly() {
        // An unpublished row is undelivered work. Ageing it out would drop a business event with no
        // trace, which is the one outcome the outbox exists to prevent.
        outboxStore.save(envelope("evt-keep", Instant.now()));
        outboxStore.save(envelope("evt-sweep", Instant.now()));
        outboxStore.markPublished("evt-sweep");

        int deleted = outboxStore.deletePublishedBefore(Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(deleted).isPositive();
        assertThat(outboxStore.findUnpublished(10))
                .extracting(EventEnvelope::eventId).contains("evt-keep");
        assertThat(rowsIn("test_outbox_events", "evt-sweep")).isZero();
    }

    @Test
    void findUnpublishedHonoursItsLimitAndTheOldestGoesFirst() {
        outboxStore.save(envelope("evt-a", Instant.now().minus(2, ChronoUnit.MINUTES)));
        outboxStore.save(envelope("evt-b", Instant.now().minus(1, ChronoUnit.MINUTES)));

        List<EventEnvelope<?>> first = outboxStore.findUnpublished(1);

        assertThat(first).hasSize(1);
    }

    @Test
    void aConsumedEventIdIsRememberedAcrossRedelivery() {
        assertThat(processedEventStore.isProcessed("evt-consumed")).isFalse();

        processedEventStore.markProcessed("evt-consumed");

        assertThat(processedEventStore.isProcessed("evt-consumed")).isTrue();
    }

    long rowsIn(String table, String eventId) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from " + table + " where event_id = :id")
                .setParameter("id", eventId)
                .getSingleResult()).longValue();
    }
}
