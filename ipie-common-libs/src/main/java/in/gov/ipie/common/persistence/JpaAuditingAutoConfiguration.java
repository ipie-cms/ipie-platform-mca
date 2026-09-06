package in.gov.ipie.common.persistence;

import java.util.Optional;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import in.gov.ipie.common.security.context.CurrentUser;
import in.gov.ipie.common.security.context.CurrentUserProvider;

/**
 * Fills {@code created_by}/{@code updated_by} from the authenticated caller, so no entity sets them
 * itself (master standards doc, 7.2).
 *
 * <p>Held by the platform because every service wired this identically, and getting it wrong is
 * quiet: an entity saved with no auditor still persists, it just records the wrong author. The
 * fallback is {@code "system"} rather than null or empty, because the columns are NOT NULL and an
 * internal path - an event consumer, a scheduled job - legitimately has no security context.
 *
 * <p>Ordered after Hibernate's own auto-configuration and conditional on an
 * {@link EntityManagerFactory}, so a service with no JPA on its classpath is unaffected.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, EnableJpaAuditing.class})
@ConditionalOnBean(EntityManagerFactory.class)
@EnableJpaAuditing(auditorAwareRef = "ipieAuditorAware")
public class JpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditorAware.class)
    public AuditorAware<String> ipieAuditorAware(CurrentUserProvider currentUserProvider) {
        return () -> Optional.of(currentUserProvider.current()
                .map(CurrentUser::userId)
                .orElse("system"));
    }
}
