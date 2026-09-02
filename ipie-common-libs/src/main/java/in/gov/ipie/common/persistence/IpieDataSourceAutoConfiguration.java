package in.gov.ipie.common.persistence;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.PropertySource;

import com.zaxxer.hikari.HikariDataSource;

import in.gov.ipie.common.resilience.config.YamlPropertySourceFactory;

/**
 * Gives the Database Administrator a declared, reviewable tuning surface for the connection pool
 * and JPA, by loading {@code ipie-datasource-defaults.yml}.
 *
 * <p>Every value in that file is the current effective default of HikariCP or Hibernate, expressed
 * as {@code ${ENV_VAR:baseline}}. Adopting this auto-configuration therefore changes no runtime
 * behaviour. What it changes is who owns the numbers: before this, the pool size, timeouts and
 * batching settings existed only as defaults inside third-party libraries, so nothing in the
 * platform stated them, no review could catch them, and a library upgrade could alter them across
 * every service at once with no diff to read. Pinning them here makes any such change a deliberate
 * edit to a file under version control.
 *
 * <p>Overriding never requires touching this library. The property source is added by
 * {@code @PropertySource}, which lands at the lowest precedence in the environment, so a consuming
 * service's {@code application.yml}, its {@code application-<profile>.yml}, and any environment
 * variable all win. Setting {@code IPIE_DB_POOL_MAX_SIZE} in a deployment manifest is the intended
 * route.
 *
 * <p>Guarded by {@link HikariDataSource}: {@code spring-boot-starter-data-jpa} is {@code compileOnly}
 * on this module, so a consumer that has no JDBC pool on its classpath - the Keycloak SPI jar, for
 * one - neither sees these properties nor fails on their absence.
 */
@AutoConfiguration
@ConditionalOnClass(HikariDataSource.class)
@PropertySource(value = "classpath:ipie-datasource-defaults.yml", factory = YamlPropertySourceFactory.class)
public class IpieDataSourceAutoConfiguration {
}
