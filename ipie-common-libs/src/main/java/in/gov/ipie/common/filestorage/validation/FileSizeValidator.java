package in.gov.ipie.common.filestorage.validation;

import in.gov.ipie.common.filestorage.exception.FileTooLargeException;

/**
 * Enforces a per-file size limit for a given upload use case (master standards doc, file-upload
 * rules, section 2). A backstop, not the only control - a gateway/reverse-proxy-level cap should
 * reject oversized requests before they reach the service where one is in front of it.
 */
public final class FileSizeValidator {

    private FileSizeValidator() {
    }

    /** @throws FileTooLargeException if {@code actualBytes} exceeds {@code maxBytes} */
    public static void validate(long actualBytes, long maxBytes) {
        if (actualBytes > maxBytes) {
            throw new FileTooLargeException(actualBytes, maxBytes);
        }
    }
}
