package in.gov.ipie.common.events.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import in.gov.ipie.common.events.envelope.EventEnvelope;
import in.gov.ipie.common.events.publisher.EventPublisher;

/**
 * Drains an {@link OutboxStore} into an {@link EventPublisher}, one batch at a time. A service
 * calls {@link #relayPending} from its own scheduling mechanism (e.g. Spring's {@code @Scheduled})
 * - deliberately framework-agnostic here, the same reasoning as {@link
 * in.gov.ipie.common.events.idempotency.IdempotentEventHandler} on the consumer side.
 *
 * <p>Guarantees at-least-once delivery, not exactly-once: if the process crashes between {@link
 * EventPublisher#publish} succeeding and the store being marked published, the same event is
 * relayed again on the next pass. This is why a consumer must always pair with {@link
 * in.gov.ipie.common.events.idempotency.ProcessedEventStore}/{@code IdempotentEventHandler} -
 * together the two give reliable, effectively-exactly-once processing despite at-least-once
 * delivery on the wire (master standards doc, section 9).
 */
public class OutboxRelay {

    /**
     * Applies the retention policy: published events older than {@code retention} are deleted.
     *
     * <p>Lives here rather than in each service's scheduler so the policy - and the rule that
     * unpublished rows are never touched - is written once and tested once.
     *
     * @return how many rows were removed
     */
    public int purgePublished(Duration retention) {
        return store.deletePublishedBefore(Instant.now().minus(retention));
    }

    private final OutboxStore store;
    private final EventPublisher publisher;

    public OutboxRelay(OutboxStore store, EventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    /**
     * Publishes up to {@code batchSize} unpublished events, marking each sent immediately after a
     * successful publish call. Returns how many were relayed.
     */
    public int relayPending(int batchSize) {
        List<EventEnvelope<?>> pending = store.findUnpublished(batchSize);
        for (EventEnvelope<?> event : pending) {
            publisher.publish(event);
            store.markPublished(event.eventId());
        }
        return pending.size();
    }
}
