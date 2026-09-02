package in.gov.ipie.common.events.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Row shape for one consumed event id - the consumer-side half of exactly-once handling, recording
 * that this service has already acted on an event so a redelivery does not act twice.
 *
 * <p>A service declares only the table, exactly as with {@link AbstractOutboxEventEntity}.
 */
@MappedSuperclass
public abstract class AbstractProcessedEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected AbstractProcessedEventEntity() {
        // required by JPA, and used by the factory a service hands to JpaProcessedEventStore
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    void initialise(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }
}
