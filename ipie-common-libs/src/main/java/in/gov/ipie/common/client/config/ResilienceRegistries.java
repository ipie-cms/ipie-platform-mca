package in.gov.ipie.common.client.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * The four resilience4j registries {@code DefaultInterServiceClient} decorates every call with,
 * grouped into one constructor/method parameter instead of four separate ones - keeps both that
 * class's constructor and {@code InterServiceClientAutoConfiguration}'s wiring method under
 * Checkstyle's {@code ParameterNumber} limit, and means adding a fifth resilience type later (e.g.
 * a {@code TimeLimiterRegistry}, if this client ever grows an async variant) only touches this one
 * record, not every call site's parameter list.
 */
public record ResilienceRegistries(
        CircuitBreakerRegistry circuitBreakerRegistry,
        RetryRegistry retryRegistry,
        BulkheadRegistry bulkheadRegistry,
        RateLimiterRegistry rateLimiterRegistry) {
}
