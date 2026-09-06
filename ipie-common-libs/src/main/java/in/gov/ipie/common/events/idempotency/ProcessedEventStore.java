package in.gov.ipie.common.events.idempotency;

/**
 * Port for tracking which inbound event ids a consumer has already handled. Each service backs
 * this with its own storage (a dedicated table is the usual choice - see
 * {@code ipie-service-template}'s idempotency table for the pattern used on the synchronous API
 * side) since each service owns its data (database-per-service - see
 * {@code Database_Environment_Configuration.md}'s Database Mandatory Controls).
 */
public interface ProcessedEventStore {

    boolean isProcessed(String eventId);

    void markProcessed(String eventId);
}
