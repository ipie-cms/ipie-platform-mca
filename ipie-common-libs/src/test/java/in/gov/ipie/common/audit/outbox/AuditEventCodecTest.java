package in.gov.ipie.common.audit.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.audit.model.AuditEventType;
import in.gov.ipie.common.events.envelope.EventEnvelope;

class AuditEventCodecTest {

    // The real app's ObjectMapper bean has JavaTimeModule auto-registered by Spring Boot - a bare
    // `new ObjectMapper()` here does not, so it's registered explicitly to match production
    // behaviour (AuditEvent.occurredAt is a java.time.Instant).
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void decodeIfAuditEvent_returnsEmpty_forAnyOtherEventType() {
        EventEnvelope<Map<String, Object>> event =
                EventEnvelope.create("USER_CREATED", 1, "ipie-user-service", "corr-1", null, Map.of("id", "user-1"));

        Optional<AuditEvent> decoded = AuditEventCodec.decodeIfAuditEvent(event, objectMapper);

        assertThat(decoded).isEmpty();
    }

    @Test
    void decodeIfAuditEvent_decodesARealAuditEvent() {
        AuditEvent original = new AuditEvent(
                AuditEventType.BUSINESS, "USER_CREATED", "USER", "user-1", null, "actor-1", "127.0.0.1",
                "ipie-user-service", "created via admin", null, null, "corr-1", Instant.now());
        // Round-trips through the ObjectMapper the same way the real RabbitMQ message converter
        // would - the envelope's data field arrives generically deserialized (a Map), not as a
        // real AuditEvent, at the point decodeIfAuditEvent is called.
        Map<String, Object> asMap = objectMapper.convertValue(original, Map.class);
        EventEnvelope<Map<String, Object>> event =
                EventEnvelope.create(OutboxAuditRecorder.AUDIT_EVENT_TYPE, 1, "ipie-user-service", "corr-1", null, asMap);

        Optional<AuditEvent> decoded = AuditEventCodec.decodeIfAuditEvent(event, objectMapper);

        assertThat(decoded).contains(original);
    }
}
