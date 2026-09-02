package in.gov.ipie.common.resilience.exception;

/**
 * Marks a failure from an outbound/inter-service call as transient - safe to retry (timeouts,
 * connection resets, 5xx-style dependency failures). Wrap only genuinely transient failures in
 * this exception; a functional/business error (validation failure, 4xx-style rejection) must
 * propagate as its own exception type instead, since {@code common-resilience}'s default retry
 * configuration retries only {@link java.io.IOException}, {@link java.util.concurrent.TimeoutException}
 * and this type - see Development_Environment_Configuration.md, Section 15, "Retry": only
 * transient failures are retried, never a functional/business error.
 */
public class TransientDependencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransientDependencyException(String message) {
        super(message);
    }

    public TransientDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
