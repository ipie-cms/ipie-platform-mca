package in.gov.ipie.common.client.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.client.*} - how a target service name (the same name passed to
 * {@code ServiceRequest.get(serviceName, ...)}) resolves to a base URL.
 *
 * <p>There is no service discovery/registry in the platform yet (no Eureka, no service mesh) -
 * this is a deliberate, documented placeholder for one: {@link #baseUrlPattern} covers the
 * common case (Docker Compose/Kubernetes DNS-style service names, e.g. {@code http://claims-service:8080})
 * with zero per-service configuration, and {@link #services} lets one service override just the
 * target(s) that do not fit that pattern (a different port, a different scheme, an external
 * hostname). If the platform later adopts real service discovery, only this class's
 * implementation changes - {@code InterServiceClient} callers are unaffected.
 */
@ConfigurationProperties(prefix = "ipie.client")
public class InterServiceClientProperties {

    /**
     * Applied when a service name has no explicit entry in {@link #services}. {@code {service}}
     * is replaced with the target service name. Default assumes every microservice listens on
     * 8080 and is reachable by its Docker Compose/Kubernetes service name - override per
     * deployment if that assumption does not hold.
     */
    private String baseUrlPattern = "http://{service}:8080";

    /** Per-service-name base URL override, e.g. {@code ipie.client.services.claims-service=https://claims.internal:8443}. */
    private Map<String, String> services = new LinkedHashMap<>();

    public String getBaseUrlPattern() {
        return baseUrlPattern;
    }

    public void setBaseUrlPattern(String baseUrlPattern) {
        this.baseUrlPattern = baseUrlPattern;
    }

    public Map<String, String> getServices() {
        return new LinkedHashMap<>(services);
    }

    public void setServices(Map<String, String> services) {
        this.services = new LinkedHashMap<>(services);
    }

    /** Resolves {@code serviceName} to a base URL - an explicit entry in {@link #services}, or {@link #baseUrlPattern} otherwise. */
    public String resolveBaseUrl(String serviceName) {
        String explicit = services.get(serviceName);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return baseUrlPattern.replace("{service}", serviceName);
    }
}
