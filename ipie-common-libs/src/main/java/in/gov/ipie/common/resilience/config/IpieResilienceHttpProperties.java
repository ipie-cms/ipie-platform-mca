package in.gov.ipie.common.resilience.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.resilience.http.*} - the connection and response timeout applied to every service's
 * auto-configured {@code RestClient.Builder} (see {@link IpieResilienceAutoConfiguration}). These
 * are deliberately separate from the {@code resilience4j.timelimiter.*} properties: a
 * {@code TimeLimiter} only bounds asynchronous/{@code CompletableFuture}-returning calls, while a
 * connection/response timeout is enforced by the HTTP client itself and applies to every
 * blocking call too - see Development_Environment_Configuration.md, Section 15, "Timeout": every
 * remote call must have an explicit connection and response timeout.
 */
@ConfigurationProperties(prefix = "ipie.resilience.http")
public class IpieResilienceHttpProperties {

    /** Time allowed to establish the TCP connection before failing. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Time allowed to wait for the response after the connection is established. */
    private Duration responseTimeout = Duration.ofSeconds(5);

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }
}
