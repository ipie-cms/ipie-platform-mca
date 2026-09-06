package in.gov.ipie.common.core.exception;

/** Raised when a requested resource does not exist. common-web maps this to HTTP 404. */
public class NotFoundException extends IpieException {

    public NotFoundException(String message) {
        super(CommonErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}