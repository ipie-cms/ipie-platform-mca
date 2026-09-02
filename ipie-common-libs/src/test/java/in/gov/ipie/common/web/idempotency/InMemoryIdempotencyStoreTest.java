package in.gov.ipie.common.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    @Test
    void find_returnsEmpty_whenNothingStoredForThatKey() {
        assertThat(store.find("key-1")).isEmpty();
    }

    @Test
    void find_returnsTheStoredResponse_afterStore() {
        IdempotencyStore.StoredResponse response = new IdempotencyStore.StoredResponse(201, "{\"id\":\"abc\"}");

        store.store("key-1", response, Duration.ofMinutes(5));

        assertThat(store.find("key-1")).contains(response);
    }

    @Test
    void store_firstWriterWins_whenTheSameKeyIsStoredTwice() {
        IdempotencyStore.StoredResponse first = new IdempotencyStore.StoredResponse(201, "{\"id\":\"first\"}");
        IdempotencyStore.StoredResponse second = new IdempotencyStore.StoredResponse(201, "{\"id\":\"second\"}");

        store.store("key-1", first, Duration.ofMinutes(5));
        store.store("key-1", second, Duration.ofMinutes(5));

        assertThat(store.find("key-1")).contains(first);
    }

    @Test
    void find_treatsDifferentKeysIndependently() {
        store.store("key-1", new IdempotencyStore.StoredResponse(200, "{}"), Duration.ofMinutes(5));

        assertThat(store.find("key-2")).isEmpty();
    }
}
