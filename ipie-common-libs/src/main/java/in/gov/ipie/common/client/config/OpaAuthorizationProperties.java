package in.gov.ipie.common.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.client.security.opa.*} - service-to-service authorization via a self-hosted Open
 * Policy Agent (OPA) instance. Extends the platform's existing OPA-based user-facing ABAC pattern
 * to inter-service calls: "the Notification service may call the IAM service's read endpoints,
 * but not IAM's admin endpoints" is a policy decision, not just a network-reachability question.
 *
 * <p>Off by default, and - like {@code hmac-signing-enabled} - an additive control layered on top
 * of whichever {@code ipie.client.security.mode} authenticates the call; OPA answers "is this
 * call allowed at all", authentication answers "who is making it".
 */
@ConfigurationProperties(prefix = "ipie.client.security.opa")
public class OpaAuthorizationProperties {

    /** Off by default - enabling requires a reachable OPA instance with the expected policy loaded. */
    private boolean enabled = false;

    /** OPA's base URL, e.g. {@code http://opa:8181}. */
    private String url = "http://opa:8181";

    /** The OPA Data API path for the interservice-authorization policy's decision document. */
    private String policyPath = "/v1/data/ipie/interservice/allow";

    /**
     * Whether an unreachable/erroring OPA instance denies the call (default, {@code true}) or
     * allows it through (fail-open). Fail-closed is the correct default for a genuine
     * authorization control - the same reasoning {@code common-file-storage}'s
     * {@code FailClosedVirusScanner} already establishes for virus scanning: an unavailable
     * security control must never silently degrade into "allow everything." Only set this
     * {@code false} for a deployment that has explicitly decided availability outweighs this
     * particular control, e.g. behind another, independently-enforced authorization layer.
     */
    private boolean failClosed = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPolicyPath() {
        return policyPath;
    }

    public void setPolicyPath(String policyPath) {
        this.policyPath = policyPath;
    }

    public boolean isFailClosed() {
        return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }
}
