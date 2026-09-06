package in.gov.ipie.common.session.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import in.gov.ipie.common.security.context.CurrentUser;
import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.session.SessionService;
import in.gov.ipie.common.session.config.SessionProperties;
import in.gov.ipie.common.session.store.InMemorySessionStore;

/**
 * Proves {@link SessionActivityFilter} touches the current user's session for an authenticated
 * request and always continues the filter chain - it is a side-effecting observer, never a gate.
 */
class SessionActivityFilterTest {

    private final SessionService sessionService = new SessionService(new InMemorySessionStore(), new SessionProperties());

    @Test
    void anAuthenticatedRequest_touchesTheCurrentUsersSession() throws Exception {
        SessionActivityFilter filter = new SessionActivityFilter(sessionService, provider("user-1"));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(sessionService.status("user-1").active()).isTrue();
    }

    @Test
    void anUnauthenticatedRequest_doesNothingButStillContinuesTheChain() throws Exception {
        SessionActivityFilter filter = new SessionActivityFilter(sessionService, provider(null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};

        filter.doFilter(request, response, (req, res) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
    }

    private static CurrentUserProvider provider(String userId) {
        return () -> userId == null ? Optional.empty() : Optional.of(new CurrentUser(userId, userId, Set.of()));
    }
}
