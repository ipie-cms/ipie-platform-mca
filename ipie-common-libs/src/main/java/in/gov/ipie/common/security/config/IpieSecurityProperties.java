package in.gov.ipie.common.security.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.security.*} configuration honoured by the common resource-server auto-configuration.
 * The identity provider (issuer/audience/JWK set) is configured through the standard Spring
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties - this class only adds the
 * iPIE-specific pieces on top.
 */
@ConfigurationProperties(prefix = "ipie.security")
public class IpieSecurityProperties {

    /** Enabled by default; set {@code ipie.security.enabled=false} only for local, non-API tooling. */
    private boolean enabled = true;

    /** JWT claim holding the caller's granted permissions, e.g. Keycloak client-mapped claim. */
    private String permissionsClaim = "permissions";

    /**
     * Ant-style paths exempt from authentication (health checks, API docs, and the Prometheus
     * scrape endpoint - Prometheus is not an OAuth2 client and cannot present a JWT, the same
     * reasoning that already exempts the health/info endpoints above; see
     * Development_Environment_Configuration.md's "Local Observability Stack Configuration").
     */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"));

    /**
     * Browser origins allowed to call this API cross-origin (e.g. a local Vite dev server).
     * Empty by default - no CORS headers are sent, matching the pre-CORS behaviour, until a
     * consuming frontend's origin is added here (e.g. {@code http://localhost:5173}).
     */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPermissionsClaim() {
        return permissionsClaim;
    }

    public void setPermissionsClaim(String permissionsClaim) {
        this.permissionsClaim = permissionsClaim;
    }

    public List<String> getPublicPaths() {
        return new ArrayList<>(publicPaths);
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = new ArrayList<>(publicPaths);
    }

    public List<String> getCorsAllowedOrigins() {
        return new ArrayList<>(corsAllowedOrigins);
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = new ArrayList<>(corsAllowedOrigins);
    }
}
