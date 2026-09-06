package in.gov.ipie.common.core.exception;

import java.util.List;

/**
 * Raised for business-rule validation failures that go beyond simple bean validation (e.g. a
 * cross-field or state-dependent rule checked in the application/domain layer). common-web maps
 * this to HTTP 400 with {@code fieldErrors} populated.
 */
public class ValidationFailedException extends IpieException {

    private final List<FieldError> fieldErrors;

    public ValidationFailedException(String message, List<FieldError> fieldErrors) {
        super(CommonErrorCode.VALIDATION_FAILED, message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
