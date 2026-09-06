package in.gov.ipie.common.i18n;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Restricts locale resolution to the platform's canonical supported-locale list
 * ({@link IpieI18nProperties#getSupportedLocales()}) instead of
 * {@link AcceptHeaderLocaleResolver}'s default behaviour of accepting whatever language tag a
 * caller sends. A request for an unsupported language, a malformed header, or no header at all
 * all resolve the same way, to {@link IpieI18nProperties#getDefaultLocale()} - one rule every
 * service applies identically, rather than each interpreting an unmatched Accept-Language
 * differently.
 */
public class SupportedLocaleResolver extends AcceptHeaderLocaleResolver {

    private final List<Locale> supportedLocales;
    private final Locale defaultLocale;

    public SupportedLocaleResolver(List<Locale> supportedLocales, Locale defaultLocale) {
        this.supportedLocales = List.copyOf(supportedLocales);
        this.defaultLocale = defaultLocale;
        setSupportedLocales(this.supportedLocales);
        setDefaultLocale(defaultLocale);
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (header == null || header.isBlank()) {
            return defaultLocale;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            Locale match = Locale.lookup(ranges, supportedLocales);
            return match != null ? match : defaultLocale;
        } catch (IllegalArgumentException malformedHeader) {
            return defaultLocale;
        }
    }
}
