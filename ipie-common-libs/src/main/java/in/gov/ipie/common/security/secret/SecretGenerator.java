package in.gov.ipie.common.security.secret;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Issues the bearer secrets this platform hands out - credential-setup tokens and the like.
 *
 * <p>32 bytes of {@link SecureRandom}, URL-safe Base64 without padding: it travels in a link, so it
 * must survive a query string untouched, and 256 bits is what lets {@link DigestSecretHasher} store
 * it without a pepper.
 *
 * <p>Deliberately not a UUID. A version-7 id encodes its own creation time, which narrows a guess
 * from a neighbouring token; a bearer secret must be unguessable rather than ordered.
 */
public final class SecretGenerator {

    private static final int DEFAULT_TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** A new token with the platform default of 256 bits. */
    public String newToken() {
        return newToken(DEFAULT_TOKEN_BYTES);
    }

    public String newToken(int bytes) {
        if (bytes < 16) {
            throw new IllegalArgumentException(
                    "A bearer token needs at least 128 bits of randomness; asked for " + (bytes * 8));
        }
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
