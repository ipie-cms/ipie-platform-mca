package in.gov.ipie.common.core.correlation;

/**
 * Shared constants for request correlation. Defined once in common-core so common-web (reads the
 * trace id back out to build the API error response) and common-observability (owns writing it)
 * agree on the same keys without depending on each other.
 */
public final class CorrelationConstants {

    /** Inbound/outbound HTTP header carrying the correlation id across service boundaries. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** SLF4J MDC key the correlation id is published under for structured logging. */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** SLF4J MDC key the current distributed trace id is published under, when tracing is active. */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private CorrelationConstants() {
    }
}
