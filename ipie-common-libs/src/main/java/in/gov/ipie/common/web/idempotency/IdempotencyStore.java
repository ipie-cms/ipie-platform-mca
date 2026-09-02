package in.gov.ipie.common.web.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for {@code @Idempotent}-replayed responses. Deliberately a port, not a concrete Redis
 * dependency - same "a real, shared-infrastructure-backed implementation when available, a safe
 * in-process fallback otherwise" pattern {@code common-security}'s {@code NonceStore} already
 * establishes; a future cloud-managed store slots in as a new implementation of this same port,
 * with no change to {@link IdempotencyAspect}.
 */
public interface IdempotencyStore {

    /** The first call's outcome for a given {@code Idempotency-Key}, if one has been recorded. */
    Optional<StoredResponse> find(String idempotencyKey);

    /** Records this key's outcome so a repeated request with the same key can replay it. */
    void store(String idempotencyKey, StoredResponse response, Duration ttl);

    /**
     * @param status the HTTP status the first call actually returned
     * @param bodyJson the first call's response body, already Jackson-serialized - kept as a
     *     `String` here (not a typed object) since the store itself has no knowledge of the
     *     intercepted method's declared return type; {@link IdempotencyAspect} is what
     *     deserializes this back into the right shape on replay
     */
    record StoredResponse(int status, String bodyJson) {
    }
}
