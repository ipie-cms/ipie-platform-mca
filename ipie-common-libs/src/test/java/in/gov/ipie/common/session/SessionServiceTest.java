package in.gov.ipie.common.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.session.config.SessionProperties;
import in.gov.ipie.common.session.store.InMemorySessionStore;

/**
 * Proves {@link SessionService}'s idle-timeout/warning/extend/logout contract against
 * {@link InMemorySessionStore} - the business logic under test is independent of which
 * {@code SessionStore} backs it (Redis's own behavior is proven separately in
 * {@code RedisSessionStoreTest}).
 */
class SessionServiceTest {

    @Test
    void status_forAUserWithNoSession_isInactive() {
        SessionService service = serviceWith(Duration.ofMinutes(15), Duration.ofMinutes(1));

        SessionStatus status = service.status("user-1");

        assertThat(status.active()).isFalse();
        assertThat(status.remainingSeconds()).isZero();
    }

    @Test
    void touch_startsASessionWithTheConfiguredIdleTimeout() {
        SessionService service = serviceWith(Duration.ofMinutes(15), Duration.ofMinutes(1));

        service.touch("user-1");
        SessionStatus status = service.status("user-1");

        assertThat(status.active()).isTrue();
        // Allow a small margin for real elapsed time between touch() and status() in the test itself.
        long minExpectedSeconds = Duration.ofMinutes(15).minusSeconds(2).getSeconds();
        long maxExpectedSeconds = Duration.ofMinutes(15).getSeconds();
        assertThat(status.remainingSeconds()).isBetween(minExpectedSeconds, maxExpectedSeconds);
    }

    @Test
    void status_reportsTheConfiguredWarningThreshold() {
        SessionService service = serviceWith(Duration.ofMinutes(15), Duration.ofSeconds(90));

        service.touch("user-1");

        assertThat(service.status("user-1").warningThresholdSeconds()).isEqualTo(90);
    }

    @Test
    void extend_resetsTheIdleWindowToTheConfiguredExtendByDuration() {
        SessionProperties properties = new SessionProperties();
        properties.setIdleTimeout(Duration.ofMinutes(15));
        properties.setExtendBy(Duration.ofMinutes(5));
        SessionService service = new SessionService(new InMemorySessionStore(), properties);
        service.touch("user-1");

        SessionStatus status = service.extend("user-1");

        assertThat(status.active()).isTrue();
        assertThat(status.remainingSeconds()).isLessThanOrEqualTo(Duration.ofMinutes(5).getSeconds());
        assertThat(status.remainingSeconds()).isGreaterThan(Duration.ofMinutes(4).getSeconds());
    }

    @Test
    void extend_withNoExplicitExtendByConfigured_fallsBackToTheIdleTimeout() {
        SessionService service = serviceWith(Duration.ofMinutes(15), Duration.ofMinutes(1));
        service.touch("user-1");

        SessionStatus status = service.extend("user-1");

        assertThat(status.remainingSeconds()).isGreaterThan(Duration.ofMinutes(14).getSeconds());
    }

    @Test
    void logout_endsTheSessionImmediately() {
        SessionService service = serviceWith(Duration.ofMinutes(15), Duration.ofMinutes(1));
        service.touch("user-1");

        service.logout("user-1");

        assertThat(service.status("user-1").active()).isFalse();
    }

    @Test
    void sessionsAreIndependentPerUser() {
        SessionService service = serviceWith(Duration.ofMinutes(15), Duration.ofMinutes(1));
        service.touch("user-1");

        assertThat(service.status("user-1").active()).isTrue();
        assertThat(service.status("user-2").active()).isFalse();
    }

    private static SessionService serviceWith(Duration idleTimeout, Duration warningBeforeTimeout) {
        SessionProperties properties = new SessionProperties();
        properties.setIdleTimeout(idleTimeout);
        properties.setWarningBeforeTimeout(warningBeforeTimeout);
        return new SessionService(new InMemorySessionStore(), properties);
    }
}
