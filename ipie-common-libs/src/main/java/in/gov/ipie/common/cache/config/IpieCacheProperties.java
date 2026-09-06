package in.gov.ipie.common.cache.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code ipie.cache.*} - the two knobs a service can set without writing any cache-specific
 * code: how long an entry lives by default, and a per-cache-name override for anything that needs
 * a different lifetime (e.g. a lookup table that barely changes vs. a hot, frequently-invalidated
 * read - set via {@code ipie.cache.ttls.<cache-name>}). Consumed by {@link
 * IpieCacheAutoConfiguration}; nothing else in a service should read these directly.
 */
@ConfigurationProperties(prefix = "ipie.cache")
public class IpieCacheProperties {

    private Duration defaultTtl = Duration.ofMinutes(10);
    private Map<String, Duration> ttls = new HashMap<>();

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Map<String, Duration> getTtls() {
        return new HashMap<>(ttls);
    }

    public void setTtls(Map<String, Duration> ttls) {
        this.ttls = new HashMap<>(ttls);
    }
}
