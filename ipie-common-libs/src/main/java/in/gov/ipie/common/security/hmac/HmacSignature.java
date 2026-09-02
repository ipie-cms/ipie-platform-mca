package in.gov.ipie.common.security.hmac;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one canonicalization + HMAC-SHA256 computation both the signing side
 * ({@code common-client}'s {@code HmacSigningInterceptor}) and the verifying side
 * ({@code HmacSignatureVerificationFilter}) use - defined once so the two can never drift apart
 * on what bytes actually get signed.
 *
 * <p>Canonical string: {@code METHOD\nPATH\nTIMESTAMP\nNONCE\nSHA256HEX(body)} - method and path
 * pin the signature to this exact request line (a signature computed for one endpoint must not
 * verify against another), the body hash ties the signature to this exact payload without
 * needing to include potentially large body bytes directly in the HMAC input twice over, and
 * timestamp+nonce are what {@link HmacSigningProperties} and {@code NonceStore} use together for
 * replay protection - a captured, otherwise-valid signature cannot be replayed after its
 * timestamp falls outside the accepted skew window, and cannot be replayed *within* that window
 * either once its nonce has been consumed.
 */
public final class HmacSignature {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private HmacSignature() {
    }

    /** Computes the hex-encoded HMAC-SHA256 signature over the canonical string built from these fields. */
    public static String sign(String secret, String method, String path, String timestamp, String nonce, byte[] body) {
        String canonical = canonicalString(method, path, timestamp, nonce, body);
        return hmacHex(secret, canonical);
    }

    /**
     * Constant-time comparison against a freshly computed signature - never compare signatures
     * with {@code String.equals}, which short-circuits on the first differing byte and can leak
     * timing information about how much of a forged signature was correct.
     */
    public static boolean verify(
            String secret, String method, String path, String timestamp, String nonce, byte[] body, String candidateSignature) {
        String expected = sign(secret, method, path, timestamp, nonce, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), candidateSignature.getBytes(StandardCharsets.US_ASCII));
    }

    private static String canonicalString(String method, String path, String timestamp, String nonce, byte[] body) {
        String bodyHash = sha256Hex(body == null ? new byte[0] : body);
        return method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HEX.formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is a JDK-mandatory algorithm and must always be available", e);
        }
    }

    private static String hmacHex(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is a JDK-mandatory algorithm and must always be available", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid HMAC signing key", e);
        }
    }
}
