package in.gov.ipie.common.utils.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Small null-safe collection helpers. Prefer these over ad hoc null checks per service. */
public final class CollectionUtils {

    private CollectionUtils() {
    }

    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    public static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Splits {@code collection} into consecutive chunks of at most {@code chunkSize} elements, preserving order. */
    public static <T> List<List<T>> partition(Collection<T> collection, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got " + chunkSize);
        }
        if (isEmpty(collection)) {
            return List.of();
        }
        List<List<T>> chunks = new ArrayList<>();
        List<T> current = new ArrayList<>(chunkSize);
        for (T item : collection) {
            current.add(item);
            if (current.size() == chunkSize) {
                chunks.add(current);
                current = new ArrayList<>(chunkSize);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
