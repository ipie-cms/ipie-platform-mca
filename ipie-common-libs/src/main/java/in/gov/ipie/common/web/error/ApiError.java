package in.gov.ipie.common.web.error;

import java.time.Instant;
import java.util.List;

import in.gov.ipie.common.core.exception.FieldError;

/**
 * The one common error response shape returned by every iPIE API (master standards doc, 5.3/5.4).
 * No service should invent its own error JSON structure.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        String traceId,
        List<FieldError> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiError of(int status, String errorCode, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, errorCode, message, path, traceId, List.of());
    }

    public static ApiError withFieldErrors(
            int status, String errorCode, String message, String path, String traceId, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status, errorCode, message, path, traceId, fieldErrors);
    }
}
