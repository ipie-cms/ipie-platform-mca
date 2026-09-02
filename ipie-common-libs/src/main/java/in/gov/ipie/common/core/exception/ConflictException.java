package in.gov.ipie.common.core.exception;

/**
 * Raised when a request conflicts with the current state of a resource (duplicate unique key,
 * stale optimistic-lock version, etc). common-web maps this to HTTP 409.
 */
public class ConflictException extends IpieException {

    public ConflictException(String message) {
        super(CommonErrorCode.CONFLICT, message);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}