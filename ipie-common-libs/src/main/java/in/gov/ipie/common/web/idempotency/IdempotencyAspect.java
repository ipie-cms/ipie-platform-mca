package in.gov.ipie.common.web.idempotency;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Enforces {@code @Idempotent} on a controller method: an {@code Idempotency-Key} request header
 * matching a previously-stored key returns the first call's response instead of re-running the
 * method. Shared across every service (unlike the per-service copies this replaces) - not
 * {@code @Component}, wired via {@link IdempotencyAutoConfiguration}'s own {@code @Bean} method
 * instead, the same "common-libs classes aren't component-scanned by services" precedent
 * {@code PermissionCheckAspect} already establishes.
 *
 * <p>Replays by deserializing the stored JSON into the intercepted method's own <em>declared</em>
 * return type - for a {@code ResponseEntity<X>}-returning method, into a plain {@code Object}
 * (never a {@code String}, so Spring MVC's converter selection is never in question) wrapped back
 * into a {@code ResponseEntity}; for a raw-DTO-returning method, straight into that DTO class.
 * This is deliberate: an earlier per-service version of this aspect only ever stored a response
 * when the intercepted method returned {@code ResponseEntity<?>}, so a method returning a raw DTO
 * directly (e.g. {@code OrganisationController#findOrCreateOrganisation},
 * {@code AccountProvisioningController#provisionAccount}) never actually got replay protection -
 * this version fixes that instead of carrying the gap forward.
 *
 * <p>Known limitation: a raw-DTO method that also sets a non-200 status via {@code @ResponseStatus}
 * would lose that status on replay (this aspect does not know about that annotation) - none of
 * this platform's current {@code @Idempotent} usages combine the two.
 */
@Aspect
public class IdempotencyAspect {

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyStore store, IdempotencyProperties properties, ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(in.gov.ipie.common.web.idempotency.Idempotent)")
    public Object enforceIdempotency(ProceedingJoinPoint joinPoint) throws Throwable {
        String idempotencyKey = currentIdempotencyKeyHeader();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return joinPoint.proceed();
        }

        Class<?> declaredReturnType = ((MethodSignature) joinPoint.getSignature()).getMethod().getReturnType();

        Optional<IdempotencyStore.StoredResponse> stored = store.find(idempotencyKey);
        if (stored.isPresent()) {
            return replay(stored.get(), declaredReturnType);
        }

        Object result = joinPoint.proceed();
        storeResult(idempotencyKey, result);
        return result;
    }

    private Object replay(IdempotencyStore.StoredResponse stored, Class<?> declaredReturnType) throws JsonProcessingException {
        if (ResponseEntity.class.isAssignableFrom(declaredReturnType)) {
            Object body = objectMapper.readValue(stored.bodyJson(), Object.class);
            return ResponseEntity.status(stored.status()).body(body);
        }
        return objectMapper.readValue(stored.bodyJson(), declaredReturnType);
    }

    private void storeResult(String idempotencyKey, Object result) throws JsonProcessingException {
        int status = HttpStatus.OK.value();
        Object body = result;
        if (result instanceof ResponseEntity<?> response) {
            status = response.getStatusCode().value();
            body = response.getBody();
        }
        String json = objectMapper.writeValueAsString(body);
        Duration ttl = properties.getTtl();
        store.store(idempotencyKey, new IdempotencyStore.StoredResponse(status, json), ttl);
    }

    private static String currentIdempotencyKeyHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest().getHeader("Idempotency-Key");
    }
}
