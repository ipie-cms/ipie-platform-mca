package in.gov.ipie.common.core.exception;

/**
 * Stable, per-domain application error code carried by every {@link IpieException}.
 * Codes are contracts with API consumers - once released, a code's meaning must not change.
 */
public interface ErrorCode {

    /**
     * Stable identifier returned to callers, e.g. {@code "USER_NOT_FOUND"}. Must never contain
     * stack traces, table names, file paths or other internal detail (see master standards doc, 5.4).
     */
    String code();
}