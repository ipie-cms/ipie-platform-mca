package in.gov.ipie.common.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

import in.gov.ipie.common.core.locale.LocaleConstants;

/**
 * {@code ipie.i18n.*} - the platform-wide canonical list of supported locales. Deliberately not a
 * per-service choice: Keycloak, notification templates and the frontend all have to agree on the
 * same locale tags, so one shared list (English + Hindi baseline) is the source of truth every
 * service resolves against via {@link SupportedLocaleResolver} - adding a language later is a
 * config change here, not a per-service code change.
 */
@ConfigurationProperties("ipie.i18n")
public class IpieI18nProperties {

    private List<Locale> supportedLocales = new ArrayList<>(List.of(Locale.ENGLISH, new Locale("hi")));

    private Locale defaultLocale = LocaleConstants.DEFAULT_LOCALE;

    public List<Locale> getSupportedLocales() {
        return new ArrayList<>(supportedLocales);
    }

    public void setSupportedLocales(List<Locale> supportedLocales) {
        this.supportedLocales = new ArrayList<>(supportedLocales);
    }

    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(Locale defaultLocale) {
        this.defaultLocale = defaultLocale;
    }
}
