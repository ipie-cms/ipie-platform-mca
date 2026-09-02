package in.gov.ipie.common.session.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Port backing one user's idle-session expiry timestamp. Deliberately a port, not a concrete
 * Redis dependency - {@code common-cache}/{@code common-security}'s {@code NonceStore} already
 * establish the platform pattern of "a real, shared-infrastructure-backed implementation when
 * available, a safe in-process fallback otherwise" for cross-cutting state like this. Keyed by
 * user id (one idle-session concept per user, shared across that user's concurrent devices/tabs -
 * a deliberate simplification, not a per-device session).
 */
public interface SessionStore {

    /** Creates or refreshes {@code userId}'s session, expiring {@code ttl} from now. */
    void put(String userId, Duration ttl);

    /** The session's current expiry instant, or empty if there is no active session for {@code userId}. */
    Optional<Instant> expiresAt(String userId);

    /** Ends {@code userId}'s session immediately (explicit logout, or letting a natural expiry be observed early). */
    void remove(String userId);
}
