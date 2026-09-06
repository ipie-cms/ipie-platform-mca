package in.gov.ipie.common.events.deadletter;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

import in.gov.ipie.common.resilience.config.YamlPropertySourceFactory;

/**
 * Declares the one dead-letter exchange and queue every service shares, and loads the listener
 * settings that make dead-lettering actually happen ({@code ipie-events-defaults.yml}).
 *
 * <p>The topology alone is not enough: Spring AMQP defaults to requeueing a rejected message, so a
 * consumer that always fails on a particular message redelivers it forever and never dead-letters
 * anything. The defaults file turns that off and bounds the in-process retries first, so a
 * transient failure still gets a few attempts before the message is set aside.
 *
 * <p>Conditional on a configured broker for the same reason every other messaging bean here is -
 * a service with no {@code spring.rabbitmq.host} publishes events through the logging fallback and
 * has no broker to declare anything against.
 */
@AutoConfiguration
@ConditionalOnClass(ConnectionFactory.class)
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
@PropertySource(value = "classpath:ipie-events-defaults.yml", factory = YamlPropertySourceFactory.class)
public class DeadLetterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "ipieDeadLetterExchange")
    public FanoutExchange ipieDeadLetterExchange() {
        return new FanoutExchange(DeadLetterSupport.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnMissingBean(name = "ipieDeadLetterQueue")
    public Queue ipieDeadLetterQueue() {
        // No dead-letter exchange of its own: a message that fails here has nowhere further to go,
        // and pointing it at another queue only builds a loop.
        return QueueBuilder.durable(DeadLetterSupport.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "ipieDeadLetterBinding")
    public Binding ipieDeadLetterBinding(Queue ipieDeadLetterQueue, FanoutExchange ipieDeadLetterExchange) {
        return BindingBuilder.bind(ipieDeadLetterQueue).to(ipieDeadLetterExchange);
    }
}
