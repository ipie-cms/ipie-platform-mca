package in.gov.ipie.common.client.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreDetails;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import in.gov.ipie.common.client.config.InterServiceMtlsProperties;

/**
 * Turns {@link InterServiceMtlsProperties} into the {@link SslBundle} that
 * {@code InterServiceClientAutoConfiguration} hands to the inter-service HTTP client's request
 * factory. See {@link InterServiceMtlsProperties} for why application-level mTLS exists here at
 * all and how it relates to the HMAC signing that already ships.
 *
 * <p>Kept as a separate, dependency-free class rather than inlined into the auto-configuration for
 * the reason {@code MASTER_CODE_STANDARDS.md}, Section 13.3 item 3 gives: the mechanism is proven
 * by this library's own tests, not by standing up a service. Everything here is a pure function of
 * the properties plus a {@link ResourceLoader}, so it is testable without a Spring context.
 *
 * <p>Nothing in here builds an {@code SSLContext} eagerly - {@link SslBundle#getManagers()} is
 * lazy. What it <em>does</em> do eagerly is read and parse both stores, which is the point: a
 * wrong path, a wrong password or a missing alias is a deployment mistake that must surface at
 * startup, next to the property that caused it, and not as an unexplained handshake failure on the
 * first inter-service call at 3am.
 */
public final class InterServiceMtlsSslBundles {

    private InterServiceMtlsSslBundles() {
    }

    /**
     * @throws IllegalStateException when the configuration cannot produce a usable mTLS identity -
     *     always with the offending property named, because the caller of this method is Spring's
     *     bean factory and the only thing an operator will see is this message
     */
    public static SslBundle from(InterServiceMtlsProperties properties, ResourceLoader resourceLoader) {
        if (!StringUtils.hasText(properties.getKeyStore())) {
            // Deliberately fatal rather than "fall back to one-way TLS". A client that trusts its
            // peers but presents no certificate of its own is not doing mutual TLS, and the
            // difference is invisible from the calling side - the call succeeds, the peer just
            // never learned who called. Failing here is the only place that distinction is
            // still cheap to notice.
            throw new IllegalStateException(
                    "ipie.client.security.mtls.enabled=true requires ipie.client.security.mtls.key-store to point at this "
                            + "service's own identity keystore (its private key and certificate). Without one the client "
                            + "would negotiate ordinary one-way TLS and no peer could authenticate it - set the keystore, "
                            + "or set ipie.client.security.mtls.enabled=false.");
        }

        JksSslStoreDetails keyStoreDetails = new JksSslStoreDetails(
                properties.getKeyStoreType(), null, properties.getKeyStore(), properties.getKeyStorePassword());
        JksSslStoreDetails trustStoreDetails = StringUtils.hasText(properties.getTrustStore())
                ? new JksSslStoreDetails(
                        properties.getTrustStoreType(), null, properties.getTrustStore(), properties.getTrustStorePassword())
                // null, not an empty details object: JksSslStoreBundle reads that as "no trust
                // store configured", which makes the JDK fall back to its own default trust
                // anchors. That is a documented, if rarely correct, choice - see the trustStore
                // property's Javadoc.
                : null;

        SslStoreBundle stores = load(properties, keyStoreDetails, trustStoreDetails, resourceLoader);

        if (properties.isIncludeSystemCaCertificates() && stores.getTrustStore() != null) {
            stores = SslStoreBundle.of(stores.getKeyStore(), stores.getKeyStorePassword(), mergeSystemCaCertificates(stores));
        }

        SslBundleKey key = SslBundleKey.of(
                StringUtils.hasText(properties.getKeyPassword()) ? properties.getKeyPassword() : properties.getKeyStorePassword(),
                properties.getKeyAlias());
        // Fails now, with the alias in the message, instead of presenting whichever certificate
        // the key manager picks on its own and leaving the far side to reject it.
        key.assertContainsAlias(stores.getKeyStore());

        return SslBundle.of(stores, key, options(properties), properties.getProtocol());
    }

    /**
     * The one place a store-reading failure is translated. {@code JksSslStoreBundle}'s own
     * exception says "could not load store from '...'" and nothing about which of the two stores
     * or which property produced the path, which is not enough to act on when both are configured.
     *
     * <p>Both stores are read here, inside the {@code try}, rather than left to whoever first
     * needs them. {@code JksSslStoreBundle} loads lazily (Spring Boot 3.5), so constructing it
     * cannot fail - which would push a wrong path or a wrong password out of this method and into
     * the first inter-service call, unrecognisable and hours away from the deployment that caused
     * it. Touching them now is what turns that into a startup failure.
     */
    private static SslStoreBundle load(
            InterServiceMtlsProperties properties,
            JksSslStoreDetails keyStoreDetails,
            JksSslStoreDetails trustStoreDetails,
            ResourceLoader resourceLoader) {
        try {
            SslStoreBundle stores = new JksSslStoreBundle(keyStoreDetails, trustStoreDetails, resourceLoader);
            stores.getKeyStore();
            stores.getTrustStore();
            return stores;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Could not load the inter-service mTLS key material. Check ipie.client.security.mtls.key-store ('"
                            + properties.getKeyStore() + "', type " + properties.getKeyStoreType() + ") and "
                            + "ipie.client.security.mtls.trust-store ('" + properties.getTrustStore() + "', type "
                            + properties.getTrustStoreType() + ") - a wrong path, a wrong store type or a wrong password "
                            + "all surface here.", ex);
        }
    }

    /**
     * Copies the JVM's default trust anchors into a new store alongside the configured ones.
     *
     * <p>Reads them back out of a default-initialised {@link TrustManagerFactory} rather than
     * opening {@code $JAVA_HOME/lib/security/cacerts} directly: the file's location, format and
     * password vary by distribution and by container base image, and several JDK builds now serve
     * the anchors from somewhere else entirely. The trust manager is the only portable view of
     * "what this JVM trusts by default".
     */
    private static KeyStore mergeSystemCaCertificates(SslStoreBundle configured) {
        try {
            KeyStore merged = KeyStore.getInstance(KeyStore.getDefaultType());
            merged.load(null, null);

            KeyStore configuredTrustStore = configured.getTrustStore();
            for (String alias : Collections.list(configuredTrustStore.aliases())) {
                Certificate certificate = configuredTrustStore.getCertificate(alias);
                if (certificate != null) {
                    merged.setCertificateEntry(alias, certificate);
                }
            }

            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            int index = 0;
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (!(trustManager instanceof X509TrustManager x509TrustManager)) {
                    continue;
                }
                for (X509Certificate issuer : x509TrustManager.getAcceptedIssuers()) {
                    // Synthetic aliases: the system anchors carry no alias once they have been
                    // reduced to a certificate array, and a collision with a configured alias
                    // would silently drop one of the two.
                    merged.setCertificateEntry("ipie-system-ca-" + index++, issuer);
                }
            }
            return merged;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException(
                    "Could not merge the JVM's default trust anchors into the inter-service mTLS trust store "
                            + "(ipie.client.security.mtls.include-system-ca-certificates=true).", ex);
        }
    }

    /**
     * {@code null} rather than an empty array is what {@link SslOptions} reads as "leave the JDK's
     * own selection alone" - an empty array would be taken literally and enable nothing, which
     * fails every handshake.
     */
    private static SslOptions options(InterServiceMtlsProperties properties) {
        return SslOptions.of(toArrayOrNull(properties.getCiphers()), toArrayOrNull(properties.getEnabledProtocols()));
    }

    private static String[] toArrayOrNull(List<String> values) {
        return values.isEmpty() ? null : values.toArray(String[]::new);
    }
}
