package in.gov.ipie.common.core.model;

import java.time.Instant;

/**
 * The standard audit + soft-delete columns every iPIE table carries (see master standards doc,
 * 7.2 and the Database Mandatory Controls soft-delete rule): created_at/created_by/updated_at/
 * updated_by/version plus is_active/deleted_at/deleted_by. Domain models expose this as a single
 * value object instead of eight loose fields.
 */
public record AuditMetadata(
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        long version,
        boolean isActive,
        Instant deletedAt,
        String deletedBy) {
}
