package in.gov.ipie.common.audit;

import in.gov.ipie.common.audit.model.AuditEvent;

/**
 * Port every service writes audit records through. The default binding
 * ({@code LoggingAuditRecorder}) is a reference implementation only - platform teams are expected
 * to provide a persistent implementation (dedicated audit table/service or append-only event
 * stream) and override this bean; business/application code should never need to change when
 * that swap happens.
 */
public interface AuditRecorder {

    void record(AuditEvent event);
}
