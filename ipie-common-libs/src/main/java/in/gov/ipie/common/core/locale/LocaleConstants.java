package in.gov.ipie.common.core.locale;

import java.util.Locale;

/**
 * Shared locale constants. Defined once in common-core so common-i18n (resolves the current
 * request's locale and publishes it to the MDC) and common-client (propagates it to downstream
 * services) agree on the same default without depending on each other - the same reasoning as
 * {@link in.gov.ipie.common.core.correlation.CorrelationConstants}.
 */
public final class LocaleConstants {

    /** Locale used when a request's Accept-Language is absent or names an unsupported language. */
    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /** SLF4J MDC key the resolved locale is published under for structured logging. */
    public static final String LOCALE_MDC_KEY = "locale";

    private LocaleConstants() {
    }
}
