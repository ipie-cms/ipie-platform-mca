package in.gov.ipie.common.web.paging;

import java.util.List;
import java.util.function.Function;

import in.gov.ipie.common.core.paging.PageResult;

/** The common JSON shape for a paged API response. Controllers return this, never a raw List. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> from(PageResult<T> result) {
        return new PageResponse<>(result.content(), result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    public static <D, T> PageResponse<D> from(PageResult<T> result, Function<T, D> mapper) {
        List<D> mapped = result.content().stream().map(mapper).toList();
        return new PageResponse<>(mapped, result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
