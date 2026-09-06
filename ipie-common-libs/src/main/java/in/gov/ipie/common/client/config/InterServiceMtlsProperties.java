package in.gov.ipie.common.client.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.client.security.mtls.*} - mutual TLS for outbound {@code InterServiceClient} calls:
 * this service presents its own X.509 client certificate on the TLS handshake, and validates the
 * peer's certificate against an explicitly configured trust store rather than whatever the JVM
 * happens to trust.
 *
 * <h2>Why this exists at all, given the platform's documented position</h2>
 *
 * <p>{@code Infra_Environment_Configuration.md}, Section 12 records the intended end state: mTLS
 * terminated transparently in a service-mesh sidecar (Istio/Linkerd), with no application code
 * involved. That remains the right destination and this class is not an attempt to replace it -
 * a mesh also gets you certificate rotation, SPIFFE workload identity and policy enforcement that
 * a library cannot. But a mesh is a Kubernetes capability, and the platform runs on Docker
 * Compose today (see {@code docker-compose.yml}/{@code docker-compose.services.yml}, where every
 * hop is plain {@code http://}). "Wait for the mesh" therefore means "no transport authentication
 * at all in the meantime", which is not an acceptable answer for the payment-style flows this was
 * requested for.
 *
 * <p>So this is the application-layer half, deliberately shaped to be thrown away: it is
 * <b>off by default</b>, it touches only the {@code InterServiceClient} transport, and the day a
 * mesh is adopted the correct migration is to set {@code enabled=false} and delete the
 * keystore mounts - no call site, no {@code ServiceRequest}, and no business code changes,
 * because none of them ever see this.
 *
 * <h2>How this relates to the HMAC signing that already exists</h2>
 *
 * <p>They answer different questions and neither subsumes the other:
 *
 * <ul>
 *   <li><b>mTLS is transport identity.</b> It proves, at connection time, that the process on the
 *       other end of this socket holds the private key for a certificate our trust store accepts.
 *       It protects the whole conversation (confidentiality + integrity) and it is what stops a
 *       rogue container inside the network from reaching a peer at all. It says nothing about any
 *       individual message once the tunnel is up.</li>
 *   <li><b>HMAC signing ({@code ipie.client.security.hmac-signing-enabled}) is message
 *       authentication.</b> The signature covers method, path, timestamp, nonce and body, and it
 *       survives the transport: it is still verifiable after TLS has been terminated at a load
 *       balancer, an ingress, or a mesh sidecar - which is precisely where mTLS stops. It also
 *       gives replay protection (the nonce store), which TLS does not, and it leaves a
 *       per-request artefact an auditor can re-verify after the fact.</li>
 * </ul>
 *
 * <p><b>Enabling mTLS therefore does not make HMAC signing redundant</b>, and the two must not be
 * treated as alternatives. Concretely: in any topology where TLS terminates somewhere other than
 * the destination process - which is every topology this platform is heading for, mesh included -
 * mTLS proves nothing about the request that finally arrives at the application. The signing
 * requirement in {@code Development_Environment_Configuration.md}, Section 15 (CIRP/Liquidation/
 * PGIRP-style state transitions) is a message-level, audit-facing requirement and stays exactly as
 * it is. The honest simplification available once mTLS is on is narrower: HMAC signing can stay
 * confined to those high-sensitivity paths instead of being reached for as a general-purpose
 * "prove the caller is a service" mechanism, because mTLS now answers that question better.
 *
 * <h2>Server side</h2>
 *
 * <p>This class configures the <em>client</em> half only. Requiring a client certificate on the
 * inbound side is Spring Boot's own {@code server.ssl.*} - specifically
 * {@code server.ssl.client-auth=need} - and this library deliberately does not wrap it
 * (MASTER_CODE_STANDARDS.md, Section 13.1: use the mechanism that exists, do not rebuild it).
 * See {@code ipie-common-libs/README.md}, "Inter-service mTLS", for the paired server-side block.
 */
@ConfigurationProperties(prefix = "ipie.client.security.mtls")
public class InterServiceMtlsProperties {

    /**
     * Off by default, and it must stay that way. Every service in the platform currently talks
     * plain {@code http://} to every other service; flipping this default to {@code true} would
     * fail startup in every environment at once (no keystore is mounted anywhere), including
     * local development. Enabling is a per-deployment decision that goes together with mounting
     * certificates, switching {@code ipie.client.base-url-pattern} to {@code https://}, and
     * turning on {@code server.ssl.client-auth} at the receiving end.
     */
    private boolean enabled = false;

    /**
     * Spring {@code Resource} location of this service's own identity keystore - the private key
     * and certificate it presents to peers, e.g. {@code file:/etc/ipie/certs/user-service.p12} or
     * {@code classpath:certs/user-service.p12}. Required when {@link #enabled} is {@code true}:
     * a client with a trust store but no keystore is doing ordinary one-way TLS, not mTLS, and
     * silently degrading to that is exactly the failure this control exists to prevent - so it is
     * rejected at startup instead.
     */
    private String keyStore;

    /** Password protecting {@link #keyStore}. Comes from the environment's secrets manager, never from a checked-in file. */
    private String keyStorePassword;

    /** {@code PKCS12} (the default and the portable choice) or {@code JKS} for a legacy store. */
    private String keyStoreType = "PKCS12";

    /**
     * Which entry in {@link #keyStore} is this service's identity. Optional, but worth setting
     * explicitly for any store that holds more than one key: left unset, the JDK's key manager
     * picks an alias itself, and "the wrong certificate was presented" is a failure that surfaces
     * as an opaque handshake error on the far side. When set, the alias is verified to exist at
     * startup rather than at first call.
     */
    private String keyAlias;

    /** Password for the {@link #keyAlias} entry, when it differs from {@link #keyStorePassword}. */
    private String keyPassword;

    /**
     * Spring {@code Resource} location of the trust store holding the CA(s) that issue peer
     * certificates - normally the platform's own internal CA, and nothing else.
     *
     * <p>Optional. Left unset, the JVM's default trust store (the public CA bundle) is used, which
     * for internal service-to-service traffic is almost always wrong: it means any certificate
     * chaining to any public CA is accepted. Set this to the internal CA for a genuinely closed
     * trust boundary.
     */
    private String trustStore;

    /** Password protecting {@link #trustStore}. */
    private String trustStorePassword;

    /** {@code PKCS12} (the default) or {@code JKS}. */
    private String trustStoreType = "PKCS12";

    /**
     * Whether the JVM's default trust anchors (the public CA bundle) are merged into
     * {@link #trustStore} in addition to its own entries.
     *
     * <p>Defaults to {@code false}, i.e. the strict reading: once you have named an internal CA,
     * that CA and nothing else may issue a certificate this service will accept from a peer.
     * Merging the public bundle back in widens that to "any public CA as well", which is a real
     * weakening even though it is a mild one in practice (public CAs will not issue for internal
     * DNS names).
     *
     * <p>Only meaningful when {@link #trustStore} is set - with no trust store configured the JVM
     * default is already in use. Set it {@code true} for the transitional case where the same
     * service also has to reach a public HTTPS endpoint through a client that shares this trust
     * material.
     */
    private boolean includeSystemCaCertificates = false;

    /** Handshake protocol family. {@code TLS} negotiates the best mutually supported version; pin {@link #enabledProtocols} instead. */
    private String protocol = "TLS";

    /**
     * TLS versions permitted on the handshake. Defaults to 1.3 and 1.2 only - everything older is
     * broken or deprecated, and leaving the JDK default in place has historically meant quietly
     * negotiating down. 1.2 is retained because a peer or a terminating proxy may not offer 1.3
     * yet; drop it once every hop is confirmed on 1.3.
     */
    private List<String> enabledProtocols = new ArrayList<>(List.of("TLSv1.3", "TLSv1.2"));

    /**
     * Cipher suites permitted, or empty for the JDK's own default selection. Empty is the right
     * default: the JDK's ordering already prefers AEAD suites and is updated with the runtime,
     * whereas a hard-coded list here ages badly and turns into a compliance liability. Pin it only
     * when an external standard demands a specific set.
     */
    private List<String> ciphers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public void setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    public boolean isIncludeSystemCaCertificates() {
        return includeSystemCaCertificates;
    }

    public void setIncludeSystemCaCertificates(boolean includeSystemCaCertificates) {
        this.includeSystemCaCertificates = includeSystemCaCertificates;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /** Defensive copy, the same reason {@code InterServiceClientProperties#getServices()} makes one. */
    public List<String> getEnabledProtocols() {
        return new ArrayList<>(enabledProtocols);
    }

    public void setEnabledProtocols(List<String> enabledProtocols) {
        this.enabledProtocols = new ArrayList<>(enabledProtocols);
    }

    /** Defensive copy, see {@link #getEnabledProtocols()}. */
    public List<String> getCiphers() {
        return new ArrayList<>(ciphers);
    }

    public void setCiphers(List<String> ciphers) {
        this.ciphers = new ArrayList<>(ciphers);
    }
}
