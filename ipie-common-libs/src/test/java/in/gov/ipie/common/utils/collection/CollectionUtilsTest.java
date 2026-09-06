package in.gov.ipie.common.utils.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectionUtilsTest {

    @Test
    void treatsNullAndEmptyCollectionsAsEmpty() {
        assertThat(CollectionUtils.isEmpty((List<?>) null)).isTrue();
        assertThat(CollectionUtils.isEmpty(List.of())).isTrue();
        assertThat(CollectionUtils.isEmpty(List.of("a"))).isFalse();
        assertThat(CollectionUtils.isNotEmpty(List.of("a"))).isTrue();
    }

    @Test
    void treatsNullAndEmptyMapsAsEmpty() {
        assertThat(CollectionUtils.isEmpty((Map<?, ?>) null)).isTrue();
        assertThat(CollectionUtils.isEmpty(Map.of())).isTrue();
        assertThat(CollectionUtils.isNotEmpty(Map.of("k", "v"))).isTrue();
    }

    @Test
    void nullToEmptyReturnsEmptyListForNull() {
        assertThat(CollectionUtils.nullToEmpty(null)).isEmpty();
        assertThat(CollectionUtils.nullToEmpty(List.of("a"))).containsExactly("a");
    }

    @Test
    void partitionsIntoChunksOfRequestedSize() {
        List<List<Integer>> chunks = CollectionUtils.partition(List.of(1, 2, 3, 4, 5), 2);

        assertThat(chunks).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
    }

    @Test
    void partitionReturnsEmptyListForEmptyInput() {
        assertThat(CollectionUtils.partition(List.of(), 2)).isEmpty();
        assertThat(CollectionUtils.partition(null, 2)).isEmpty();
    }

    @Test
    void partitionRejectsNonPositiveChunkSize() {
        assertThatThrownBy(() -> CollectionUtils.partition(List.of(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
