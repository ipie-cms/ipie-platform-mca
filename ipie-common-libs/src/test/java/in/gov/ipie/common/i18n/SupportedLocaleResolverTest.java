package in.gov.ipie.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SupportedLocaleResolverTest {

    private final SupportedLocaleResolver resolver =
            new SupportedLocaleResolver(List.of(Locale.ENGLISH, Locale.forLanguageTag("hi")), Locale.ENGLISH);

    @Test
    void aSupportedLanguageInAcceptLanguage_resolvesToThatLocale() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "hi");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.forLanguageTag("hi"));
    }

    @Test
    void anUnsupportedLanguage_fallsBackToTheDefaultLocale() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void noAcceptLanguageHeader_fallsBackToTheDefaultLocale() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void aMalformedAcceptLanguageHeader_fallsBackToTheDefaultLocaleInsteadOfThrowing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "not a language tag###");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void aWeightedAcceptLanguageHeaderPreferringAnUnsupportedLanguage_fallsBackToASupportedOne() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr-FR;q=0.9,hi;q=0.8");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.forLanguageTag("hi"));
    }
}
