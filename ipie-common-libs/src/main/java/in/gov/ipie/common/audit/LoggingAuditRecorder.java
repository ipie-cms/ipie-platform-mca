package in.gov.ipie.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import in.gov.ipie.common.audit.model.AuditEvent;

/**
 * Reference {@link AuditRecorder}: writes each event as a single structured JSON line under the
 * dedicated {@code AUDIT} logger so it can be routed/retained separately from application logs by
 * the logging pipeline. Replace with a persistent implementation before relying on this for real
 * compliance evidence (master standards doc, 12.3) - see the class-level note on {@link AuditRecorder}.
 */
public class LoggingAuditRecorder implements AuditRecorder {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    private final ObjectMapper objectMapper;

    public LoggingAuditRecorder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            AUDIT_LOG.info(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            AUDIT_LOG.error("Failed to serialize audit event for action={} entityType={}", event.action(), event.entityType(), e);
        }
    }
}
