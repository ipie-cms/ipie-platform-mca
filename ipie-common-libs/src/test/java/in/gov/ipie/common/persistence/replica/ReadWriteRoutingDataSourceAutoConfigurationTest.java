package in.gov.ipie.common.persistence.replica;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Asserts the three things that make the read/write split safe to ship dormant: it does nothing
 * unless asked, it wraps the router lazily when asked, and it refuses to start rather than pretend
 * when it is half-configured.
 *
 * <p>No database is contacted. HikariCP opens no connection until one is requested, so a real
 * PostgreSQL URL is enough to prove the wiring.
 */
class ReadWriteRoutingDataSourceAutoConfigurationTest {

    private static final String PRIMARY_URL = "jdbc:postgresql://primary.invalid:5432/ipie";
    private static final String REPLICA_URL = "jdbc:postgresql://replica.invalid:5432/ipie";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    ReadWriteRoutingDataSourceAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=" + PRIMARY_URL,
                    "spring.datasource.username=ipie",
                    "spring.datasource.password=ipie");

    @Test
    @DisplayName("does nothing at all unless a deployment enables it")
    void dormantByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("replicaDataSource");
            assertThat(context).doesNotHaveBean("flywayDataSource");
            // Boot's own single DataSource, untouched - not a proxy, not a router.
            assertThat(context.getBean(DataSource.class)).isNotInstanceOf(LazyConnectionDataSourceProxy.class);
        });
    }

    @Test
    @DisplayName("stays dormant when the switch is explicitly false")
    void dormantWhenDisabled() {
        runner.withPropertyValues("ipie.datasource.replica.enabled=false",
                        "ipie.datasource.replica.url=" + REPLICA_URL)
                .run(context -> assertThat(context).doesNotHaveBean("replicaDataSource"));
    }

    @Test
    @DisplayName("when enabled, the injected DataSource is the router behind a lazy proxy")
    void wrapsRouterLazily() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            DataSource primaryBean = context.getBean("dataSource", DataSource.class);
            // The lazy wrapping is the whole correctness of this feature: without it the routing
            // decision is taken before readOnly is visible, and every read silently hits the primary.
            assertThat(primaryBean).isInstanceOf(LazyConnectionDataSourceProxy.class);
            assertThat(context).hasBean("primaryDataSource");
            assertThat(context).hasBean("replicaDataSource");
        });
    }

    @Test
    @DisplayName("migrations are pinned to the primary, never the router")
    void flywayUsesPrimary() {
        enabled().run(context -> assertThat(context.getBean("flywayDataSource"))
                .isSameAs(context.getBean("primaryDataSource")));
    }

    @Test
    @DisplayName("enabled without a replica URL fails at startup and names the property")
    void refusesToStartHalfConfigured() {
        runner.withPropertyValues("ipie.datasource.replica.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("ipie.datasource.replica.url");
        });
    }

    @Test
    @DisplayName("replica credentials fall back to the primary's when unset")
    void replicaInheritsPrimaryCredentials() {
        enabled().run(context -> {
            com.zaxxer.hikari.HikariDataSource replica =
                    context.getBean("replicaDataSource", com.zaxxer.hikari.HikariDataSource.class);
            assertThat(replica.getUsername()).isEqualTo("ipie");
            assertThat(replica.getJdbcUrl()).isEqualTo(REPLICA_URL);
            // Marked read-only so the driver refuses a write that reached the wrong side, rather
            // than letting it through to a replica that will reject it less clearly.
            assertThat(replica.isReadOnly()).isTrue();
        });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("ipie.datasource.replica.enabled=true",
                "ipie.datasource.replica.url=" + REPLICA_URL);
    }
}
