package in.gov.ipie.common.utils.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Id generation helpers so services don't reinvent id/reference-number formats independently. */
public final class IdGenerator {

    private static final DateTimeFormatter DATE_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no O/0/I/1 - avoids ambiguity
    private static final int SUFFIX_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int VERSION_7 = 0x7000;
    private static final long VARIANT_RFC = 0x8000000000000000L;

    private IdGenerator() {
    }

    /**
     * A time-ordered UUID (RFC 9562 version 7), for anything that becomes a primary key.
     *
     * <p><b>Why not {@code UUID.randomUUID()}.</b> A version-4 UUID is uniformly random, so
     * consecutive inserts land at random points in the primary-key index. At a few thousand rows
     * that costs nothing. At the volumes this platform is sized for - a creditor per case, millions
     * of them - it costs three things at once: every insert dirties a different index page, so the
     * write working set is the whole index rather than its right-hand edge; pages split repeatedly
     * and leave the index larger and emptier than the data warrants; and the cache fills with index
     * pages nothing is about to read again. A version-7 UUID carries a 48-bit millisecond timestamp
     * in its high bits, so inserts append in roughly chronological order like a sequence while
     * staying a UUID - same column type, same width.
     *
     * <p><b>What must NOT use this.</b> The ordering that makes a v7 good for an index makes it
     * predictable, and predictability is the entire value of a nonce or an emailed token. Anyone who
     * sees one value can guess the neighbourhood of the next. HMAC nonces and verification tokens
     * therefore keep {@code UUID.randomUUID()} and say so at the call site: the choice is between
     * "an index likes this" and "an attacker cannot guess this", and only one applies per use.
     *
     * <p>Postgres 18 offers {@code uuidv7()} natively, which migrations use for rows the database
     * generates; this is the same thing for rows the application assigns before insert.
     */
    public static UUID newUuid() {
        long millis = System.currentTimeMillis();
        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);

        // 48 bits of timestamp, then 4 bits of version, then 12 bits of entropy (rand_a).
        //
        // rand_a is TWELVE bits, not sixteen: the version nibble sits at bits 12-15, so the first
        // entropy byte is masked to its low nibble. Taking the whole byte overlaps the version and
        // ORs it upward - 0x7 | 0x8 is 0xF - which produced a version-15 UUID roughly half the time
        // while still looking like a plausible id in every log and column.
        long high = (millis << 16)
                | VERSION_7
                | ((entropy[0] & 0x0FL) << 8)
                | (entropy[1] & 0xFFL);

        // Two bits of variant, then 62 bits of entropy, taken from the remaining bytes so one draw
        // from SecureRandom covers the whole value.
        long low = 0;
        for (int i = 2; i < entropy.length; i++) {
            low = (low << 8) | (entropy[i] & 0xFFL);
        }
        low = (low & 0x3FFFFFFFFFFFFFFFL) | VARIANT_RFC;

        return new UUID(high, low);
    }

    /** {@link #newUuid()} in string form, for the places that carry an id as text. */
    public static String newId() {
        return newUuid().toString();
    }

    /** A short, human-readable business reference such as {@code "USR-20260705-4F2A"}. */
    public static String newReferenceNumber(String prefix) {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return prefix + "-" + DATE_COMPACT.format(Instant.now()) + "-" + suffix;
    }
}
