package in.gov.ipie.common.web.paging;

import java.util.List;
import java.util.function.Function;

import in.gov.ipie.common.core.paging.CursorPageResult;

/** The common JSON shape for a keyset-paged API response. Controllers return this, never a raw List. */
public record CursorPageResponse<T>(List<T> content, String nextCursor, boolean hasMore) {

    public CursorPageResponse {
        content = List.copyOf(content);
    }

    public static <T> CursorPageResponse<T> from(CursorPageResult<T> result) {
        return new CursorPageResponse<>(result.content(), result.nextCursor(), result.hasMore());
    }

    public static <D, T> CursorPageResponse<D> from(CursorPageResult<T> result, Function<T, D> mapper) {
        List<D> mapped = result.content().stream().map(mapper).toList();
        return new CursorPageResponse<>(mapped, result.nextCursor(), result.hasMore());
    }
}
