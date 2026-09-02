package in.gov.ipie.common.security.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import in.gov.ipie.common.i18n.MessageResolver;
import in.gov.ipie.common.observability.correlation.LoggingContext;
import in.gov.ipie.common.web.error.ApiError;
import in.gov.ipie.common.web.util.HttpRequestUtils;

/**
 * Rejects a request with {@code 429 Too Many Requests} once its caller (keyed by pattern + client
 * IP, see {@link HttpRequestUtils#clientIp}) exceeds the threshold {@link RateLimitProperties}
 * configures for the first matching rule - defense against abuse of genuinely public,
 * unauthenticated endpoints (e.g. self-registration, a stakeholder-SSO callback), for which a
 * per-user/JWT-based limit is not possible since there is no authenticated caller yet.
 *
 * <p><b>Not registered by any auto-configuration - opt-in only</b>, the exact same reasoning
 * {@code HmacSignatureVerificationFilter} documents for itself: a service adds this itself (e.g.
 * {@code http.addFilterBefore(new RateLimitFilter(...), ...)} in its own {@code
 * SecurityFilterChain}, see {@code ResourceServerAutoConfiguration#configureBaseline}) only once
 * it has identified which endpoints actually need this protection - most endpoints are already
 * authenticated and better protected by ordinary access control than by IP-based throttling.
 *
 * <p>A request whose path matches no configured rule passes through unchanged. The first matching
 * rule wins (rules are not combined) - keep patterns non-overlapping in practice.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Kept as the response's {@code errorCode} so any client already keying on it still matches. */
    private static final String ERROR_CODE = "RATE_LIMITED";

    private final RateLimitProperties properties;
    private final RateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final MessageResolver messageResolver;

    public RateLimitFilter(
            RateLimitProperties properties,
            RateLimiter rateLimiter,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            MessageResolver messageResolver) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RateLimitProperties.Rule rule = matchingRule(request.getRequestURI());
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = rule.getPattern() + ":" + HttpRequestUtils.clientIp(request);
        if (!rateLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow())) {
            meterRegistry.counter("ipie.ratelimit.rejected", "pattern", rule.getPattern()).increment();
            respondTooManyRequests(request, response, rule);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitProperties.Rule matchingRule(String requestUri) {
        return properties.getRules().stream()
                .filter(rule -> PATH_MATCHER.match(rule.getPattern(), requestUri))
                .findFirst()
                .orElse(null);
    }

    /**
     * Answers in the platform's one error shape ({@link ApiError}), not a bespoke body. This filter
     * short-circuits before any controller, so {@code GlobalExceptionHandler} never sees the
     * rejection and cannot render it - which is how this response came to invent its own
     * {@code {"error": ...}} structure, the one thing the standards forbid (master standards doc,
     * 5.3/5.4). A client parsing errorCode/message/traceId should not need a special case for the
     * one status it is most likely to hit.
     *
     * <p>{@code Retry-After} carries the rule's whole window rather than the time left in it: the
     * {@link RateLimiter} contract exposes no reset instant, and a value that is too large only
     * makes a well-behaved client wait slightly longer, while one that is too small invites it
     * straight back into another rejection.
     */
    private void respondTooManyRequests(
            HttpServletRequest request, HttpServletResponse response, RateLimitProperties.Rule rule) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, rule.getWindow().toSeconds())));

        ApiError body = ApiError.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                ERROR_CODE,
                messageResolver.resolve(ERROR_CODE, "Too many requests - try again later"),
                request.getRequestURI(),
                LoggingContext.traceId());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
