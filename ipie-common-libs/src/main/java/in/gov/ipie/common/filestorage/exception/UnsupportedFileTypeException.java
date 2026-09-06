package in.gov.ipie.common.filestorage.exception;

import java.util.List;

import in.gov.ipie.common.core.exception.FieldError;
import in.gov.ipie.common.core.exception.ValidationFailedException;

/**
 * Raised when a file's actual sniffed content type (never just its extension - master standards
 * doc, file-upload rules, section 1) is not in the caller's whitelist for this use case. Maps to
 * HTTP 400 via {@code ValidationFailedException}.
 */
public class UnsupportedFileTypeException extends ValidationFailedException {

    public UnsupportedFileTypeException(String detectedMimeType) {
        super("Unsupported file type: " + detectedMimeType,
                List.of(new FieldError("file", "File type '" + detectedMimeType + "' is not allowed for this upload")));
    }
}
