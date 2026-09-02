package in.gov.ipie.common.client.security;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import in.gov.ipie.common.security.hmac.HmacSignature;
import in.gov.ipie.common.security.hmac.HmacSignatureVerificationFilter;

/**
 * Adds the {@code X-Signature}/{@code X-Timestamp}/{@code X-Nonce}/{@code X-Signing-Key-Id}
 * headers {@code common-security}'s {@code HmacSignatureVerificationFilter} verifies - defense in
 * depth for genuinely high-sensitivity regulatory calls (e.g. CIRP/Liquidation/PGIRP-style state
 * transitions), applied *on top of* whichever {@code Authorization} interceptor
 * ({@code OAuth2ClientCredentialsInterceptor}/{@code TokenRelayInterceptor}) is already in
 * effect - this is an additive control, not a replacement mode
 * (Development_Environment_Configuration.md, Section 15 checklist: "request signing... prevents
 * tampering even within the trusted network").
 *
 * <p>Opt-in per target service via {@code ipie.client.security.hmac-signing.enabled} - only wired
 * in {@code InterServiceClientAutoConfiguration} when set, since most calls should rely on the
 * platform's standard transport/token security alone.
 *
 * <p>Every call gets a fresh {@link UUID} nonce - the pairing of this nonce with the current
 * timestamp is what {@code HmacSignatureVerificationFilter}'s {@code NonceStore} uses to detect a
 * captured request being replayed.
 */
public class HmacSigningInterceptor implements ClientHttpRequestInterceptor {

    private final String secret;
    private final String keyId;

    public HmacSigningInterceptor(String keyId, String secret) {
        this.keyId = keyId;
        this.secret = secret;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String timestamp = Instant.now().toString();
        // Random by requirement, NOT IdGenerator.newUuid(). A v7 id embeds a millisecond
        // timestamp and is therefore guessable from a neighbouring value, which is exactly what a
        // replay nonce must not be. See IdGenerator's Javadoc.
        String nonce = UUID.randomUUID().toString();
        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        String signature = HmacSignature.sign(secret, method, path, timestamp, nonce, body);

        request.getHeaders().add(HmacSignatureVerificationFilter.TIMESTAMP_HEADER, timestamp);
        request.getHeaders().add(HmacSignatureVerificationFilter.NONCE_HEADER, nonce);
        request.getHeaders().add(HmacSignatureVerificationFilter.KEY_ID_HEADER, keyId);
        request.getHeaders().add(HmacSignatureVerificationFilter.SIGNATURE_HEADER, signature);

        return execution.execute(request, body);
    }
}
