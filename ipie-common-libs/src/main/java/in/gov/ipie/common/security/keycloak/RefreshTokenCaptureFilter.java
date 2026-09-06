package in.gov.ipie.common.security.keycloak;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures the {@value #REFRESH_TOKEN_HEADER} request header into {@link RefreshTokenContextHolder}
 * for the lifetime of the request, so {@code common-client}'s {@code TokenRelayInterceptor} can
 * use it to refresh an expired relayed token (see {@link RefreshTokenContextHolder}'s Javadoc for
 * the security trade-off this represents).
 *
 * <p><b>Not registered by any auto-configuration - opt-in only.</b> A service adds this itself
 * (e.g. {@code @Bean FilterRegistrationBean<RefreshTokenCaptureFilter> ...}) only once it has
 * deliberately decided its callers may supply a refresh token this way. This is the opposite
 * default from {@code common-observability}'s {@code CorrelationIdFilter}, which is always
 * registered - a correlation id is not sensitive, a refresh token is.
 */
public class RefreshTokenCaptureFilter extends OncePerRequestFilter {

    public static final String REFRESH_TOKEN_HEADER = "X-Refresh-Token";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String refreshToken = request.getHeader(REFRESH_TOKEN_HEADER);
        try {
            if (refreshToken != null && !refreshToken.isBlank()) {
                RefreshTokenContextHolder.set(refreshToken);
            }
            filterChain.doFilter(request, response);
        } finally {
            RefreshTokenContextHolder.clear();
        }
    }
}
