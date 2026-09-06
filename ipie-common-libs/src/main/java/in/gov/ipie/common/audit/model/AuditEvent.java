package in.gov.ipie.common.audit.model;

import java.time.Instant;

/**
 * A single audit record, capturing the fields the master standards doc requires (5.7): who, what,
 * when, from where, which service, which entity/case, and old/new values where required.
 */
public record AuditEvent(
        AuditEventType eventType,
        String action,
        String entityType,
        String entityId,
        String caseId,
        String actorUserId,
        String sourceIp,
        String serviceName,
        String comment,
        Object oldValue,
        Object newValue,
        String correlationId,
        Instant occurredAt) {
}
