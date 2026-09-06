package in.gov.ipie.common.audit.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.audit.annotation.Auditable;
import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.security.context.CurrentUserProvider;

/**
 * Proves {@link AuditAspect} correctly evaluates the SpEL-based {@code comment}/{@code oldValue}/
 * {@code newValue} attributes {@link Auditable} exposes - {@code comment} and {@code newValue}
 * resolve against the method's arguments plus {@code #result} (after the method has run),
 * {@code oldValue} resolves against arguments only (evaluated *before* the method runs, so
 * {@code #result} is never in scope for it) - and that an unset (default {@code ""}) expression
 * stays {@code null} rather than being evaluated.
 */
class AuditAspectTest {

    private record TestCommand(String id, String reasonGivenByCaller) {
    }

    private static class Probe {
        @Auditable(
                action = "TEST_ACTION", entityType = "TEST", comment = "#command.reasonGivenByCaller()",
                oldValue = "#command.id()", newValue = "#result")
        Object fullyAnnotated(TestCommand command) {
            return null;
        }

        @Auditable(action = "TEST_ACTION", entityType = "TEST")
        Object minimallyAnnotated(TestCommand command) {
            return null;
        }
    }

    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final Environment environment = mock(Environment.class);
    private final AuditAspect aspect = new AuditAspect(auditRecorder, currentUserProvider, environment);

    @Test
    void audit_evaluatesCommentAndNewValueAfterTheMethodRuns_andOldValueFromArgumentsOnly() throws Throwable {
        when(currentUserProvider.current()).thenReturn(Optional.empty());
        TestCommand command = new TestCommand("entity-1", "correcting a typo");
        Object result = "the returned value";
        ProceedingJoinPoint joinPoint = joinPointFor("fullyAnnotated", command, result);
        Auditable auditable = annotationFor("fullyAnnotated");

        aspect.audit(joinPoint, auditable);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.comment()).isEqualTo("correcting a typo");
        assertThat(event.oldValue()).isEqualTo("entity-1");
        assertThat(event.newValue()).isEqualTo("the returned value");
    }

    @Test
    void audit_leavesCommentOldValueNewValueNull_whenTheirExpressionsAreNotSet() throws Throwable {
        when(currentUserProvider.current()).thenReturn(Optional.empty());
        TestCommand command = new TestCommand("entity-1", "irrelevant here");
        ProceedingJoinPoint joinPoint = joinPointFor("minimallyAnnotated", command, "some result");
        Auditable auditable = annotationFor("minimallyAnnotated");

        aspect.audit(joinPoint, auditable);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.comment()).isNull();
        assertThat(event.oldValue()).isNull();
        assertThat(event.newValue()).isNull();
    }

    private static Auditable annotationFor(String methodName) throws NoSuchMethodException {
        return Probe.class.getDeclaredMethod(methodName, TestCommand.class).getAnnotation(Auditable.class);
    }

    private static ProceedingJoinPoint joinPointFor(String methodName, TestCommand command, Object result) throws Throwable {
        Method method = Probe.class.getDeclaredMethod(methodName, TestCommand.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[] {command});
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }
}
