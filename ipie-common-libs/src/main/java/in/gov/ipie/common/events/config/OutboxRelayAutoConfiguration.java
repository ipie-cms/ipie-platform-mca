package in.gov.ipie.common.events.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import in.gov.ipie.common.events.outbox.OutboxRelayScheduler;
import in.gov.ipie.common.events.outbox.OutboxStore;
import in.gov.ipie.common.events.publisher.EventPublisher;

/**
 * Runs the outbox relay for any service that has both a store and a publisher.
 *
 * <p>{@code @ConditionalOnBean} is safe here because auto-configuration is processed after the
 * service's own beans: a service registers its {@link OutboxStore} in its own configuration, so by
 * the time this class is considered the answer is already known. A service that publishes nothing
 * gets no scheduler and no beans it does not use.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ipie.events.outbox", name = "relay-enabled", matchIfMissing = true)
public class OutboxRelayAutoConfiguration {

    @Bean
    @ConditionalOnBean({OutboxStore.class, EventPublisher.class})
    @ConditionalOnMissingBean(OutboxRelayScheduler.class)
    public OutboxRelayScheduler outboxRelayScheduler(
            OutboxStore outboxStore,
            EventPublisher eventPublisher,
            @Value("${ipie.events.outbox.retention:P30D}") Duration purgeRetention,
            @Value("${ipie.events.outbox.batch-size:50}") int batchSize) {
        return new OutboxRelayScheduler(outboxStore, eventPublisher, purgeRetention, batchSize);
    }
}
