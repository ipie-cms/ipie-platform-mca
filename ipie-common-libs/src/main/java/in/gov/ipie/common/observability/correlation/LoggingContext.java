package in.gov.ipie.common.observability.correlation;

import org.slf4j.MDC;

import in.gov.ipie.common.core.correlation.CorrelationConstants;
import in.gov.ipie.common.core.locale.LocaleConstants;

/**
 * Application code sets the business-specific structured log fields (master standards doc, 5.6)
 * through this helper instead of calling {@link MDC} directly, so the MDC key names stay an
 * internal detail of this module.
 */
public final class LoggingContext {

    private static final String CASE_ID_MDC_KEY = "caseId";
    private static final String USER_ID_MDC_KEY = "userId";

    private LoggingContext() {
    }

    public static void putCaseId(String caseId) {
        MDC.put(CASE_ID_MDC_KEY, caseId);
    }

    public static void putUserId(String userId) {
        MDC.put(USER_ID_MDC_KEY, userId);
    }

    public static void putCorrelationId(String correlationId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, correlationId);
    }

    public static String correlationId() {
        return MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
    }

    /**
     * The id an error response carries so a report can be tied back to the logs: the tracing
     * system's trace id when there is one, falling back to the correlation id (there is always one
     * of those, set by {@code CorrelationIdFilter}, even with tracing disabled or not yet started).
     *
     * <p>Lives here rather than in each responder because more than one place builds an error body
     * - {@code GlobalExceptionHandler} for anything thrown by a controller, and
     * {@code RateLimitFilter} for a rejection that short-circuits before any controller runs.
     */
    public static String traceId() {
        String traceId = MDC.get(CorrelationConstants.TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
    }

    /** Called by common-i18n's LocaleLoggingInterceptor once DispatcherServlet has resolved the request's locale. */
    public static void putLocale(String locale) {
        MDC.put(LocaleConstants.LOCALE_MDC_KEY, locale);
    }

    public static void clearLocale() {
        MDC.remove(LocaleConstants.LOCALE_MDC_KEY);
    }

    public static void clear() {
        MDC.remove(CASE_ID_MDC_KEY);
        MDC.remove(USER_ID_MDC_KEY);
        MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        MDC.remove(CorrelationConstants.TRACE_ID_MDC_KEY);
        MDC.remove(LocaleConstants.LOCALE_MDC_KEY);
    }
}
