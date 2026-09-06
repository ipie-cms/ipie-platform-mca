package in.gov.ipie.common.core.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CursorPageResultTest {

    @Test
    void of_populatesContentCursorAndHasMore() {
        CursorPageResult<String> result = CursorPageResult.of(List.of("a", "b"), "next-token", true);

        assertThat(result.content()).containsExactly("a", "b");
        assertThat(result.nextCursor()).isEqualTo("next-token");
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void lastPage_hasNoNextCursor() {
        CursorPageResult<String> result = CursorPageResult.of(List.of("a"), null, false);

        assertThat(result.nextCursor()).isNull();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void content_isDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        CursorPageResult<String> result = CursorPageResult.of(mutable, null, false);
        mutable.add("b");

        assertThat(result.content()).containsExactly("a");
    }
}
