package in.gov.ipie.common.security.hmac;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InMemoryNonceStoreTest {

    private final InMemoryNonceStore store = new InMemoryNonceStore();

    @Test
    void tryConsume_returnsTrueTheFirstTimeANonceIsSeen() {
        assertThat(store.tryConsume("nonce-1", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void tryConsume_returnsFalseForAnAlreadyConsumedNonce_evenWithADifferentTtl() {
        store.tryConsume("nonce-1", Duration.ofMinutes(5));

        assertThat(store.tryConsume("nonce-1", Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void tryConsume_treatsDifferentNoncesIndependently() {
        assertThat(store.tryConsume("nonce-1", Duration.ofMinutes(5))).isTrue();
        assertThat(store.tryConsume("nonce-2", Duration.ofMinutes(5))).isTrue();
    }
}
