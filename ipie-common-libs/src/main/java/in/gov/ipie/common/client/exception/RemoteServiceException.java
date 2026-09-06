package in.gov.ipie.common.client.exception;

/**
 * A downstream service rejected the call with a {@code 4xx} - a functional/business outcome, not
 * a transient dependency failure, so {@code common-resilience}'s default Retry/CircuitBreaker
 * never treat it as one (Development_Environment_Configuration.md, Section 15, "Retry": a
 * functional/business error is never retried).
 *
 * <p>Deliberately not an {@code IpieException} subtype: a downstream 404/409/etc. is a fact about
 * the dependency call, not automatically the calling service's own domain error - blanket-mapping
 * it to one generic status would misrepresent what actually went wrong to that service's own API
 * consumers. Catch this at the call site and translate it into whatever domain-appropriate
 * exception (or {@code NotFoundException}/{@code ConflictException}/etc.) fits the specific call;
 * left uncaught, it surfaces as a generic 500 via {@code common-web}'s
 * {@code GlobalExceptionHandler} catch-all, which is a safe default but rarely the best one.
 */
public class RemoteServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String serviceName;
    private final int statusCode;
    private final String responseBody;

    public RemoteServiceException(String serviceName, int statusCode, String responseBody, Throwable cause) {
        super("Service '" + serviceName + "' rejected the request with status " + statusCode, cause);
        this.serviceName = serviceName;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public String serviceName() {
        return serviceName;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
