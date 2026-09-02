package in.gov.ipie.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import in.gov.ipie.common.core.exception.CommonErrorCode;

class MessageResolverTest {

    private final StaticMessageSource messageSource = new StaticMessageSource();
    private final MessageResolver resolver = new MessageResolver(messageSource);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void noTranslationForTheCurrentLocale_fallsBackToTheSuppliedDefault() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("hi"));

        String message = resolver.resolve("USER_NOT_FOUND", "User was not found");

        assertThat(message).isEqualTo("User was not found");
    }

    @Test
    void aTranslationForTheCurrentLocale_isUsedInsteadOfTheDefault() {
        messageSource.addMessage("USER_NOT_FOUND", Locale.forLanguageTag("hi"), "उपयोगकर्ता नहीं मिला");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("hi"));

        String message = resolver.resolve("USER_NOT_FOUND", "User was not found");

        assertThat(message).isNotEqualTo("User was not found");
    }

    @Test
    void resolvingByErrorCode_usesTheCodesStringValueAsTheKey() {
        messageSource.addMessage(CommonErrorCode.NOT_FOUND.code(), Locale.ENGLISH, "Resource not found");
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        String message = resolver.resolve(CommonErrorCode.NOT_FOUND, "fallback");

        assertThat(message).isEqualTo("Resource not found");
    }
}
