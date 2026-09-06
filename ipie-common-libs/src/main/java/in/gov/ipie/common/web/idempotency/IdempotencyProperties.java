package in.gov.ipie.common.web.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.idempotency.*} - how long a stored {@code @Idempotent} response is replayable
 * before expiring. A retry window, not a permanent record - unlike the Postgres-backed storage
 * this replaces, both {@link InMemoryIdempotencyStore} and {@link RedisIdempotencyStore} always
 * expire an entry, so this never grows without bound.
 */
@ConfigurationProperties(prefix = "ipie.idempotency")
public class IdempotencyProperties {

    /** How long a stored response stays replayable - long enough to cover a client's own retry window. */
    private Duration ttl = Duration.ofHours(24);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
