package in.gov.ipie.common.security.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Known-answer tests, and they are the point of this class.
 *
 * <p>These hashes are already in production databases: every unexpired OTP, verification token and
 * credential-setup token is stored as one. Moving the implementation into the platform must not
 * change a single byte of output, and a change here would not fail loudly - it would quietly stop
 * matching every secret already issued, so the symptom is "nobody can complete a registration" with
 * a stack trace pointing nowhere near the cause.
 *
 * <p>The expected values below were computed independently of this code (HMAC-SHA256 and SHA-256 over
 * UTF-8, lower-case hex), so they check the implementation rather than agreeing with it.
 */
class SecretHasherCompatibilityTest {

    @Test
    void thePepperedHasherStillProducesTheDigestsAlreadyStored() {
        SecretHasher hasher = new PepperedSecretHasher("local-dev-registration-pepper");

        assertThat(hasher.hash("123456"))
                .isEqualTo("4888c6dd3331472a32b72a5997e2623bf4b0f765f53ef406e8b1ae58f06b0100");
        assertThat(new PepperedSecretHasher("another-pepper").hash("verification-token-abc"))
                .isEqualTo("c0f1d1871a477c29dd1008f30c504f7adb639ab2249fe2af361f2e0b2979beae");
    }

    @Test
    void theDigestHasherStillProducesTheFingerprintsAlreadyStored() {
        assertThat(new DigestSecretHasher().hash("a-known-setup-token"))
                .isEqualTo("57ef6e7e199a1aab577be1d2d24ae75cc8578488d698acb6b035e65c30520c4f");
    }

    @Test
    void aDifferentPepperProducesADifferentDigestForTheSameSecret() {
        // The property the pepper exists for: holding the database is not enough to reproduce a
        // digest of a six-digit code.
        assertThat(new PepperedSecretHasher("pepper-a").hash("123456"))
                .isNotEqualTo(new PepperedSecretHasher("pepper-b").hash("123456"));
    }

    @Test
    void aPepperedDigestIsNotADigestOfTheSameSecret() {
        // The two modes are not interchangeable; storing one and verifying with the other silently
        // matches nothing.
        assertThat(new PepperedSecretHasher("pepper-a").hash("123456"))
                .isNotEqualTo(new DigestSecretHasher().hash("123456"));
    }

    @Test
    void matchingIsTrueOnlyForTheRightSecret() {
        SecretHasher hasher = new PepperedSecretHasher("local-dev-registration-pepper");
        String stored = hasher.hash("123456");

        assertThat(hasher.matches("123456", stored)).isTrue();
        assertThat(hasher.matches("123457", stored)).isFalse();
        assertThat(hasher.matches(null, stored)).isFalse();
        assertThat(hasher.matches("123456", null)).isFalse();
    }

    @Test
    void nullHashesToNullRatherThanToTheDigestOfNothing() {
        // The registration flow hashes optional fields; a digest of the empty string stored against
        // an absent identity number would look like a real value.
        assertThat(new PepperedSecretHasher("p").hash(null)).isNull();
        assertThat(new DigestSecretHasher().hash(null)).isNull();
    }

    @Test
    void aPepperlessConstructionIsRefusedRatherThanDefaulted() {
        // Falling back to an unkeyed digest would look identical in every log and test while
        // protecting nothing.
        assertThatThrownBy(() -> new PepperedSecretHasher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no safe default");
        assertThatThrownBy(() -> new PepperedSecretHasher(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatedTokensAreUrlSafeUniqueAndLongEnough() {
        Set<String> tokens = IntStream.range(0, 500)
                .mapToObj(i -> new SecretGenerator().newToken())
                .collect(Collectors.toSet());

        assertThat(tokens).hasSize(500);
        assertThat(tokens).allSatisfy(t -> {
            // 32 bytes, Base64url, no padding - it travels in a link and must survive a query string.
            assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
        });
    }

    @Test
    void aTokenTooShortToBeUnguessableIsRefused() {
        assertThatThrownBy(() -> new SecretGenerator().newToken(8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128 bits");
    }
}
