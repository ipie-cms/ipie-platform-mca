package in.gov.ipie.common.core.paging;

import java.util.Optional;

/**
 * Framework-agnostic keyset ("seek") paging request - the counterpart to {@link PageRequest} for
 * endpoints where deep offset paging or a {@code COUNT(*)} would not scale (master standards doc,
 * section 8: use keyset pagination for large/high-traffic listings, offset paging only for small,
 * total-count-needing screens). {@code cursor} is {@code null} for the first page; every
 * subsequent page is requested with the {@link CursorPageResult#nextCursor()} of the previous one.
 */
public record CursorPageRequest(String cursor, int size) {

    public static final int MAX_PAGE_SIZE = 200;

    public CursorPageRequest {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public static CursorPageRequest firstPage(int size) {
        return new CursorPageRequest(null, size);
    }

    /** Decodes {@link #cursor()}, or {@link Optional#empty()} for the first page. */
    public Optional<Cursor> decodeCursor() {
        return cursor == null || cursor.isBlank() ? Optional.empty() : Optional.of(Cursor.decode(cursor));
    }
}
