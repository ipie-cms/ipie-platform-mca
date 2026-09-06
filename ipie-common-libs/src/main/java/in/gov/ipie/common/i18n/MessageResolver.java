package in.gov.ipie.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import in.gov.ipie.common.core.exception.ErrorCode;

/**
 * The one way any iPIE service turns a stable {@link ErrorCode} into a message localized for the
 * current request's locale. Falls back to the caller-supplied default - almost always the
 * exception's own English message - whenever no translation key matches the code, so a service
 * with no translations at all, or only partial ones, behaves exactly as it did before this
 * module existed rather than ever surfacing a missing-key error to a caller.
 *
 * <p>Only resolves a whole message by code, not per-argument interpolation: an exception like
 * {@code UserNotFoundException} bakes its dynamic detail (the user id) directly into the English
 * message at throw time today, so a translated {@code USER_NOT_FOUND} bundle entry is
 * necessarily a generic sentence without that instance-specific detail, until a service migrates
 * its own exceptions to pass structured arguments instead - a per-service follow-up, not a
 * limitation of this resolver.
 */
public class MessageResolver {

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String resolve(ErrorCode errorCode, String defaultMessage) {
        return resolve(errorCode.code(), defaultMessage);
    }

    public String resolve(String code, String defaultMessage) {
        return messageSource.getMessage(code, null, defaultMessage, LocaleContextHolder.getLocale());
    }
}
