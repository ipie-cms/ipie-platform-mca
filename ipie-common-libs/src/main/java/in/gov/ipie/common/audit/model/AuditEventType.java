package in.gov.ipie.common.audit.model;

/**
 * The two audit categories from the master standards doc (5.7). Application/troubleshooting logs
 * are a separate concern (common-observability) and are never mixed into audit records.
 */
public enum AuditEventType {
    /** Login, access, permission and other security-relevant events. */
    SECURITY,
    /** Important user actions and changes to business/case data. */
    BUSINESS
}
