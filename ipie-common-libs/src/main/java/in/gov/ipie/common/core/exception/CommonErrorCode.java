package in.gov.ipie.common.core.exception;

/**
 * Generic error codes usable by any service. Prefer a service-specific {@link ErrorCode}
 * (e.g. a {@code UserErrorCode.USER_NOT_FOUND} enum) whenever a more precise code helps API
 * consumers - these generic codes exist so common exceptions remain usable out of the box.
 */
public enum CommonErrorCode implements ErrorCode {
    NOT_FOUND,
    CONFLICT,
    VALIDATION_FAILED,
    ILLEGAL_STATE;

    @Override
    public String code() {
        return name();
    }
}