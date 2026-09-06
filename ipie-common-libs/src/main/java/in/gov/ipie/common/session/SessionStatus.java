package in.gov.ipie.common.session;

/**
 * The frontend-facing snapshot of a user's idle session, returned by
 * {@code GET /api/v1/session/status} (and by {@code extend}/after a fresh {@code touch}). The
 * frontend is expected to show its "stay logged in?" prompt once
 * {@code remainingSeconds <= warningThresholdSeconds}, and treat {@code active=false} as an
 * already-expired session (prompt the user to log in again rather than offering to extend).
 */
public record SessionStatus(boolean active, long remainingSeconds, long warningThresholdSeconds) {
}
