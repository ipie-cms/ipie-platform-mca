package in.gov.ipie.common.web.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.core.paging.CursorPageResult;

class CursorPageResponseTest {

    @Test
    void from_copiesContentCursorAndHasMore() {
        CursorPageResult<String> result = CursorPageResult.of(List.of("a", "b"), "next-token", true);

        CursorPageResponse<String> response = CursorPageResponse.from(result);

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.nextCursor()).isEqualTo("next-token");
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void from_withMapper_transformsEachContentElement() {
        CursorPageResult<Integer> result = CursorPageResult.of(List.of(1, 2, 3), null, false);

        CursorPageResponse<String> response = CursorPageResponse.from(result, i -> "item-" + i);

        assertThat(response.content()).containsExactly("item-1", "item-2", "item-3");
        assertThat(response.hasMore()).isFalse();
    }
}
