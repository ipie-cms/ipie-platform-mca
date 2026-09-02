package in.gov.ipie.common.audit.outbox;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.events.envelope.EventEnvelope;

/**
 * The read-side counterpart to {@link OutboxAuditRecorder}: recognizes and decodes an
 * {@code AUDIT_EVENT} envelope out of the generic {@code EventEnvelope<?>} stream a service's own
 * event-log consumer already receives (every event this service publishes flows through one
 * queue/topic, domain events and audit events alike - see {@code RabbitUserEventLogConsumer}).
 * Shared here because the recognize-and-decode logic is identical for every service; persisting
 * the result has to stay per-service (different JPA entity/table per service's own schema).
 */
public final class AuditEventCodec {

    private AuditEventCodec() {
    }

    /**
     * @return the decoded {@link AuditEvent} if {@code envelope} is one (its {@code eventType}
     *     equals {@link OutboxAuditRecorder#AUDIT_EVENT_TYPE}), empty for any other event type
     */
    public static Optional<AuditEvent> decodeIfAuditEvent(EventEnvelope<?> envelope, ObjectMapper objectMapper) {
        if (!OutboxAuditRecorder.AUDIT_EVENT_TYPE.equals(envelope.eventType())) {
            return Optional.empty();
        }
        // envelope.data() arrives here as whatever the generic EventEnvelope<?> listener's own
        // message converter produced for an untyped field (a LinkedHashMap, at this
        // already-deserialized boundary) - convertValue (object-graph conversion), not readValue
        // (JSON-string parsing), is the correct Jackson operation for turning that back into a
        // real AuditEvent.
        return Optional.of(objectMapper.convertValue(envelope.data(), AuditEvent.class));
    }
}
