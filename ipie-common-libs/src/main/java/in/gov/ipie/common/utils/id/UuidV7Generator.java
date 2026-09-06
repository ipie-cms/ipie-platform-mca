package in.gov.ipie.common.utils.id;

import java.util.UUID;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidValueGenerator;

/**
 * Makes {@link IdGenerator#newUuid()} the identifier strategy for JPA entities, so a row's key is
 * assigned before insert and is already in time order.
 *
 * <p>Used as {@code @UuidGenerator(algorithm = UuidV7Generator.class)} on the {@code @Id} field, in
 * place of {@code @GeneratedValue(strategy = GenerationType.UUID)}, which delegates to Hibernate's
 * random generator and is exactly what this replaces. The annotation has to appear on every entity;
 * Hibernate offers no global default, which is why the reasoning lives on {@link IdGenerator} rather
 * than being repeated on each one.
 *
 * <p><b>Why this is a separate class from {@link IdGenerator}.</b> It imports Hibernate, and
 * {@code spring-boot-starter-data-jpa} is {@code compileOnly} in this library precisely so a service
 * with no JPA on its classpath still compiles against everything else here. Putting these two
 * imports into {@code IdGenerator} would drag Hibernate into every consumer of an id helper,
 * including services that have no database at all.
 */
public class UuidV7Generator implements UuidValueGenerator {

    @Override
    public UUID generateUuid(SharedSessionContractImplementor session) {
        return IdGenerator.newUuid();
    }
}
