package in.gov.ipie.common.session.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

    @Test
    void put_thenExpiresAt_returnsAnExpiryAroundNowPlusTtl() {
        store.put("user-1", Duration.ofMinutes(15));

        Instant expiresAt = store.expiresAt("user-1").orElseThrow();

        assertThat(expiresAt).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        assertThat(expiresAt).isBefore(Instant.now().plus(Duration.ofMinutes(16)));
    }

    @Test
    void expiresAt_forAnUnknownUser_isEmpty() {
        assertThat(store.expiresAt("never-touched")).isEmpty();
    }

    @Test
    void anAlreadyElapsedEntry_isTreatedAsAbsent() {
        store.put("user-1", Duration.ofMillis(-1));

        assertThat(store.expiresAt("user-1")).isEmpty();
    }

    @Test
    void remove_endsTheSessionImmediately() {
        store.put("user-1", Duration.ofMinutes(15));

        store.remove("user-1");

        assertThat(store.expiresAt("user-1")).isEmpty();
    }

    @Test
    void usersAreIndependent() {
        store.put("user-1", Duration.ofMinutes(15));

        assertThat(store.expiresAt("user-1")).isPresent();
        assertThat(store.expiresAt("user-2")).isEmpty();
    }
}
