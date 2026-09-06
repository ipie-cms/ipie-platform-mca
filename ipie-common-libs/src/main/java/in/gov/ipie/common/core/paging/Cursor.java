package in.gov.ipie.common.core.paging;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import in.gov.ipie.common.core.exception.FieldError;
import in.gov.ipie.common.core.exception.ValidationFailedException;

/**
 * Opaque keyset-pagination token: the {@code (createdAt, id)} of the last row a caller has seen.
 * Every aggregate in the platform already carries these two audit/identity columns (master
 * standards doc, 7.2), which makes the pair a universally available, stable sort/tiebreak key for
 * keyset ("seek") pagination without requiring a dedicated sequence column per entity.
 *
 * <p>Encoded as an opaque Base64 string so API consumers never depend on its internal shape -
 * only ever pass a previously-returned token back verbatim, as {@link CursorPageRequest#cursor()}.
 */
public record Cursor(Instant createdAt, UUID id) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = createdAt.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws ValidationFailedException if {@code token} is not a cursor this class produced */
    public static Cursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            List<String> parts = List.of(raw.split("\\|", 2));
            if (parts.size() != 2) {
                throw invalidCursor(token);
            }
            return new Cursor(Instant.parse(parts.get(0)), UUID.fromString(parts.get(1)));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalidCursor(token);
        }
    }

    private static ValidationFailedException invalidCursor(String token) {
        return new ValidationFailedException(
                "Invalid or tampered pagination cursor",
                List.of(new FieldError("cursor", "must be a token previously returned by this API")));
    }
}
