package in.gov.ipie.common.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.i18n.MessageResolver;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Proves {@link RateLimitFilter}: a path matching no configured rule passes through untouched; a
 * matched path is allowed up to its rule's limit, then rejected with {@code 429}, keyed
 * independently per client IP.
 */
class RateLimitFilterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final RateLimitFilter filter = new RateLimitFilter(
            properties(),
            new InMemoryRateLimiter(),
            meterRegistry,
            objectMapper,
            new MessageResolver(new StaticMessageSource()));

    @Test
    void aNonMatchingPath_passesThroughUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/unprotected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aMatchingPath_isAllowedUpToTheLimit_thenRejected() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(registrationRequest("10.0.0.1"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(registrationRequest("10.0.0.1"), rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(meterRegistry.counter("ipie.ratelimit.rejected", "pattern", "/api/v1/registrations/**").count()).isEqualTo(1.0);
    }

    @Test
    void differentClientIps_areThrottledIndependently() throws Exception {
        filter.doFilter(registrationRequest("10.0.0.1"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(registrationRequest("10.0.0.1"), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse firstIpRejected = new MockHttpServletResponse();
        filter.doFilter(registrationRequest("10.0.0.1"), firstIpRejected, new MockFilterChain());
        assertThat(firstIpRejected.getStatus()).isEqualTo(429);

        MockHttpServletResponse secondIpAllowed = new MockHttpServletResponse();
        filter.doFilter(registrationRequest("10.0.0.2"), secondIpAllowed, new MockFilterChain());

        assertThat(secondIpAllowed.getStatus()).isEqualTo(200);
    }

    /**
     * The rejection must speak the platform's one error shape, not a body of its own. It is
     * rendered here rather than by {@code GlobalExceptionHandler} - a filter short-circuits before
     * any controller runs - which is exactly how a bespoke shape came to exist in the first place.
     */
    @Test
    void aRejection_answersInTheStandardApiErrorShape_withRetryAfter() throws Exception {
        for (int i = 0; i < 2; i++) {
            filter.doFilter(registrationRequest("10.0.0.9"), new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(registrationRequest("10.0.0.9"), rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentType()).startsWith("application/json");
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");

        JsonNode body = objectMapper.readTree(rejected.getContentAsString());
        assertThat(body.hasNonNull("timestamp")).isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("errorCode").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("path").asText()).isEqualTo("/api/v1/registrations");
        assertThat(body.has("traceId")).isTrue();
        assertThat(body.get("fieldErrors").isArray()).isTrue();
    }

    /**
     * Guards the fix for a real defect: the bucket key used to come from the first entry of
     * {@code X-Forwarded-For}, which is the part a client writes - so rotating the header handed a
     * caller a fresh bucket on every request and defeated the limiter entirely. The address is now
     * whatever Tomcat resolved into {@code getRemoteAddr()}, and the header is not consulted.
     */
    @Test
    void aRotatingXForwardedFor_noLongerBuysAFreshBucket() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = registrationRequest("10.0.0.7");
            request.addHeader("X-Forwarded-For", "203.0.113." + i);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest spoofed = registrationRequest("10.0.0.7");
        spoofed.addHeader("X-Forwarded-For", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(spoofed, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest registrationRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registrations");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static RateLimitProperties properties() {
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setPattern("/api/v1/registrations/**");
        rule.setLimit(2);
        rule.setWindow(Duration.ofMinutes(1));
        properties.setRules(List.of(rule));
        return properties;
    }
}
