package in.gov.ipie.common.filestorage.exception;

import java.util.List;

import in.gov.ipie.common.core.exception.FieldError;
import in.gov.ipie.common.core.exception.ValidationFailedException;

/**
 * Raised when a file exceeds the per-file limit for its use case (master standards doc,
 * file-upload rules, section 2). Enforcing this at the service layer is a backstop, not the only
 * control - a gateway-level cap (e.g. Kong/APISIX) should reject oversized requests earlier where
 * one is in front of this service; see the master standards doc's gaps list for that dependency.
 */
public class FileTooLargeException extends ValidationFailedException {

    public FileTooLargeException(long actualBytes, long maxBytes) {
        super("File too large: " + actualBytes + " bytes (max " + maxBytes + ")",
                List.of(new FieldError("file", "File exceeds the maximum allowed size of " + maxBytes + " bytes")));
    }
}
