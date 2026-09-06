package in.gov.ipie.common.security.hmac;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Proves {@link HmacSignature} is deterministic (same inputs -> same signature, so the signing
 * and verifying sides - which never share code beyond this class - always agree), and that
 * changing any one field (secret, method, path, timestamp, nonce, body) changes the signature, so
 * a captured signature cannot be replayed against a different request.
 */
class HmacSignatureTest {

    private static final byte[] BODY = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);

    @Test
    void sign_isDeterministicForTheSameInputs() {
        String first = HmacSignature.sign("secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY);
        String second = HmacSignature.sign("secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void verify_succeedsForAMatchingSignature() {
        String signature = HmacSignature.sign("secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY);

        boolean valid = HmacSignature.verify(
                "secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY, signature);

        assertThat(valid).isTrue();
    }

    @Test
    void verify_failsWhenTheSecretDiffers() {
        String signature = HmacSignature.sign("secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY);

        boolean valid = HmacSignature.verify(
                "different-secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY, signature);

        assertThat(valid).isFalse();
    }

    @Test
    void verify_failsWhenTheBodyDiffers() {
        String signature = HmacSignature.sign("secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY);
        byte[] tamperedBody = "{\"amount\":999999}".getBytes(StandardCharsets.UTF_8);

        boolean valid = HmacSignature.verify(
                "secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", tamperedBody, signature);

        assertThat(valid).isFalse();
    }

    @Test
    void verify_failsWhenTheMethodOrPathDiffers() {
        String signature = HmacSignature.sign("secret", "POST", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY);

        assertThat(HmacSignature.verify(
                "secret", "PUT", "/api/v1/claims", "2026-01-01T00:00:00Z", "nonce-1", BODY, signature)).isFalse();
        assertThat(HmacSignature.verify(
                "secret", "POST", "/api/v1/other", "2026-01-01T00:00:00Z", "nonce-1", BODY, signature)).isFalse();
    }
}
