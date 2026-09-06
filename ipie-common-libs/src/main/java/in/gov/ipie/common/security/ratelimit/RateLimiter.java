package in.gov.ipie.common.security.ratelimit;

import java.time.Duration;

/**
 * Fixed-window request throttling for a caller-supplied key (typically {@code path:clientIp}).
 * Deliberately a port, not a concrete Redis dependency - same "a real, shared-infrastructure-
 * backed implementation when available, a safe in-process fallback otherwise" pattern
 * {@code NonceStore}/{@code IdempotencyStore} already establish for this platform's other
 * Redis-optional cross-cutting concerns.
 */
public interface RateLimiter {

    /**
     * Registers one hit against {@code key}'s current window.
     *
     * @return {@code true} if this hit is within {@code limit} for the window (the call may
     *     proceed); {@code false} once the window's hit count exceeds {@code limit} (the caller
     *     must be rejected, e.g. with {@code 429 Too Many Requests})
     */
    boolean tryAcquire(String key, int limit, Duration window);
}
