package in.gov.ipie.common.events.idempotency;

/**
 * Wraps event handling with the check-then-mark idempotency pattern consumers are required to
 * apply (master standards doc, section 9: "Consumers must handle duplicate delivery").
 */
public final class IdempotentEventHandler {

    private IdempotentEventHandler() {
    }

    /** Runs {@code action} only if {@code eventId} has not already been marked processed. */
    public static void handle(String eventId, ProcessedEventStore store, Runnable action) {
        if (store.isProcessed(eventId)) {
            return;
        }
        action.run();
        store.markProcessed(eventId);
    }
}
