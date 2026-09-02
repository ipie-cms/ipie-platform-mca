package in.gov.ipie.common.core.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CursorPageRequestTest {

    @Test
    void firstPage_hasNoCursor() {
        CursorPageRequest request = CursorPageRequest.firstPage(20);

        assertThat(request.cursor()).isNull();
        assertThat(request.decodeCursor()).isEmpty();
    }

    @Test
    void decodeCursor_decodesAPreviouslyEncodedCursor() {
        Cursor original = new Cursor(Instant.parse("2026-07-14T10:15:30.123Z"), UUID.randomUUID());
        CursorPageRequest request = new CursorPageRequest(original.encode(), 20);

        assertThat(request.decodeCursor()).contains(original);
    }

    @Test
    void constructor_rejectsSizeBelowOne() {
        assertThatThrownBy(() -> new CursorPageRequest(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsSizeAboveMax() {
        assertThatThrownBy(() -> new CursorPageRequest(null, CursorPageRequest.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsMaxPageSize() {
        CursorPageRequest request = new CursorPageRequest(null, CursorPageRequest.MAX_PAGE_SIZE);

        assertThat(request.size()).isEqualTo(CursorPageRequest.MAX_PAGE_SIZE);
    }
}
