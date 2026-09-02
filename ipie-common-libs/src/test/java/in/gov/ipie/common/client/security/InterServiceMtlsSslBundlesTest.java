package in.gov.ipie.common.client.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import in.gov.ipie.common.client.config.InterServiceMtlsProperties;

/**
 * Proves {@link InterServiceMtlsSslBundles} turns {@code ipie.client.security.mtls.*} into a
 * usable {@link SslBundle}, and - more importantly - that every way of getting the configuration
 * wrong fails at startup with a message naming the property responsible. A silent fallback to
 * one-way TLS, or to the JVM's default trust anchors, is the failure mode this class exists to
 * prevent, so most of what is asserted here is the refusal rather than the success.
 *
 * <p>The stores are built at test time from certificates the JVM already trusts, rather than from
 * a generated key pair or a checked-in fixture. That keeps a private key out of the repository and
 * needs no external tooling, at the cost of the key store holding trusted-certificate entries
 * instead of a private-key entry. That distinction does not matter to anything under test here:
 * this class's job is reading, validating and assembling key material, and the JDK's
 * {@code KeyManagerFactory} accepts such a store happily. What an actual handshake does with the
 * resulting context is the JDK's contract, not this library's, and is not re-proven here.
 */
class InterServiceMtlsSslBundlesTest {

    private static final String PASSWORD = "changeit";

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    @TempDir
    private Path certificateDirectory;

    private Path keyStoreFile;
    private Path trustStoreFile;

    @BeforeEach
    void writeStores() throws Exception {
        keyStoreFile = writeCertificateStore("identity.p12", 1);
        trustStoreFile = writeCertificateStore("truststore.p12", 2);
    }

    @Test
    void mtlsIsOffUntilSomebodyTurnsItOn() {
        InterServiceMtlsProperties properties = new InterServiceMtlsProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getEnabledProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
        assertThat(properties.getCiphers()).isEmpty();
    }

    @Test
    void aTrustStoreWithoutAKeyStoreIsRejectedRatherThanDegradedToOneWayTls() {
        InterServiceMtlsProperties properties = new InterServiceMtlsProperties();
        properties.setEnabled(true);
        properties.setTrustStore(trustStoreFile.toUri().toString());
        properties.setTrustStorePassword(PASSWORD);

        assertThatThrownBy(() -> InterServiceMtlsSslBundles.from(properties, resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ipie.client.security.mtls.key-store");
    }

    @Test
    void aKeyStoreAndTrustStoreProduceAUsableSslContext() {
        SslBundle bundle = InterServiceMtlsSslBundles.from(properties(), resourceLoader);

        assertThat(bundle.getProtocol()).isEqualTo("TLS");
        assertThat(bundle.getOptions().getEnabledProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
        // Empty cipher list means "leave the JDK's own selection alone", which SslOptions
        // represents as null rather than an empty array - an empty array enables nothing.
        assertThat(bundle.getOptions().getCiphers()).isNull();
        assertThat(bundle.createSslContext()).isNotNull();
    }

    @Test
    void anExplicitCipherListIsPassedThroughToTheHandshake() {
        InterServiceMtlsProperties properties = properties();
        properties.setCiphers(List.of("TLS_AES_256_GCM_SHA384"));
        properties.setEnabledProtocols(List.of("TLSv1.3"));

        SslBundle bundle = InterServiceMtlsSslBundles.from(properties, resourceLoader);

        assertThat(bundle.getOptions().getCiphers()).containsExactly("TLS_AES_256_GCM_SHA384");
        assertThat(bundle.getOptions().getEnabledProtocols()).containsExactly("TLSv1.3");
    }

    @Test
    void aConfiguredTrustStoreReplacesTheJvmDefaultsRatherThanAddingToThem() {
        SslBundle bundle = InterServiceMtlsSslBundles.from(properties(), resourceLoader);

        assertThat(trustAnchorCount(bundle)).isEqualTo(2);
    }

    @Test
    void includeSystemCaCertificatesAddsTheJvmDefaultAnchorsBackOnTop() {
        InterServiceMtlsProperties properties = properties();
        properties.setIncludeSystemCaCertificates(true);

        SslBundle bundle = InterServiceMtlsSslBundles.from(properties, resourceLoader);

        assertThat(trustAnchorCount(bundle)).isEqualTo(2 + systemCaCertificates().size());
    }

    @Test
    void aWrongKeyStorePasswordNamesBothStoresSoTheOperatorKnowsWhichToFix() {
        InterServiceMtlsProperties properties = properties();
        properties.setKeyStorePassword("not-the-password");

        assertThatThrownBy(() -> InterServiceMtlsSslBundles.from(properties, resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ipie.client.security.mtls.key-store")
                .hasMessageContaining("ipie.client.security.mtls.trust-store");
    }

    @Test
    void aMissingKeyStoreFileFailsAtStartupNotAtTheFirstCall() {
        InterServiceMtlsProperties properties = properties();
        properties.setKeyStore(certificateDirectory.resolve("nothing-here.p12").toUri().toString());

        assertThatThrownBy(() -> InterServiceMtlsSslBundles.from(properties, resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing-here.p12");
    }

    @Test
    void aKeyAliasThatIsNotInTheStoreFailsInsteadOfLettingTheJdkPickOne() {
        InterServiceMtlsProperties properties = properties();
        properties.setKeyAlias("some-other-service");

        assertThatThrownBy(() -> InterServiceMtlsSslBundles.from(properties, resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some-other-service");
    }

    private InterServiceMtlsProperties properties() {
        InterServiceMtlsProperties properties = new InterServiceMtlsProperties();
        properties.setEnabled(true);
        properties.setKeyStore(keyStoreFile.toUri().toString());
        properties.setKeyStorePassword(PASSWORD);
        properties.setTrustStore(trustStoreFile.toUri().toString());
        properties.setTrustStorePassword(PASSWORD);
        return properties;
    }

    private static int trustAnchorCount(SslBundle bundle) {
        try {
            return bundle.getStores().getTrustStore().size();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Path writeCertificateStore(String fileName, int certificateCount) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        List<X509Certificate> certificates = systemCaCertificates();
        for (int i = 0; i < certificateCount; i++) {
            store.setCertificateEntry("entry-" + i, certificates.get(i));
        }
        Path file = certificateDirectory.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD.toCharArray());
        }
        return file;
    }

    /** The same portable view of "what this JVM trusts by default" the production merge uses. */
    private static List<X509Certificate> systemCaCertificates() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            List<X509Certificate> certificates = new ArrayList<>();
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager x509TrustManager) {
                    certificates.addAll(List.of(x509TrustManager.getAcceptedIssuers()));
                }
            }
            return certificates;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
