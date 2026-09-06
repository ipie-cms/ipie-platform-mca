package in.gov.ipie.common.session.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import in.gov.ipie.common.security.context.CurrentUser;
import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.session.SessionService;
import in.gov.ipie.common.session.SessionStatus;
import in.gov.ipie.common.session.config.SessionProperties;
import in.gov.ipie.common.session.store.InMemorySessionStore;

/**
 * Proves {@link SessionController} delegates to {@link SessionService} for the current caller
 * (identified via {@link CurrentUserProvider}, the same as every other controller in the
 * platform) - constructed directly rather than through a full Spring MVC context, the same
 * lightweight style used for {@code common-client}'s interceptor tests.
 */
class SessionControllerTest {

    private final SessionProperties properties = new SessionProperties();
    private final SessionService sessionService = new SessionService(new InMemorySessionStore(), properties);
    private final SessionController controller =
            new SessionController(sessionService, () -> Optional.of(new CurrentUser("user-1", "user-1", Set.of())));

    @Test
    void status_beforeAnyActivity_isInactive() {
        assertThat(controller.status().active()).isFalse();
    }

    @Test
    void status_afterTouchingTheSession_isActiveWithTheConfiguredIdleTimeoutRemaining() {
        sessionService.touch("user-1");

        SessionStatus status = controller.status();

        assertThat(status.active()).isTrue();
        assertThat(status.remainingSeconds()).isGreaterThan(0);
    }

    @Test
    void extend_resetsTheIdleWindow() {
        properties.setExtendBy(Duration.ofMinutes(5));
        sessionService.touch("user-1");

        SessionStatus status = controller.extend();

        assertThat(status.active()).isTrue();
        assertThat(status.remainingSeconds()).isLessThanOrEqualTo(Duration.ofMinutes(5).getSeconds());
    }

    @Test
    void logout_endsTheSessionAndReturnsNoContent() {
        sessionService.touch("user-1");

        ResponseEntity<Void> response = controller.logout();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.status().active()).isFalse();
    }
}
