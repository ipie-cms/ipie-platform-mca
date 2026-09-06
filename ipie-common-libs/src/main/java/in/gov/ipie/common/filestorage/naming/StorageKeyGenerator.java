package in.gov.ipie.common.filestorage.naming;

import in.gov.ipie.common.utils.id.IdGenerator;

/**
 * Builds storage keys (master standards doc, file-upload rules, section 4): never the
 * user-supplied filename directly - a UUID-based key, structured by module/entity/doc-type so
 * access control and lifecycle rules can be applied predictably (e.g.
 * {@code liquidation/{caseId}/{docType}/{uuid}.pdf}). The original filename is kept as metadata
 * only, never as part of the storage path.
 *
 * <p>One {@link #newObjectPath} covers both stages of the quarantine-first pattern (section 3):
 * {@link #quarantineKey} and {@link #permanentKey} share the same path suffix under different
 * prefixes, so promoting a clean file is a same-key copy from one prefix to the other.
 */
public final class StorageKeyGenerator {

    private static final String QUARANTINE_PREFIX = "quarantine";
    private static final String PERMANENT_PREFIX = "permanent";

    private StorageKeyGenerator() {
    }

    /** e.g. {@code liquidation/<caseId>/court-order/<uuid>.pdf} - not yet prefixed for either quarantine or permanent storage. */
    public static String newObjectPath(String module, String entityId, String docType, String extension) {
        String ext = extension.startsWith(".") ? extension : "." + extension;
        return module + "/" + entityId + "/" + docType + "/" + IdGenerator.newId() + ext;
    }

    public static String quarantineKey(String objectPath) {
        return QUARANTINE_PREFIX + "/" + objectPath;
    }

    public static String permanentKey(String objectPath) {
        return PERMANENT_PREFIX + "/" + objectPath;
    }
}
