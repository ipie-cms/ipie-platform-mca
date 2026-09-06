package in.gov.ipie.common.i18n.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import in.gov.ipie.common.core.locale.LocaleConstants;

class LocaleLoggingInterceptorTest {

    private final LocaleLoggingInterceptor interceptor = new LocaleLoggingInterceptor();

    @AfterEach
    void cleanup() {
        LocaleContextHolder.resetLocaleContext();
        MDC.clear();
    }

    @Test
    void preHandle_publishesTheAlreadyResolvedLocaleToMdc() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("hi"));

        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertThat(MDC.get(LocaleConstants.LOCALE_MDC_KEY)).isEqualTo("hi");
    }

    @Test
    void afterCompletion_removesTheLocaleFromMdc() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("hi"));
        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(MDC.get(LocaleConstants.LOCALE_MDC_KEY)).isNull();
    }
}
