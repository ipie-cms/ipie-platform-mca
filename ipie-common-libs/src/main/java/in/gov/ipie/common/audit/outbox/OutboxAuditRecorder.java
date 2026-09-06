package in.gov.ipie.common.audit.outbox;

import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.events.envelope.EventEnvelope;
import in.gov.ipie.common.events.outbox.OutboxStore;

/**
 * Real, durable {@link AuditRecorder}: writes every audit event through the same transactional
 * outbox {@code common-events} already defines for domain events, rather than standing up a
 * second, parallel delivery pipeline just for audit. This reuses the exact mechanism
 * {@code ipie-service-template}'s {@code JpaOutboxStore}/{@code OutboxRelayScheduler}/
 * {@code KafkaEventPublisher} already provide: a durable, at-least-once, ordered-per-partition
 * delivery of every recorded event to Kafka (or RabbitMQ, whichever this service has configured -
 * see {@code EventPublisherConfig}) - which is what makes the audit trail immutable-in-transit
 * rather than a log line that can be lost or edited (contrast {@code LoggingAuditRecorder}).
 *
 * <p>Takes precedence over {@code LoggingAuditRecorder} automatically once a service has an
 * {@link OutboxStore} bean (see {@code AuditAutoConfiguration}) - the same "real binding wins over
 * the reference implementation when configured" precedence {@code EventPublisherConfig} and
 * {@code IpieCacheAutoConfiguration} already use elsewhere in this platform.
 *
 * <p><b>{@link #record} must be called inside the same database transaction as the business
 * change the audit event describes</b> - exactly the same rule {@link OutboxStore#save}'s own
 * Javadoc states for domain events - so the two either both commit or both roll back. {@code
 * AuditAspect} already calls this after the annotated method itself returns, inside that method's
 * own transaction, satisfying this by construction for the {@code @Auditable} path; a manual
 * {@code AuditRecorder.record} call (e.g. {@code DocumentService.recordSecurityAudit}) must
 * likewise happen inside an active transaction.
 *
 * <p>Consuming/persisting the resulting {@code AUDIT_EVENT} records into a queryable audit store
 * (or forwarding them to a SIEM) is deployment-specific, the same way domain-event consumption is
 * - this class's job ends at reliable delivery into the outbox/broker pipeline.
 */
public class OutboxAuditRecorder implements AuditRecorder {

    /** The audit event envelope's stable {@code eventType} - one generic type; the real detail is {@link AuditEvent#action()}. */
    public static final String AUDIT_EVENT_TYPE = "AUDIT_EVENT";

    /** The audit envelope's contract version - bump only alongside a deliberate, backward-compatible {@link AuditEvent} shape change. */
    public static final int AUDIT_EVENT_CONTRACT_VERSION = 1;

    private final OutboxStore outboxStore;
    private final String serviceName;

    public OutboxAuditRecorder(OutboxStore outboxStore, String serviceName) {
        this.outboxStore = outboxStore;
        this.serviceName = serviceName;
    }

    @Override
    public void record(AuditEvent event) {
        EventEnvelope<AuditEvent> envelope = EventEnvelope.create(
                AUDIT_EVENT_TYPE, AUDIT_EVENT_CONTRACT_VERSION, serviceName, event.correlationId(), event.caseId(), event);
        outboxStore.save(envelope);
    }
}
