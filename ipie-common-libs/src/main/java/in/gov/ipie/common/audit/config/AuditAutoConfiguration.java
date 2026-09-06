package in.gov.ipie.common.audit.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.Environment;

import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.audit.LoggingAuditRecorder;
import in.gov.ipie.common.audit.aspect.AuditAspect;
import in.gov.ipie.common.audit.outbox.OutboxAuditRecorder;
import in.gov.ipie.common.events.outbox.OutboxStore;
import in.gov.ipie.common.security.context.CurrentUserProvider;

/**
 * Wires the {@link AuditRecorder} precedence chain: {@link OutboxAuditRecorder} (durable,
 * Kafka/RabbitMQ-backed via the transactional outbox) when a service has an {@link OutboxStore}
 * bean, {@link LoggingAuditRecorder} otherwise - the same "real binding wins when configured,
 * reference implementation is the safe fallback" precedence used elsewhere in this platform (see
 * {@code EventPublisherConfig}, {@code IpieCacheAutoConfiguration}). Declared in this order
 * deliberately: {@code loggingAuditRecorder}'s own {@code @ConditionalOnMissingBean} only backs
 * off correctly if {@code outboxAuditRecorder} has already been evaluated first.
 */
@AutoConfiguration
@EnableAspectJAutoProxy
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnBean(OutboxStore.class)
    @ConditionalOnMissingBean(AuditRecorder.class)
    public AuditRecorder outboxAuditRecorder(OutboxStore outboxStore, Environment environment) {
        return new OutboxAuditRecorder(outboxStore, environment.getProperty("spring.application.name", "unknown-service"));
    }

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    public AuditRecorder loggingAuditRecorder(ObjectMapper objectMapper) {
        return new LoggingAuditRecorder(objectMapper);
    }

    @Bean
    public AuditAspect auditAspect(AuditRecorder auditRecorder, CurrentUserProvider currentUserProvider, Environment environment) {
        return new AuditAspect(auditRecorder, currentUserProvider, environment);
    }
}
