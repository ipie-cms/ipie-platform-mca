package in.gov.ipie.common.security.secret;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 under a configured pepper, hex-encoded - for a secret small enough to enumerate.
 *
 * <p>The pepper is deliberately not stored beside the data it protects: a database read yields
 * digests that cannot be reproduced without it. There is no safe default, so construction fails
 * rather than quietly falling back to an unkeyed digest, which would look identical in every log and
 * test while protecting nothing.
 *
 * <p>The encoding is fixed - UTF-8 in, lower-case hex out - because these digests are already stored.
 * Changing either would not fail; it would silently stop matching every OTP and token already
 * issued, which is the failure mode {@code SecretHasherCompatibilityTest} exists to prevent.
 */
public final class PepperedSecretHasher implements SecretHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public PepperedSecretHasher(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "A peppered secret hasher needs a pepper and there is no safe default for a real "
                            + "deployment - the secrets it protects (OTP codes, verification tokens) are small "
                            + "enough to enumerate without one.");
        }
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String hash(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Could not hash a secret with " + ALGORITHM, e);
        }
    }

    @Override
    public boolean matches(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(plaintext).getBytes(StandardCharsets.UTF_8), storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
