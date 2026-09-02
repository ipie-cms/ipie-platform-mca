package in.gov.ipie.common.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.client.security.*} - how outbound inter-service calls authenticate.
 *
 * <p>Default is {@link Mode#CLIENT_CREDENTIALS}: this service authenticates as itself, using its
 * own Keycloak client (client-id/secret) via the OAuth2 client-credentials grant - the downstream
 * service sees "service A called me", not the original caller's identity. This is the
 * recommended mode for genuine service-to-service calls and requires {@code serviceAccountsEnabled}
 * on this service's Keycloak client (see {@code deploy/keycloak/realm-export.json}) plus the
 * standard {@code spring.security.oauth2.client.registration.<registration-id>.*} properties
 * (client-id/client-secret/authorization-grant-type=client_credentials/provider or token-uri).
 *
 * <p>{@link Mode#TOKEN_RELAY} forwards the inbound caller's own JWT unchanged - only appropriate
 * when the downstream call must be authorized against the *original user's* permissions (e.g. a
 * fan-out read on the user's behalf), since it exposes that user's token to the downstream
 * service. Prefer {@code CLIENT_CREDENTIALS} unless a call specifically needs this.
 *
 * <p>{@link Mode#NONE} sends no {@code Authorization} header at all - only for calls to a
 * genuinely public/unauthenticated downstream endpoint; logs a warning on startup since this is
 * rarely the right choice for an inter-service call.
 *
 * <p>{@code hmacSigningEnabled}/{@code hmacSigningKeyId} are independent of {@link #mode} above -
 * an *additive* control (HMAC request signing, on top of whichever {@code Authorization} scheme
 * {@link #mode} selects), not a fourth mode. See {@code security.HmacSigningInterceptor}'s Javadoc
 * for when to use this: genuinely high-sensitivity regulatory calls, not every inter-service call.
 */
@ConfigurationProperties(prefix = "ipie.client.security")
public class InterServiceSecurityProperties {

    public enum Mode {
        CLIENT_CREDENTIALS,
        TOKEN_RELAY,
        NONE
    }

    private Mode mode = Mode.CLIENT_CREDENTIALS;

    /**
     * The {@code spring.security.oauth2.client.registration.<id>} to use for
     * {@link Mode#CLIENT_CREDENTIALS}. Reuse this service's own resource-server Keycloak client
     * (same client-id/secret already registered for validating inbound tokens) unless a specific
     * call needs a different, narrower-scoped client.
     */
    private String registrationId = "ipie-interservice";

    /** Off by default - see this class's Javadoc before enabling. */
    private boolean hmacSigningEnabled = false;

    /**
     * Which entry of {@code ipie.security.hmac.keys} (common-security) to sign with - must name a
     * key id the target service also has configured, so it can verify the signature.
     */
    private String hmacSigningKeyId;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(String registrationId) {
        this.registrationId = registrationId;
    }

    public boolean isHmacSigningEnabled() {
        return hmacSigningEnabled;
    }

    public void setHmacSigningEnabled(boolean hmacSigningEnabled) {
        this.hmacSigningEnabled = hmacSigningEnabled;
    }

    public String getHmacSigningKeyId() {
        return hmacSigningKeyId;
    }

    public void setHmacSigningKeyId(String hmacSigningKeyId) {
        this.hmacSigningKeyId = hmacSigningKeyId;
    }
}
