package in.gov.ipie.common.session.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.session.SessionService;
import in.gov.ipie.common.session.SessionStatus;

/**
 * The one {@code /api/v1/session/*} surface every service gets automatically by depending on
 * {@code common-session} - a service never writes its own version of this controller, the same
 * "one shared implementation, not one per service" precedent {@code common-web}'s
 * {@code GlobalExceptionHandler} already sets for a common-lib module registering a real Spring
 * MVC component. Backs the frontend's "stay logged in?" idle-timeout prompt:
 *
 * <ul>
 *   <li>{@code GET /api/v1/session/status} - poll this to know when to show the prompt
 *       ({@code remainingSeconds <= warningThresholdSeconds}).</li>
 *   <li>{@code POST /api/v1/session/extend} - "stay logged in" - resets the idle window.</li>
 *   <li>{@code POST /api/v1/session/logout} - explicit logout, or the frontend acting on an
 *       already-elapsed timeout with no response from the user.</li>
 * </ul>
 *
 * <p>Ordinary authenticated endpoints - no different security treatment than any other
 * controller; {@link CurrentUserProvider} identifies the caller the same way it does everywhere
 * else in the platform.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final SessionService sessionService;
    private final CurrentUserProvider currentUserProvider;

    public SessionController(SessionService sessionService, CurrentUserProvider currentUserProvider) {
        this.sessionService = sessionService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/status")
    public SessionStatus status() {
        return sessionService.status(currentUserId());
    }

    @PostMapping("/extend")
    public SessionStatus extend() {
        return sessionService.extend(currentUserId());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        sessionService.logout(currentUserId());
        return ResponseEntity.noContent().build();
    }

    private String currentUserId() {
        return currentUserProvider.currentOrThrow().userId();
    }
}
