package in.gov.ipie.common.events.outbox;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import in.gov.ipie.common.events.publisher.EventPublisher;

/**
 * Drains the outbox into whichever {@link EventPublisher} is active - the business transaction that
 * wrote the row never talks to the broker itself (master standards doc, section 9).
 *
 * <p>Held by the platform because all three services ran a byte-identical copy of it. The service
 * that wants it needs {@code @EnableScheduling} on its application class and nothing else; the
 * interval, the batch size and the retention are properties.
 *
 * <p>Runs regardless of which publisher is wired in, so the outbox guarantee holds the same way in
 * every environment and not only where a broker happens to be configured.
 */
public class OutboxRelayScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final Duration purgeRetention;
    private final int batchSize;

    public OutboxRelayScheduler(OutboxStore outboxStore, EventPublisher eventPublisher,
            Duration purgeRetention, int batchSize) {
        this.relay = new OutboxRelay(outboxStore, eventPublisher);
        this.purgeRetention = purgeRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ipie.events.outbox.relay-interval-ms:5000}")
    public void relay() {
        try {
            int relayed = relay.relayPending(batchSize);
            if (relayed > 0) {
                LOG.debug("Relayed {} outbox event(s)", relayed);
            }
        } catch (Exception e) {
            // The database or broker being briefly unavailable is expected and transient - the next
            // scheduled run retries automatically, so this is a WARN with a short message, not an
            // unhandled ERROR stack trace that reads like the service is crashing.
            LOG.warn("Outbox relay pass failed, will retry next cycle: {}", e.getMessage());
        }
    }

    /**
     * Applies the outbox retention policy once a day.
     *
     * <p>Outbox payloads carry email addresses, names and phone numbers, and the table is
     * append-only, so without this it grows into a store of personal data no erasure request can
     * reach (DPDP s.8(7)). This is a delivery buffer, not the audit trail - the audit record is the
     * thing with a 180-day CERT-In obligation, and it lives elsewhere - so the default retention is
     * deliberately short.
     */
    @Scheduled(cron = "${ipie.events.outbox.purge-cron:0 30 3 * * *}")
    public void purge() {
        try {
            int purged = relay.purgePublished(purgeRetention);
            if (purged > 0) {
                LOG.info("Purged {} published outbox event(s) older than {}", purged, purgeRetention);
            }
        } catch (Exception e) {
            LOG.warn("Outbox purge pass failed, will retry next cycle: {}", e.getMessage());
        }
    }
}
