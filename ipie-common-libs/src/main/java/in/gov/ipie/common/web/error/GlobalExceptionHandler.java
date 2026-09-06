package in.gov.ipie.common.web.error;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import in.gov.ipie.common.persistence.IdCollisionException;
import in.gov.ipie.common.persistence.IntegrityViolations;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import in.gov.ipie.common.core.correlation.CorrelationConstants;
import in.gov.ipie.common.core.exception.ConflictException;
import in.gov.ipie.common.core.exception.FieldError;
import in.gov.ipie.common.core.exception.IpieException;
import in.gov.ipie.common.core.exception.NotFoundException;
import in.gov.ipie.common.core.exception.ValidationFailedException;
import in.gov.ipie.common.i18n.MessageResolver;
import in.gov.ipie.common.utils.exception.ExceptionUtils;

/**
 * The single place every iPIE service translates exceptions into the common {@link ApiError}
 * shape. Do not add per-service {@code @ExceptionHandler} methods that return a different error
 * format - extend this class or add new domain exception types instead (master standards doc,
 * 5.4).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (malformed
 * body, bean validation, unsupported media type, etc.) are funnelled through the same
 * {@code handleExceptionInternal} override and always come out in the common shape.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final MessageResolver messageResolver;
    private final List<IntegrityViolations> integrityViolations;

    public GlobalExceptionHandler(MessageResolver messageResolver,
            ObjectProvider<IntegrityViolations> integrityViolations) {
        this.messageResolver = messageResolver;
        // Optional: a service with no database declares none, and this handler then behaves exactly
        // as it did before - an integrity violation falls through to the unexpected-error path.
        this.integrityViolations = integrityViolations.orderedStream().toList();
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        return respond(HttpStatus.NOT_FOUND, ex.errorCode().code(), ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        log.warn("Conflict: {}", ex.getMessage());
        return respond(HttpStatus.CONFLICT, ex.errorCode().code(), ex.getMessage(), request);
    }

    /**
     * A generated id that duplicated an existing row.
     *
     * <p>Declared separately from the catch-all below, which would otherwise answer for it: an
     * {@code IpieException} with no more specific handler comes out as 422, and 422 says the caller
     * sent something unprocessable. Nothing about the request was wrong here, and the same request
     * repeated would succeed - so this is a 409, matching what the same collision produces when it
     * reaches {@link #handleDataIntegrityViolation} instead. Two paths reach it: the repository's own
     * {@code catch}, when Hibernate flushed inside the call, and the boundary, when it did not.
     */
    @ExceptionHandler(IdCollisionException.class)
    public ResponseEntity<ApiError> handleIdCollision(IdCollisionException ex, HttpServletRequest request) {
        // ERROR rather than WARN: with version-7 ids this cannot happen by chance, so its appearance
        // says something about id generation - or about hand-written seed data - and not about load.
        log.error("Generated id collided: {}", ex.getMessage(), ex);
        return respond(HttpStatus.CONFLICT, ex.errorCode().code(), ex.getMessage(), request);
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<ApiError> handleValidationFailed(ValidationFailedException ex, HttpServletRequest request) {
        log.warn("Validation failed: {}", ex.getMessage());
        ApiError body = ApiError.withFieldErrors(
                HttpStatus.BAD_REQUEST.value(),
                ex.errorCode().code(),
                ex.getMessage(),
                request.getRequestURI(),
                traceId(),
                ex.fieldErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .collect(Collectors.toList());
        String message = messageResolver.resolve("VALIDATION_FAILED", "Request validation failed");
        ApiError body = ApiError.withFieldErrors(
                HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message,
                request.getRequestURI(), traceId(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Base type catch-all for domain exceptions that do not have a more specific handler above. */
    @ExceptionHandler(IpieException.class)
    public ResponseEntity<ApiError> handleIpieException(IpieException ex, HttpServletRequest request) {
        log.warn("Business rule violation [{}]: {}", ex.errorCode().code(), ex.getMessage());
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.errorCode().code(), ex.getMessage(), request);
    }

    /**
     * A database integrity violation, translated into the error it actually is.
     *
     * <p><b>Why this is handled here and not in the repository that made the call.</b> Hibernate
     * defers the insert to flush, and with a transaction that commits after the repository method
     * returns, the violation surfaces from {@code JpaTransactionManager.doCommit} - outside any
     * try/catch the repository wrote. Repositories did catch it, and for a deferred flush that catch
     * was simply never reached: a duplicate phone number produced "an unexpected error occurred"
     * while the code that looked like it handled the case sat one stack frame too early. Handling it
     * at the boundary is the only place that sees it regardless of when the flush happens.
     *
     * <p>Unmatched violations keep the old behaviour deliberately - rethrown to the unexpected-error
     * path, logged in full. A foreign-key or check-constraint failure is a bug to see, and dressing
     * it as a 409 would tell the caller to change something they never sent.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        String constraint = IntegrityViolations.constraintName(ex).orElse(null);
        for (IntegrityViolations violations : integrityViolations) {
            // Only the declaration that owns this constraint may answer for it. Asking each in turn
            // to translate would let a table configured with a fallback message claim another
            // table's foreign key, and report a bug as the caller's conflict.
            if (!violations.declares(constraint)) {
                continue;
            }
            RuntimeException translated = violations.translate(ex);
            if (translated instanceof ConflictException conflict) {
                log.warn("Constraint conflict [{}]: {}", constraint, conflict.getMessage());
                return respond(HttpStatus.CONFLICT, conflict.errorCode().code(), conflict.getMessage(), request);
            }
            if (translated instanceof IdCollisionException collision) {
                // Through the same method the repository-thrown collision takes, so one kind of
                // failure cannot acquire two different status codes - or two log lines - by route.
                // The violation travels as the collision's cause, so that one line carries the
                // failed statement.
                return handleIdCollision(collision, request);
            }
        }
        return handleUnexpected(ex, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action", request);
    }

    /**
     * Anything not explicitly handled above is an unexpected error: log the full detail exactly
     * once here, at ERROR level, and return only a stable, non-leaking message to the caller
     * (master standards doc, 5.4 - never expose stack traces or internal detail to callers).
     *
     * <p>{@link ExceptionUtils#getRootCause} is logged alongside the full trace because {@code ex}
     * itself is often just a generic wrapper (e.g. a listener/invocation wrapper thrown by a Kafka
     * or RabbitMQ client) - the wrapper's own class/message rarely says anything actionable, so the
     * root cause is what's worth being able to grep for.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        Throwable rootCause = ExceptionUtils.getRootCause(ex);
        log.error("Unexpected error handling {} {} (root cause: {}: {})", request.getMethod(), request.getRequestURI(),
                rootCause.getClass().getSimpleName(), rootCause.getMessage(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        String message = messageResolver.resolve("VALIDATION_FAILED", "Request validation failed");
        ApiError body = ApiError.withFieldErrors(
                HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message,
                requestPath(request), traceId(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Covers a query/path param that fails to convert to its declared type - most commonly an
     * invalid enum value (e.g. {@code ?status=BOGUS}, {@code ?sortBy=BOGUS}). Spring's own
     * default for this ({@link ResponseEntityExceptionHandler}'s built-in handling) returns a
     * bare {@code ProblemDetail}, not this platform's {@link ApiError} shape - overriding it here
     * is what keeps every rejected request param consistent with every other validation failure,
     * platform-wide, not just for one controller.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "request";
        String fieldMessage = "'" + ex.getValue() + "' is not a valid value for '" + field + "'";
        String message = messageResolver.resolve("VALIDATION_FAILED", "Request validation failed");
        ApiError body = ApiError.withFieldErrors(
                HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message,
                requestPath(request), traceId(), List.of(new FieldError(field, fieldMessage)));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = messageResolver.resolve("MALFORMED_REQUEST", "Request body could not be read");
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(), "MALFORMED_REQUEST", message,
                requestPath(request), traceId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String errorCode, String defaultMessage, HttpServletRequest request) {
        String message = messageResolver.resolve(errorCode, defaultMessage);
        ApiError body = ApiError.of(status.value(), errorCode, message, request.getRequestURI(), traceId());
        return ResponseEntity.status(status).body(body);
    }

    private static String requestPath(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }

    private static String traceId() {
        return MDC.get(CorrelationConstants.TRACE_ID_MDC_KEY) != null
                ? MDC.get(CorrelationConstants.TRACE_ID_MDC_KEY)
                : MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
    }
}
