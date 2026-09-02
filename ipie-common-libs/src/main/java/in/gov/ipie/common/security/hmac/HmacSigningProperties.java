package in.gov.ipie.common.security.hmac;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.security.hmac.*} - the symmetric key(s) used to sign and verify high-sensitivity
 * inter-service calls (request signing, on top of whatever {@code Authorization} scheme is
 * already in use), and the replay-protection window. Both the signing side
 * ({@code common-client}'s {@code HmacSigningInterceptor}) and the verifying side
 * ({@code HmacSignatureVerificationFilter}) read the *same* {@link #keys} map - each key id is a
 * secret shared between exactly the services that need to sign/verify calls under it, agreed out
 * of band (the same trust-establishment step as agreeing a Keycloak client secret).
 *
 * <p>Empty {@link #protectedPaths} by default - HMAC signing verification is opt-in per service
 * and per path, for genuinely high-sensitivity regulatory actions (e.g. CIRP/Liquidation/PGIRP
 * state transitions), not a blanket requirement on every endpoint.
 */
@ConfigurationProperties(prefix = "ipie.security.hmac")
public class HmacSigningProperties {

    /** {@code keyId -> shared secret}. A signed request names which key it used via {@code X-Signing-Key-Id}. */
    private Map<String, String> keys = new LinkedHashMap<>();

    /**
     * How far a signed request's {@code X-Timestamp} may drift from this server's clock (either
     * direction) before being rejected as expired - bounds how long a captured, valid signature
     * remains replayable even before nonce tracking is considered, and is the tolerance for
     * ordinary clock skew between services.
     */
    private Duration clockSkewTolerance = Duration.ofMinutes(5);

    /**
     * How long a consumed nonce is remembered by {@code NonceStore} before it can be evicted -
     * must be at least {@link #clockSkewTolerance} (otherwise a nonce could be forgotten while its
     * timestamp is still inside the accepted skew window, defeating replay protection); defaults
     * to twice the skew tolerance for margin.
     */
    private Duration nonceTtl = Duration.ofMinutes(10);

    /** Ant-style path patterns that require a valid signature; empty means none are enforced. */
    private List<String> protectedPaths = new ArrayList<>();

    public Map<String, String> getKeys() {
        return new LinkedHashMap<>(keys);
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = new LinkedHashMap<>(keys);
    }

    public Duration getClockSkewTolerance() {
        return clockSkewTolerance;
    }

    public void setClockSkewTolerance(Duration clockSkewTolerance) {
        this.clockSkewTolerance = clockSkewTolerance;
    }

    public Duration getNonceTtl() {
        return nonceTtl;
    }

    public void setNonceTtl(Duration nonceTtl) {
        this.nonceTtl = nonceTtl;
    }

    public List<String> getProtectedPaths() {
        return new ArrayList<>(protectedPaths);
    }

    public void setProtectedPaths(List<String> protectedPaths) {
        this.protectedPaths = new ArrayList<>(protectedPaths);
    }
}
