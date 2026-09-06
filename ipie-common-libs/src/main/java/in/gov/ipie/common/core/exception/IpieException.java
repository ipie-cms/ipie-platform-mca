package in.gov.ipie.common.core.exception;

/**
 * Base type for every business/domain exception raised in an iPIE service. common-web translates
 * these into the one common API error response - domain and application code must never build
 * HTTP responses themselves.
 */
public abstract class IpieException extends RuntimeException {

    private final ErrorCode errorCode;

    protected IpieException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected IpieException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}