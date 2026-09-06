package in.gov.ipie.common.security.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Plain SHA-256, hex-encoded - for a secret this platform generated with {@link SecretGenerator}.
 *
 * <p>No pepper, and that is a decision rather than an omission: the input is 256 bits of
 * {@code SecureRandom} output, so there is no dictionary to run and nothing for a pepper to defend
 * against. Adding one would introduce a key to distribute, rotate and lose for no gain.
 *
 * <p>Use {@link PepperedSecretHasher} instead the moment the input is something a person chose or
 * could enumerate. The two are not interchangeable and the digests are not compatible.
 */
public final class DigestSecretHasher implements SecretHasher {

    private static final String ALGORITHM = "SHA-256";

    @Override
    public String hash(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(ALGORITHM).digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is unavailable in this JVM", e);
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
