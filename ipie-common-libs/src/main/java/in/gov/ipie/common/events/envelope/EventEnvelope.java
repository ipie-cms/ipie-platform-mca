package in.gov.ipie.common.events.envelope;

import java.time.Instant;
import in.gov.ipie.common.utils.id.IdGenerator;

/**
 * The one envelope shape every iPIE business event is published in (master standards doc, section
 * 9). {@code eventVersion} is the event *contract* version - bump it, and keep publishing the old
 * version alongside the new one until every consumer has migrated, whenever a change would break
 * existing consumers.
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String source,
        String correlationId,
        String caseId,
        T data) {

    public static <T> EventEnvelope<T> create(
            String eventType, int eventVersion, String source, String correlationId, String caseId, T data) {
        return new EventEnvelope<>(
                IdGenerator.newId(), eventType, eventVersion, Instant.now(), source, correlationId, caseId, data);
    }
}
