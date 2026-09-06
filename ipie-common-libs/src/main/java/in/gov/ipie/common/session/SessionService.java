package in.gov.ipie.common.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import in.gov.ipie.common.session.config.SessionProperties;
import in.gov.ipie.common.session.store.SessionStore;

/**
 * The idle-session business logic - independent of the JWT's own {@code exp}
 * (see {@link SessionProperties}'s Javadoc). {@link SessionActivityFilter} calls
 * {@link #touch(String)} on every authenticated request (creating/refreshing the idle window from
 * genuine activity); {@code SessionController} exposes {@link #status(String)}/{@link #extend(String)}/
 * {@link #logout(String)} for the frontend's "stay logged in?" prompt.
 */
public class SessionService {

    private final SessionStore sessionStore;
    private final SessionProperties properties;

    public SessionService(SessionStore sessionStore, SessionProperties properties) {
        this.sessionStore = sessionStore;
        this.properties = properties;
    }

    /** Creates the session on first activity, or refreshes its idle window on every subsequent authenticated request. */
    public void touch(String userId) {
        sessionStore.put(userId, properties.getIdleTimeout());
    }

    /** The current status - {@code active=false} once the idle timeout has actually elapsed (or the session never started). */
    public SessionStatus status(String userId) {
        Optional<Instant> expiresAt = sessionStore.expiresAt(userId);
        long warningThresholdSeconds = properties.getWarningBeforeTimeout().getSeconds();
        if (expiresAt.isEmpty()) {
            return new SessionStatus(false, 0, warningThresholdSeconds);
        }
        long remainingSeconds = Math.max(0, Duration.between(Instant.now(), expiresAt.get()).getSeconds());
        return new SessionStatus(true, remainingSeconds, warningThresholdSeconds);
    }

    /** "Stay logged in" - resets the idle window to a fresh {@link SessionProperties#getExtendBy()}. */
    public SessionStatus extend(String userId) {
        sessionStore.put(userId, properties.getExtendBy());
        return status(userId);
    }

    /** Explicit logout (or the frontend observing an already-elapsed timeout) - ends the session immediately. */
    public void logout(String userId) {
        sessionStore.remove(userId);
    }
}
