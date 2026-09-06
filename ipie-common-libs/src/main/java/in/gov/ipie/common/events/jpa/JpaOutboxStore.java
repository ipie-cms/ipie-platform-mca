package in.gov.ipie.common.events.jpa;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.springframework.transaction.annotation.Transactional;

import in.gov.ipie.common.events.envelope.EventEnvelope;
import in.gov.ipie.common.events.outbox.OutboxStore;

/**
 * The JPA-backed {@link OutboxStore} every service used to write for itself.
 *
 * <p><b>Why it lives here now.</b> The platform defined the port and every service wrote the same
 * seventy lines to satisfy it - four identical copies, so a change to the port had to be applied
 * four times, and adding {@code deletePublishedBefore} once left a test fake in this module
 * uncompilable until someone noticed. If a port has one correct implementation, the platform ships
 * it; the service supplies configuration.
 *
 * <p><b>Why an EntityManager rather than a Spring Data repository.</b> The entity is declared by the
 * service (it owns the table name), so no repository interface here could name its type. Working
 * through the entity manager keeps the query in one place while the row shape stays a mapped
 * superclass. The service passes its concrete type and a factory for it - a method reference, not
 * reflection, so an entity with no accessible constructor fails to compile instead of at the first
 * event published.
 *
 * <pre>{@code
 * @Bean
 * OutboxStore outboxStore(EntityManager em, ObjectMapper mapper) {
 *     return new JpaOutboxStore<>(em, mapper, OutboxEventJpaEntity.class, OutboxEventJpaEntity::new);
 * }
 * }</pre>
 */
public class JpaOutboxStore<T extends AbstractOutboxEventJpaEntity> implements OutboxStore {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Class<T> entityType;
    private final Supplier<T> factory;

    public JpaOutboxStore(EntityManager entityManager, ObjectMapper objectMapper,
            Class<T> entityType, Supplier<T> factory) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.entityType = entityType;
        this.factory = factory;
    }

    @Override
    @Transactional
    public void save(EventEnvelope<?> event) {
        T row = factory.get();
        row.initialise(event.eventId(), serialize(event), event.occurredAt());
        entityManager.persist(row);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventEnvelope<?>> findUnpublished(int limit) {
        return entityManager.createQuery(
                        "select e from " + entityName() + " e where e.publishedAt is null order by e.occurredAt asc",
                        entityType)
                .setMaxResults(limit)
                .getResultList().stream()
                .<EventEnvelope<?>>map(row -> deserialize(row.getPayload()))
                .toList();
    }

    @Override
    @Transactional
    public void markPublished(String eventId) {
        T row = entityManager.find(entityType, eventId);
        if (row != null) {
            row.markPublished(Instant.now());
        }
    }

    @Override
    @Transactional
    public int deletePublishedBefore(Instant cutoff) {
        // Published rows only - see OutboxStore#deletePublishedBefore. An unpublished row is
        // undelivered work; ageing it out would drop a business event silently.
        return entityManager.createQuery(
                        "delete from " + entityName() + " e"
                                + " where e.publishedAt is not null and e.publishedAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    /**
     * The JPA entity name, which is not always the class name - a service may set
     * {@code @Entity(name = ...)}. Read from the metamodel so the query cannot silently address a
     * type that does not exist.
     */
    private String entityName() {
        return entityManager.getMetamodel().entity(entityType).getName();
    }

    private String serialize(EventEnvelope<?> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event " + event.eventId(), e);
        }
    }

    private EventEnvelope<?> deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize outbox payload: " + payload, e);
        }
    }
}
