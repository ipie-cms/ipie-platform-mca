package in.gov.ipie.common.events.publisher;

import in.gov.ipie.common.events.envelope.EventEnvelope;

/**
 * Port application services publish domain events through. Deliberately silent on the broker
 * (Kafka, SNS, etc.) - that binding is an infrastructure adapter each platform provides once and
 * every service reuses (see master standards doc's open decisions section).
 */
public interface EventPublisher {

    void publish(EventEnvelope<?> event);
}
