package in.gov.ipie.common.events.jpa;

import java.time.Instant;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;

import org.springframework.transaction.annotation.Transactional;

import in.gov.ipie.common.events.idempotency.ProcessedEventStore;

/**
 * The JPA-backed {@link ProcessedEventStore} every service used to write for itself - see
 * {@link JpaOutboxStore} for why both now live here and what the service still supplies.
 */
public class JpaProcessedEventStore<T extends AbstractProcessedEventEntity> implements ProcessedEventStore {

    private final EntityManager entityManager;
    private final Class<T> entityType;
    private final Supplier<T> factory;

    public JpaProcessedEventStore(EntityManager entityManager, Class<T> entityType, Supplier<T> factory) {
        this.entityManager = entityManager;
        this.entityType = entityType;
        this.factory = factory;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isProcessed(String eventId) {
        return entityManager.find(entityType, eventId) != null;
    }

    @Override
    @Transactional
    public void markProcessed(String eventId) {
        T row = factory.get();
        row.initialise(eventId, Instant.now());
        entityManager.persist(row);
    }
}
