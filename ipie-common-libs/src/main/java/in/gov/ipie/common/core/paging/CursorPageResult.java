package in.gov.ipie.common.core.paging;

import java.util.List;

/**
 * Framework-agnostic keyset-paged result, the counterpart to {@link PageResult}. Deliberately
 * carries no {@code totalElements}/{@code totalPages} - computing those would require the same
 * {@code COUNT(*)} this paging style exists to avoid; {@link #hasMore()} is the only thing callers
 * need to know whether to request another page.
 */
public record CursorPageResult<T>(List<T> content, String nextCursor, boolean hasMore) {

    public CursorPageResult {
        content = List.copyOf(content);
    }

    public static <T> CursorPageResult<T> of(List<T> content, String nextCursor, boolean hasMore) {
        return new CursorPageResult<>(content, nextCursor, hasMore);
    }
}
