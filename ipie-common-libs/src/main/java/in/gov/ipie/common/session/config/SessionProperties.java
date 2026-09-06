package in.gov.ipie.common.session.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.session.*} - the platform's common idle-session timeout, independent of the JWT's
 * own {@code exp}. A logged-in user is warned before, and can choose to extend past, this idle
 * limit ("stay logged in?") - a UX/compliance control layered on top of, not instead of, the
 * token's own technical expiry (a still-valid JWT can still trigger an idle-timeout logout).
 *
 * <p>On by default ({@link #isEnabled()}) - unlike the opt-in security add-ons in
 * {@code common-client}/{@code common-security} (HMAC signing, OPA authorization), idle-session
 * management is a baseline UX/compliance expectation for every logged-in user once a service
 * depends on this module, the same "used wherever required" default-on posture
 * {@code common-observability}'s {@code CorrelationIdFilter} already has.
 */
@ConfigurationProperties(prefix = "ipie.session")
public class SessionProperties {

    private boolean enabled = true;

    /** How long a session may sit idle (no authenticated request) before it expires. */
    private Duration idleTimeout = Duration.ofMinutes(15);

    /**
     * How long before {@link #idleTimeout} elapses the frontend should be considered "in the
     * warning window" - purely advisory metadata returned by {@code GET /api/v1/session/status}
     * (this module enforces nothing extra at this threshold itself); the frontend is expected to
     * show its "stay logged in?" prompt once the remaining time drops to or below this value.
     */
    private Duration warningBeforeTimeout = Duration.ofMinutes(1);

    /**
     * How much time {@code POST /api/v1/session/extend} adds back - defaults to
     * {@link #idleTimeout} itself (a full fresh idle window), but can be set independently, e.g.
     * a shorter top-up than the original timeout.
     */
    private Duration extendBy;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getWarningBeforeTimeout() {
        return warningBeforeTimeout;
    }

    public void setWarningBeforeTimeout(Duration warningBeforeTimeout) {
        this.warningBeforeTimeout = warningBeforeTimeout;
    }

    /** Falls back to {@link #idleTimeout} when not explicitly configured. */
    public Duration getExtendBy() {
        return extendBy != null ? extendBy : idleTimeout;
    }

    public void setExtendBy(Duration extendBy) {
        this.extendBy = extendBy;
    }
}
