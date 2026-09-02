package in.gov.ipie.common.client.request;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpMethod;

/**
 * One outbound inter-service call: which service, which HTTP method/path, and (for state-changing
 * methods) the idempotency key every {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} must
 * carry (Development_Environment_Configuration.md, Section 15, Idempotency). Deliberately generic
 * - one request/response shape covers every communication pattern (fetch, create, replace,
 * partial update, delete) rather than a bespoke method per verb.
 */
public final class ServiceRequest {

    private static final Set<HttpMethod> REQUIRES_IDEMPOTENCY_KEY =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final String serviceName;
    private final HttpMethod method;
    private final String path;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final Object body;
    private final String idempotencyKey;

    private ServiceRequest(Builder builder) {
        this.serviceName = builder.serviceName;
        this.method = builder.method;
        this.path = builder.path;
        this.queryParams = Map.copyOf(builder.queryParams);
        this.headers = Map.copyOf(builder.headers);
        this.body = builder.body;
        this.idempotencyKey = builder.idempotencyKey;
    }

    public static Builder get(String serviceName, String path) {
        return new Builder(serviceName, HttpMethod.GET, path);
    }

    public static Builder post(String serviceName, String path) {
        return new Builder(serviceName, HttpMethod.POST, path);
    }

    public static Builder put(String serviceName, String path) {
        return new Builder(serviceName, HttpMethod.PUT, path);
    }

    public static Builder patch(String serviceName, String path) {
        return new Builder(serviceName, HttpMethod.PATCH, path);
    }

    public static Builder delete(String serviceName, String path) {
        return new Builder(serviceName, HttpMethod.DELETE, path);
    }

    /** Escape hatch for a method not covered by the named factories above. */
    public static Builder of(String serviceName, HttpMethod method, String path) {
        return new Builder(serviceName, method, path);
    }

    public String serviceName() {
        return serviceName;
    }

    public HttpMethod method() {
        return method;
    }

    public String path() {
        return path;
    }

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Object body() {
        return body;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public static final class Builder {

        private final String serviceName;
        private final HttpMethod method;
        private final String path;
        private final Map<String, String> queryParams = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Object body;
        private String idempotencyKey;

        private Builder(String serviceName, HttpMethod method, String path) {
            this.serviceName = serviceName;
            this.method = method;
            this.path = path;
        }

        public Builder queryParam(String name, String value) {
            queryParams.put(name, value);
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder body(Object body) {
            this.body = body;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public ServiceRequest build() {
            if (REQUIRES_IDEMPOTENCY_KEY.contains(method) && (idempotencyKey == null || idempotencyKey.isBlank())) {
                throw new IllegalArgumentException(
                        "An Idempotency-Key is required for " + method + " calls to service '" + serviceName
                                + "' (Development_Environment_Configuration.md, Section 15, Idempotency) - "
                                + "call .idempotencyKey(...) before build(), or use a caller-supplied key when "
                                + "relaying an inbound request that already carries one");
            }
            return new ServiceRequest(this);
        }
    }
}
