package in.gov.ipie.common.security.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.security.rate-limit.rules[]} - which paths {@link RateLimitFilter} throttles, and
 * at what threshold. A list, not a single blanket limit, since different public endpoints warrant
 * different thresholds (e.g. a registration endpoint tolerates far fewer attempts per minute than
 * a read-only lookup). Empty by default - like {@code HmacSigningProperties.protectedPaths}, rate
 * limiting is opt-in per service and per path, not a blanket requirement.
 */
@ConfigurationProperties(prefix = "ipie.security.rate-limit")
public class RateLimitProperties {

    private List<Rule> rules = new ArrayList<>();

    public List<Rule> getRules() {
        return new ArrayList<>(rules);
    }

    public void setRules(List<Rule> rules) {
        this.rules = new ArrayList<>(rules);
    }

    /** One throttled path pattern and its threshold. */
    public static class Rule {

        /** Ant-style path pattern this rule applies to. */
        private String pattern;

        /** Maximum hits allowed within {@link #window} before a request is rejected with {@code 429}. */
        private int limit;

        /** How long a hit count accumulates before resetting. */
        private Duration window = Duration.ofMinutes(1);

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
