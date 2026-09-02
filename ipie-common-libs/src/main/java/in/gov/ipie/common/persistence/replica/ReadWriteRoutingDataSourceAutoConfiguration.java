package in.gov.ipie.common.persistence.replica;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Splits reads onto a replica, when a deployment says there is one.
 *
 * <p>The platform's operational standards say the design "supports directing read-heavy reporting
 * and MIS queries away from the primary write path", with whether replicas are used, and how many,
 * set by the Database Administrator against measured load. This is the code side of that: the
 * capability is present and dormant, and a deployment turns it on without any service changing.
 *
 * <h2>Off unless asked for</h2>
 *
 * <p>{@code ipie.datasource.replica.enabled} defaults to false, and this whole auto-configuration
 * is conditional on it. A service that says nothing gets Spring Boot's ordinary single
 * {@code DataSource}, unchanged - no proxy, no router, no extra pool.
 *
 * <h2>The two traps this class exists to close</h2>
 *
 * <ol>
 *   <li><b>Routing must be lazy.</b> Spring acquires the connection when the transaction begins,
 *       which is before {@code readOnly} reaches {@code TransactionSynchronizationManager}. A bare
 *       router would therefore see false every time and send every read to the primary - correct
 *       results, no error, and the replica sitting idle while someone wonders why nothing improved.
 *       {@link LazyConnectionDataSourceProxy} defers acquisition to the first statement, after the
 *       flag is set. It is the reason the router is never exposed as the {@code DataSource} bean
 *       itself.
 *   <li><b>Flyway must never see the router.</b> Migrations write. Handing them a routing data
 *       source risks DDL arriving at a read-only replica, and - worse - makes which database gets
 *       migrated depend on transaction metadata. The {@code @FlywayDataSource} bean below pins
 *       migrations to the primary explicitly.
 * </ol>
 *
 * <h2>What a service takes on by enabling it</h2>
 *
 * <p>Replication is asynchronous, so a read-only transaction may not see a write that has just
 * committed. Read-your-own-writes therefore stops holding across two transactions. Where a flow
 * depends on it - re-reading a row it has just created to return it - the read belongs in the same
 * transaction as the write, or on a method that is not marked read-only. This is a real behaviour
 * change and is why the switch is per deployment rather than on by default.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass({HikariDataSource.class, LazyConnectionDataSourceProxy.class})
@ConditionalOnProperty(prefix = "ipie.datasource.replica", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ReplicaDataSourceProperties.class)
public class ReadWriteRoutingDataSourceAutoConfiguration {

    /**
     * The primary. Built from {@code spring.datasource.*} exactly as Boot would have built it, so
     * the writer is the same database on the same settings as before the split.
     */
    @Bean
    @Qualifier("primaryDataSource")
    public HikariDataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * The replica. Credentials and driver fall back to the primary's, because in the usual
     * deployment a replica is the same database software with the same login and only the host
     * differs - making an operator restate them would be a way to get them wrong.
     */
    @Bean
    @Qualifier("replicaDataSource")
    public HikariDataSource replicaDataSource(DataSourceProperties primaryProperties,
                                              ReplicaDataSourceProperties replicaProperties) {
        String url = replicaProperties.getUrl();
        if (url == null || url.isBlank()) {
            // Fail at startup, naming the property. The alternative - quietly pointing the replica
            // at the primary - would look like a working split and deliver none of it.
            throw new IllegalStateException(
                    "ipie.datasource.replica.enabled is true but ipie.datasource.replica.url is not set."
                            + " A read replica has no usable default: pointing reads back at the primary"
                            + " would report a working split while delivering none of it.");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(value(replicaProperties.getUsername(), primaryProperties.getUsername()));
        dataSource.setPassword(value(replicaProperties.getPassword(), primaryProperties.getPassword()));
        String driver = value(replicaProperties.getDriverClassName(), primaryProperties.getDriverClassName());
        if (driver != null && !driver.isBlank()) {
            dataSource.setDriverClassName(driver);
        }
        if (replicaProperties.getMaximumPoolSize() != null) {
            dataSource.setMaximumPoolSize(replicaProperties.getMaximumPoolSize());
        }
        dataSource.setReadOnly(true);
        dataSource.setPoolName("ipie-replica");
        return dataSource;
    }

    /**
     * The {@code DataSource} everything else injects: the router, wrapped lazily. See the class
     * note - the wrapping is not optional, and this is the only bean that performs it.
     */
    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("primaryDataSource") DataSource primary,
                                 @Qualifier("replicaDataSource") DataSource replica) {
        return new LazyConnectionDataSourceProxy(new ReadWriteRoutingDataSource(primary, replica));
    }

    /**
     * Migrations run against the primary, never the router. See trap 2 in the class note.
     */
    @Bean
    @FlywayDataSource
    public DataSource flywayDataSource(@Qualifier("primaryDataSource") DataSource primary) {
        return primary;
    }

    private static String value(String preferred, String fallback) {
        return (preferred == null || preferred.isBlank()) ? fallback : preferred;
    }
}
