package in.gov.ipie.common.resilience.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import in.gov.ipie.common.audit.config.AuditAutoConfiguration;
import in.gov.ipie.common.cache.config.IpieCacheAutoConfiguration;
import in.gov.ipie.common.client.config.InterServiceClientAutoConfiguration;
import in.gov.ipie.common.observability.config.ObservabilityAutoConfiguration;
import in.gov.ipie.common.resilience.config.IpieResilienceAutoConfiguration;
import in.gov.ipie.common.resilience.exception.TransientDependencyException;
import in.gov.ipie.common.security.config.ResourceServerAutoConfiguration;
import in.gov.ipie.common.security.hmac.HmacAutoConfiguration;
import in.gov.ipie.common.security.keycloak.KeycloakTokenAutoConfiguration;
import in.gov.ipie.common.security.keycloak.admin.KeycloakAdminAutoConfiguration;
import in.gov.ipie.common.security.keycloak.admin.KeycloakUserManagementAutoConfiguration;
import in.gov.ipie.common.session.config.SessionAutoConfiguration;
import in.gov.ipie.common.web.config.WebErrorAutoConfiguration;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * Proves the shared defaults in {@code ipie-resilience-defaults.yml}
 * (Development_Environment_Configuration.md, Section 15, Resilience) actually take effect through
 * the real resilience4j-spring-boot3 AOP aspects, with zero per-service {@code resilience4j.*}
 * configuration - only an {@code @Retry}/{@code @CircuitBreaker}/{@code @Bulkhead}/
 * {@code @TimeLimiter(name = "...")} annotation is needed. Each assertion is chosen so that it
 * would FAIL against resilience4j's own built-in defaults (max-attempts=3 but retries every
 * exception; sliding-window-size=100/minimum-number-of-calls=100; max-concurrent-calls=25;
 * timeout-duration=1s) - passing proves this module's YAML, not resilience4j's factory defaults,
 * is what's actually wired in.
 *
 * <p>Bootstraps a plain {@link SpringApplicationBuilder} rather than {@code @SpringBootTest}
 * deliberately: {@code @SpringBootTest}'s {@code SpringExtension}/{@code TestContextManager} path
 * always registers {@code ResetMocksTestExecutionListener}, which touches Mockito's byte-buddy
 * mock maker for every test regardless of whether the test uses mocks - this module has no
 * dependency on Mockito, and shouldn't need one just to boot a plain application context.
 *
 * <p>Excludes every auto-configuration this module ships except {@link IpieResilienceAutoConfiguration}
 * itself: since the 2026-07-20 module merge, every package's auto-configuration is on this
 * module's own test classpath (previously {@code common-resilience} was a separate Gradle module
 * and {@code @EnableAutoConfiguration} here never saw a single one of these other classes), and
 * several need beans (e.g. {@code security}'s {@code HttpSecurity}, {@code CurrentUserProvider})
 * that only a real servlet web/security context provides - resilience4j's own auto-configuration
 * (a third-party classpath entry, not excluded here) is unaffected and still wires the AOP aspects
 * these tests exercise.
 *
 * <p>The context is started once for the whole class ({@code @BeforeAll}/{@code @AfterAll},
 * not per test) - each test below deliberately uses its own resilience instance name
 * ({@code widget-retry}, {@code widget-circuit-breaker}, etc.), so sharing one context across
 * tests cannot leak state between them, and avoids paying full Spring Boot startup cost five times.
 */
class ResilienceDefaultsBehaviorTest {

    private static ConfigurableApplicationContext context;
    private static ResilientWidget widget;

    @BeforeAll
    static void startApplication() {
        context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .run();
        widget = context.getBean(ResilientWidget.class);
    }

    @AfterAll
    static void stopApplication() {
        context.close();
    }

    @Test
    void retry_retriesOnlyTransientFailures_untilTheSharedDefaultMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        String result = widget.callTransientDependency(attempts);

        assertThat(result).isEqualTo("ok");
        // Shared default max-attempts is 3: fails twice, succeeds on the 3rd.
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void retry_neverRetriesANonTransientBusinessError() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> widget.callWithBusinessError(attempts))
                .isInstanceOf(IllegalArgumentException.class);

        // resilience4j's own built-in default retries every exception type; only one call here
        // proves the shared retry-exceptions allowlist (IOException/TimeoutException/
        // TransientDependencyException only) is the config actually in effect.
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void circuitBreaker_opensAfterTheSharedDefaultFailureThreshold() {
        // Shared default: minimum-number-of-calls=5, sliding-window-size=10, threshold=50%.
        // resilience4j's built-in default requires 100 calls first, so 5 calls could not open it.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(widget::alwaysFailForCircuitBreaker)
                    .isInstanceOf(TransientDependencyException.class);
        }

        assertThatThrownBy(widget::alwaysFailForCircuitBreaker)
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void bulkhead_rejectsCallsBeyondTheSharedDefaultConcurrencyLimit() throws InterruptedException {
        // Shared default max-concurrent-calls=10 (resilience4j's built-in default is 25, which
        // would happily accept an 11th concurrent call - this proves ours is the one in effect).
        int maxConcurrentCalls = 10;
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allEntered = new CountDownLatch(maxConcurrentCalls);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrentCalls);
        try {
            for (int i = 0; i < maxConcurrentCalls; i++) {
                pool.submit(() -> widget.holdBulkheadSlot(allEntered, release));
            }
            assertThat(allEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(widget::holdOneMoreBulkheadSlot)
                    .isInstanceOf(BulkheadFullException.class);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rateLimiter_rejectsCallsBeyondTheSharedDefaultLimitForThePeriod() {
        // Shared default: limit-for-period=20, timeout-duration=0s (fails fast, no waiting).
        // resilience4j's own built-in default is limit-for-period=50 (a burst of 25 would never
        // be rejected at all) and timeout-duration=5s (a rejected call would block for up to 5s
        // waiting for a permit instead of failing immediately). Asserting on "some call in a
        // 25-call burst is rejected, fast" rather than pinning the exact 21st call as the boundary
        // keeps this robust against incidental timing/JIT-warmup variance near a 1s cycle edge,
        // while still ruling out both of resilience4j's built-in defaults having applied instead.
        int burstSize = 25;
        int successCount = 0;
        boolean rejected = false;
        long start = System.nanoTime();
        for (int i = 0; i < burstSize && !rejected; i++) {
            try {
                widget.callWithRateLimit();
                successCount++;
            } catch (RequestNotPermitted e) {
                rejected = true;
            }
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(rejected).as("a %d-call burst should be throttled under the shared limit-for-period=20 default", burstSize)
                .isTrue();
        assertThat(successCount).isLessThan(burstSize);
        assertThat(elapsedMillis).isLessThan(900);
    }

    @Test
    void timeLimiter_timesOutAtTheSharedDefaultDuration_notResilience4jsBuiltInOne() {
        long start = System.nanoTime();

        CompletableFuture<String> future = widget.callSlowDependency();

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Shared default timeout-duration is 5s; resilience4j's built-in default is 1s, so
        // asserting we waited well past 1s rules out the built-in default having applied instead.
        assertThat(elapsedMillis).isGreaterThan(2000).isLessThan(10000);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ResourceServerAutoConfiguration.class,
            KeycloakTokenAutoConfiguration.class,
            KeycloakAdminAutoConfiguration.class,
            KeycloakUserManagementAutoConfiguration.class,
            HmacAutoConfiguration.class,
            SessionAutoConfiguration.class,
            WebErrorAutoConfiguration.class,
            AuditAutoConfiguration.class,
            IpieCacheAutoConfiguration.class,
            InterServiceClientAutoConfiguration.class,
            ObservabilityAutoConfiguration.class,
            // spring-boot-starter-data-jpa is a testImplementation dependency of this module (it
            // backs persistence.AuditableJpaEntity), so Boot tries to auto-configure a DataSource
            // here and fails with "Failed to determine a suitable driver class" - there is no
            // database, and this test is about resilience defaults, not persistence. Excluding the
            // three JPA/SQL auto-configurations keeps the context to what the test actually
            // exercises.
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            SqlInitializationAutoConfiguration.class,
    })
    @Import(ResilientWidget.class)
    static class TestApplication {
    }

    @Component
    static class ResilientWidget {

        @Retry(name = "widget-retry")
        String callTransientDependency(AtomicInteger attempts) {
            if (attempts.incrementAndGet() < 3) {
                throw new TransientDependencyException("simulated transient failure");
            }
            return "ok";
        }

        @Retry(name = "widget-retry-business-error")
        String callWithBusinessError(AtomicInteger attempts) {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("simulated business/validation error - never retried");
        }

        @CircuitBreaker(name = "widget-circuit-breaker")
        String alwaysFailForCircuitBreaker() {
            throw new TransientDependencyException("simulated dependency failure");
        }

        @Bulkhead(name = "widget-bulkhead", type = Bulkhead.Type.SEMAPHORE)
        String holdBulkheadSlot(CountDownLatch entered, CountDownLatch release) {
            entered.countDown();
            awaitUninterruptibly(release);
            return "released";
        }

        @Bulkhead(name = "widget-bulkhead", type = Bulkhead.Type.SEMAPHORE)
        String holdOneMoreBulkheadSlot() {
            return "should never get this far";
        }

        @RateLimiter(name = "widget-rate-limiter")
        String callWithRateLimit() {
            return "ok";
        }

        @TimeLimiter(name = "widget-time-limiter")
        CompletableFuture<String> callSlowDependency() {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "too slow";
            });
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        latch.await();
                        return;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
