package in.gov.ipie.common.session.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.session.SessionService;

/**
 * Refreshes the current caller's idle-session window on every authenticated request - genuine API
 * activity is exactly what an idle timeout should measure. Registered automatically (unlike
 * {@code common-security}'s {@code RefreshTokenCaptureFilter}/{@code common-client}'s opt-in
 * security add-ons) via {@code SessionAutoConfiguration}, the same default-on posture
 * {@code common-observability}'s {@code CorrelationIdFilter} has - see
 * {@code SessionProperties}'s Javadoc for why idle-session tracking is on by default rather than
 * an opt-in control.
 *
 * <p>Registered as a plain servlet {@code FilterRegistrationBean}, not inside
 * {@code common-security}'s {@code SecurityFilterChain} - Spring Boot mounts the entire Spring
 * Security filter chain as one filter at a very early servlet-container order, so a
 * {@code FilterRegistrationBean} filter (default order) already runs after it, meaning
 * {@link CurrentUserProvider} sees a populated {@code SecurityContextHolder} by the time this
 * filter runs. Silently does nothing for an unauthenticated request (no current user to touch).
 */
public class SessionActivityFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final CurrentUserProvider currentUserProvider;

    public SessionActivityFilter(SessionService sessionService, CurrentUserProvider currentUserProvider) {
        this.sessionService = sessionService;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        currentUserProvider.current().ifPresent(user -> sessionService.touch(user.userId()));
        filterChain.doFilter(request, response);
    }
}
