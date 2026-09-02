package in.gov.ipie.common.core.exception;

/** A single field-level validation failure, surfaced to callers under {@code fieldErrors}. */
public record FieldError(String field, String message) {
}
