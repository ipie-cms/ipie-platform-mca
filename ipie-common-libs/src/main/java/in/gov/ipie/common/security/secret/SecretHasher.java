package in.gov.ipie.common.security.secret;

/**
 * Stores a bearer secret so that reading the database does not yield a working one.
 *
 * <p><b>Which implementation, and why it matters more than it looks.</b> Both produce a hex
 * SHA-256-sized digest and they are not interchangeable:
 *
 * <ul>
 *   <li>{@link PepperedSecretHasher} for a secret a person could enumerate - a six-digit OTP has a
 *       search space of one million, so an unkeyed digest of it is reversible by anyone holding the
 *       database, in seconds, with no cryptographic weakness involved at all. The pepper is what
 *       makes the digest unreproducible without a value the database does not hold.
 *   <li>{@link DigestSecretHasher} for a secret this platform generated with {@link SecretGenerator}
 *       - 256 bits of randomness has no search space to enumerate, so a plain digest is enough and a
 *       pepper adds a key to manage for no gain.
 * </ul>
 *
 * <p><b>Neither of these is a password hasher.</b> A password is chosen by a human, reused across
 * sites, and must be slow to verify - Argon2id, in ipie-iam-service, which owns credentials. These
 * are fast on purpose: they are checked on a hot path against a value the platform issued minutes
 * earlier.
 *
 * <p>Held by the platform because the two services that needed it each answered the question
 * separately and correctly, with the reasoning written down nowhere - so the third service would
 * have guessed, and a wrong guess here is invisible until someone reads the database.
 */
public interface SecretHasher {

    /** The stored form of {@code plaintext}, or null when it is null. */
    String hash(String plaintext);

    /**
     * Whether {@code plaintext} hashes to {@code storedHash}, compared in constant time so the
     * comparison itself does not leak how much of a guess was right.
     */
    boolean matches(String plaintext, String storedHash);
}
