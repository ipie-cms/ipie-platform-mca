package in.gov.ipie.common.events.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import in.gov.ipie.common.events.envelope.EventEnvelope;
import in.gov.ipie.common.events.publisher.EventPublisher;

class OutboxRelayTest {

    private final OutboxStore store = mock(OutboxStore.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final OutboxRelay relay = new OutboxRelay(store, publisher);

    @Test
    void relayPending_publishesAndMarksEachUnpublishedEvent() {
        EventEnvelope<String> first = EventEnvelope.create("USER_CREATED", 1, "test", null, null, "user-1");
        EventEnvelope<String> second = EventEnvelope.create("USER_UPDATED", 1, "test", null, null, "user-2");
        when(store.findUnpublished(10)).thenReturn(List.of(first, second));

        int relayed = relay.relayPending(10);

        assertThat(relayed).isEqualTo(2);
        verify(publisher).publish(first);
        verify(store).markPublished(first.eventId());
        verify(publisher).publish(second);
        verify(store).markPublished(second.eventId());
    }

    @Test
    void relayPending_doesNothingWhenNoEventsArePending() {
        when(store.findUnpublished(10)).thenReturn(List.of());

        int relayed = relay.relayPending(10);

        assertThat(relayed).isZero();
        verify(publisher, never()).publish(any());
        verify(store, never()).markPublished(any());
    }

    @Test
    void relayPending_passesBatchSizeThroughToTheStore() {
        when(store.findUnpublished(5)).thenReturn(List.of());

        relay.relayPending(5);

        verify(store).findUnpublished(5);
    }
}
