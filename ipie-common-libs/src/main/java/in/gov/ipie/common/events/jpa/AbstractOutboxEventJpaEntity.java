package in.gov.ipie.common.events.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Row shape for one outbox event, held by the platform because every service's was identical.
 *
 * <p>A service declares only where the row lives:
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "outbox_events")   // or "iam_outbox_events" under the shared database
 * class OutboxEventEntity extends AbstractOutboxEventJpaEntity { }
 * }</pre>
 *
 * <p><b>Why the table name is the service's business and nothing else is.</b> ipie-iam-service
 * shares ipie-user-service's physical database, so its platform tables carry an {@code iam_} prefix
 * to avoid collisions - a real per-service fact. Everything else about an outbox row is a platform
 * decision: {@code payload} is the serialized {@code EventEnvelope} and {@code publishedAt} stays
 * null until the relay confirms the publisher took it. Hiding the prefix inside a naming strategy
 * was considered and rejected: it would make the one thing that genuinely differs the one thing you
 * cannot see in the entity.
 */
@MappedSuperclass
public abstract class AbstractOutboxEventJpaEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected AbstractOutboxEventJpaEntity() {
        // required by JPA, and used by the factory a service hands to JpaOutboxStore
    }

    public String getEventId() {
        return eventId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    /** Set once, by {@link JpaOutboxStore#save}, before the row is persisted. */
    void initialise(String eventId, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
