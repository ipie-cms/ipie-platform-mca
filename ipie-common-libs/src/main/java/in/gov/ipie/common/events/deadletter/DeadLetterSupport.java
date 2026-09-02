package in.gov.ipie.common.events.deadletter;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Builds a consumer queue that dead-letters instead of losing - or endlessly redelivering - a
 * message the consumer cannot handle.
 *
 * <p>Declare consumer queues with {@link #workQueue(String)} rather than {@code new Queue(name)}.
 * A plain queue has no dead-letter exchange, so a message that always fails has two possible fates,
 * both bad: Spring AMQP's default {@code defaultRequeueRejected=true} puts it straight back on the
 * queue, and the consumer spins on it forever at full CPU; set that to false without a dead-letter
 * exchange and the message is simply dropped. Neither leaves anything to investigate.
 *
 * <p>Every dead-lettered message from every queue lands in one place ({@link #DEAD_LETTER_QUEUE}),
 * declared by {@code DeadLetterAutoConfiguration}. One queue rather than one per consumer is
 * deliberate: it is a single thing for operations to alert on and inspect, and RabbitMQ's
 * {@code x-death} header already records which queue the message came from, how many times, and
 * why - so nothing is lost by not splitting it.
 */
public final class DeadLetterSupport {

    /** Fanout, so every dead-lettered message reaches {@link #DEAD_LETTER_QUEUE} whatever its original routing key. */
    public static final String DEAD_LETTER_EXCHANGE = "ipie.events.dlx";

    public static final String DEAD_LETTER_QUEUE = "ipie.events.dlq";

    private DeadLetterSupport() {
    }

    /**
     * A durable consumer queue whose rejected messages are routed to {@link #DEAD_LETTER_EXCHANGE}.
     *
     * <p>Durable so a broker restart does not discard undelivered work - the events crossing these
     * queues are business facts (a registration completed, an account was linked), not cache
     * warming.
     */
    public static Queue workQueue(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .build();
    }
}
