package in.gov.ipie.common.web.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as idempotent under an {@code Idempotency-Key} request header - a
 * repeated request with the same key returns the first call's cached response instead of
 * re-running the method. A pure marker: each service supplies its own {@code IdempotencyAspect}
 * backed by its own idempotency-key storage (a JPA entity/repository today, per service), since
 * that storage is not a shared concern this annotation itself needs to know about.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {
}
