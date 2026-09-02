package in.gov.ipie.common.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Proves {@link IdempotencyAspect} replays correctly for <em>both</em> return-type shapes a
 * {@code @Idempotent} method can have - the regression test for the bug this aspect's own Javadoc
 * documents: an earlier per-service version only ever stored/replayed a response for a
 * {@code ResponseEntity<?>}-returning method, so a raw-DTO-returning method (e.g. {@code
 * OrganisationController#findOrCreateOrganisation}) never actually got replay protection. Both
 * {@link #replay_reconstructsAResponseEntityBody} and {@link #replay_reconstructsARawDtoBody}
 * would fail against that older implementation.
 */
class IdempotencyAspectTest {

    private record SampleDto(String id, String name) {
    }

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyProperties properties = new IdempotencyProperties();
    private final IdempotencyAspect aspect = new IdempotencyAspect(store, properties, new ObjectMapper());

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void noIdempotencyKeyHeader_alwaysProceeds_andNeverStores() throws Throwable {
        requestWithIdempotencyKey(null);
        ProceedingJoinPoint joinPoint = joinPointReturning(responseEntityMethod(), ResponseEntity.ok("real result"));

        Object result = aspect.enforceIdempotency(joinPoint);

        assertThat(result).isEqualTo(ResponseEntity.ok("real result"));
        verify(store, never()).store(anyString(), any(), any());
    }

    @Test
    void firstCallWithAKey_proceedsAndStoresTheResponseEntityBody() throws Throwable {
        requestWithIdempotencyKey("key-1");
        when(store.find("key-1")).thenReturn(Optional.empty());
        SampleDto body = new SampleDto("abc", "Acme");
        ProceedingJoinPoint joinPoint = joinPointReturning(responseEntityMethod(), ResponseEntity.status(201).body(body));

        Object result = aspect.enforceIdempotency(joinPoint);

        assertThat(result).isEqualTo(ResponseEntity.status(201).body(body));
        IdempotencyStore.StoredResponse expected = new IdempotencyStore.StoredResponse(201, "{\"id\":\"abc\",\"name\":\"Acme\"}");
        verify(store).store(eq("key-1"), eq(expected), any(Duration.class));
    }

    @Test
    void replay_reconstructsAResponseEntityBody() throws Throwable {
        requestWithIdempotencyKey("key-1");
        when(store.find("key-1"))
                .thenReturn(Optional.of(new IdempotencyStore.StoredResponse(201, "{\"id\":\"abc\",\"name\":\"Acme\"}")));
        ProceedingJoinPoint joinPoint = joinPointReturning(responseEntityMethod(), null);

        Object result = aspect.enforceIdempotency(joinPoint);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> replayed = (ResponseEntity<?>) result;
        assertThat(replayed.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getBody()).isEqualTo(java.util.Map.of("id", "abc", "name", "Acme"));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void replay_reconstructsARawDtoBody() throws Throwable {
        requestWithIdempotencyKey("key-1");
        when(store.find("key-1"))
                .thenReturn(Optional.of(new IdempotencyStore.StoredResponse(200, "{\"id\":\"abc\",\"name\":\"Acme\"}")));
        ProceedingJoinPoint joinPoint = joinPointReturning(rawDtoMethod(), null);

        Object result = aspect.enforceIdempotency(joinPoint);

        assertThat(result).isEqualTo(new SampleDto("abc", "Acme"));
        verify(joinPoint, never()).proceed();
    }

    private void requestWithIdempotencyKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (key != null) {
            request.addHeader("Idempotency-Key", key);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ProceedingJoinPoint joinPointReturning(Method method, Object proceedResult) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn(proceedResult);
        return joinPoint;
    }

    private static Method responseEntityMethod() throws NoSuchMethodException {
        return Probe.class.getDeclaredMethod("responseEntityMethod");
    }

    private static Method rawDtoMethod() throws NoSuchMethodException {
        return Probe.class.getDeclaredMethod("rawDtoMethod");
    }

    /** Return-type probes only - never actually invoked, just source for {@link Method#getReturnType()}. */
    private static final class Probe {
        ResponseEntity<Object> responseEntityMethod() {
            return null;
        }

        SampleDto rawDtoMethod() {
            return null;
        }
    }
}
