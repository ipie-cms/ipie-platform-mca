package in.gov.ipie.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * The contract of {@code ipie-datasource-defaults.yml} is narrow and easy to break silently, so it
 * is asserted rather than assumed:
 *
 * <ol>
 *   <li>the declared baselines reach the environment, so the Database Administrator's surface is
 *       actually present rather than merely documented;</li>
 *   <li>every one of them equals the library default it restates, so adopting the file changed no
 *       behaviour - the property that makes this file safe to introduce at all;</li>
 *   <li>an override still wins, so the surface is usable without editing this library.</li>
 * </ol>
 *
 * <p>Point 2 is the one that decays. A HikariCP or Hibernate upgrade can move a default underneath
 * the platform, and the values here would then quietly assert the old one in every service. If this
 * test fails after a dependency bump, the library changed its mind: re-read the new default and
 * decide deliberately whether to follow it, rather than editing the expectation to match.
 */
class IpieDataSourceDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IpieDataSourceAutoConfiguration.class));

    @Test
    @DisplayName("ships the pool and JPA baselines into the environment")
    void shipsBaselines() {
        runner.run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
            assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("30000");
            assertThat(env.getProperty("spring.datasource.hikari.idle-timeout")).isEqualTo("600000");
            assertThat(env.getProperty("spring.datasource.hikari.max-lifetime")).isEqualTo("1800000");
            assertThat(env.getProperty("spring.datasource.hikari.validation-timeout")).isEqualTo("5000");
            assertThat(env.getProperty("spring.datasource.hikari.leak-detection-threshold")).isEqualTo("0");
            // 120000, not 0: HikariCP 6 enables keepalive by default. Restating it as 0 would have
            // disabled the probe in every service - the exact class of silent change this guards.
            assertThat(env.getProperty("spring.datasource.hikari.keepalive-time")).isEqualTo("120000");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("0");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.order_inserts")).isEqualTo("false");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.order_updates")).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("minimum-idle follows maximum-pool-size, so raising only the maximum keeps the pool fixed")
    void minimumIdleFollowsMaximum() {
        runner.run(context ->
                assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.minimum-idle"))
                        .isEqualTo("10"));

        runner.withPropertyValues("IPIE_DB_POOL_MAX_SIZE=40").run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("40");
            assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("40");
        });
    }

    @Test
    @DisplayName("an override beats the shipped baseline")
    void overrideWins() {
        runner.withPropertyValues(
                "IPIE_DB_POOL_MAX_SIZE=25",
                "IPIE_DB_POOL_LEAK_DETECTION_MS=30000",
                "IPIE_DB_BATCH_SIZE=50",
                "IPIE_DB_ORDER_INSERTS=true").run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("25");
            assertThat(env.getProperty("spring.datasource.hikari.leak-detection-threshold")).isEqualTo("30000");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("50");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.order_inserts")).isEqualTo("true");
        });
    }

    @Test
    @DisplayName("a service's own property beats the shipped baseline too")
    void serviceConfigurationWins() {
        runner.withPropertyValues("spring.datasource.hikari.maximum-pool-size=64").run(context ->
                assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size"))
                        .isEqualTo("64"));
    }
}
