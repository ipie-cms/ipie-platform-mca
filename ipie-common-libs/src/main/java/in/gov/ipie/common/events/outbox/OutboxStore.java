package in.gov.ipie.common.events.outbox;

import java.time.Instant;
import java.util.List;

import in.gov.ipie.common.events.envelope.EventEnvelope;

/**
 * Port backing the transactional outbox pattern: {@link #save} must be called inside the same
 * database transaction as the business change the event describes, so the two either both commit
 * or both roll back - the business transaction never talks to the broker directly, which is what
 * makes the write atomic (master standards doc, section 9). A separate {@link OutboxRelay} later
 * reads unpublished rows and hands them to the real {@code EventPublisher}.
 *
 * <p>Each service backs this with its own table (a dedicated outbox table is the usual choice -
 * see {@code ipie-service-template}'s implementation for the pattern used), since each service
 * owns its data (database-per-service - see {@code Database_Environment_Configuration.md}'s
 * Database Mandatory Controls).
 */
public interface OutboxStore {

    void save(EventEnvelope<?> event);

    List<EventEnvelope<?>> findUnpublished(int limit);

    void markPublished(String eventId);

    /**
     * Deletes events already published before {@code cutoff}.
     *
     * <p>Retention, not cleanliness. Outbox payloads are serialised event envelopes carrying email
     * addresses, names and phone numbers, and the table is append-only, so an unbounded outbox is a
     * growing store of personal data that no erasure request can reach - DPDP s.8(7). Bounding it is
     * what makes erasure answerable at all.
     *
     * <p><b>Only published rows.</b> An unpublished row is undelivered work, not history; deleting
     * one on age would silently drop a business event and leave no trace that it existed.
     * Implementations must filter on the published timestamp, never on {@code occurred_at} alone.
     *
     * @return how many rows were removed
     */
    int deletePublishedBefore(Instant cutoff);
}
