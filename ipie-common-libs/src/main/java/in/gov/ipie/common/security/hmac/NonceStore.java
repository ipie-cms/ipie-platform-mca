package in.gov.ipie.common.security.hmac;

import java.time.Duration;

/**
 * Replay protection for signed inter-service calls: a nonce may be consumed exactly once within
 * its TTL. Deliberately a port, not a concrete Redis dependency - {@code common-cache}/
 * {@code FileStorage}/{@code EventPublisher} already establish the platform pattern of "a real,
 * shared-infrastructure-backed implementation when available, a safe in-process fallback
 * otherwise" for cross-cutting concerns; a future cloud-managed store (e.g. a managed Redis or a
 * dedicated idempotency-key service) slots in as a new implementation of this same port, with no
 * change to {@code HmacSignatureVerificationFilter}.
 */
public interface NonceStore {

    /**
     * Atomically checks whether {@code nonce} has already been consumed and, if not, marks it
     * consumed for {@code ttl}.
     *
     * @return {@code true} if this is the first time {@code nonce} has been seen (the call may
     *     proceed); {@code false} if it was already consumed (a replay - the call must be rejected)
     */
    boolean tryConsume(String nonce, Duration ttl);
}
