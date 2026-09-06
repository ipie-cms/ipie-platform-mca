package in.gov.ipie.common.audit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.audit.model.AuditEventType;
import in.gov.ipie.common.events.envelope.EventEnvelope;
import in.gov.ipie.common.events.outbox.OutboxStore;

/**
 * Proves {@link OutboxAuditRecorder} writes every audit event through {@link OutboxStore} (the
 * same transactional-outbox port domain events use), carrying the audit event's own correlation
 * id/case id onto the envelope, rather than through a second, parallel delivery mechanism.
 */
class OutboxAuditRecorderTest {

    @Test
    void record_savesTheAuditEventThroughTheOutboxStore() {
        FakeOutboxStore outboxStore = new FakeOutboxStore();
        OutboxAuditRecorder recorder = new OutboxAuditRecorder(outboxStore, "ipie-service-template");
        AuditEvent event = new AuditEvent(
                AuditEventType.BUSINESS, "USER_CREATED", "USER", "user-123", "case-456", "actor-1", "127.0.0.1",
                "ipie-service-template", null, null, null, "corr-789", Instant.now());

        recorder.record(event);

        assertThat(outboxStore.saved).hasSize(1);
        EventEnvelope<?> envelope = outboxStore.saved.get(0);
        assertThat(envelope.eventType()).isEqualTo(OutboxAuditRecorder.AUDIT_EVENT_TYPE);
        assertThat(envelope.eventVersion()).isEqualTo(OutboxAuditRecorder.AUDIT_EVENT_CONTRACT_VERSION);
        assertThat(envelope.source()).isEqualTo("ipie-service-template");
        assertThat(envelope.correlationId()).isEqualTo("corr-789");
        assertThat(envelope.caseId()).isEqualTo("case-456");
        assertThat(envelope.data()).isEqualTo(event);
    }

    private static final class FakeOutboxStore implements OutboxStore {

        private final List<EventEnvelope<?>> saved = new ArrayList<>();

        @Override
        public void save(EventEnvelope<?> event) {
            saved.add(event);
        }

        @Override
        public List<EventEnvelope<?>> findUnpublished(int limit) {
            return List.of();
        }

        @Override
        public void markPublished(String eventId) {
        }

        // Retention sweep - this test only asserts what the recorder writes, so nothing is deleted
        // and the count is zero. Present because OutboxStore declares it, not because it is exercised.
        @Override
        public int deletePublishedBefore(Instant cutoff) {
            return 0;
        }
    }
}
