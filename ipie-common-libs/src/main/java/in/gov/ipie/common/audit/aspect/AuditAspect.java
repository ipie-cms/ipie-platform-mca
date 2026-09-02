package in.gov.ipie.common.audit.aspect;

import java.lang.reflect.Method;
import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.env.Environment;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.audit.annotation.Auditable;
import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.observability.correlation.LoggingContext;
import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.web.util.HttpRequestUtils;

/**
 * Turns a {@code @Auditable}-annotated method call into an {@link AuditEvent}, recorded through
 * the configured {@link AuditRecorder} after the method completes successfully. Runs the wrapped
 * method first, so a failed/rolled-back action does not produce a misleading audit record.
 */
@Aspect
public class AuditAspect {

    private static final Logger LOG = LoggerFactory.getLogger(AuditAspect.class);
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();
    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();

    private final AuditRecorder auditRecorder;
    private final CurrentUserProvider currentUserProvider;
    private final String serviceName;

    public AuditAspect(AuditRecorder auditRecorder, CurrentUserProvider currentUserProvider, Environment environment) {
        this.auditRecorder = auditRecorder;
        this.currentUserProvider = currentUserProvider;
        this.serviceName = environment.getProperty("spring.application.name", "unknown-service");
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        // oldValue must be captured before the method runs - it describes the entity's state as
        // it was *before* this action, so it can only ever see the method's own arguments, never
        // #result (which does not exist yet at this point).
        Object oldValue = evaluateObject(auditable.oldValue(), evaluationContext(joinPoint, null));

        Object result = joinPoint.proceed();

        try {
            EvaluationContext context = evaluationContext(joinPoint, result);
            AuditEvent event = new AuditEvent(
                    auditable.eventType(),
                    auditable.action(),
                    auditable.entityType(),
                    evaluateString(auditable.entityId(), context),
                    evaluateString(auditable.caseId(), context),
                    currentUserProvider.current().map(user -> user.userId()).orElse("system"),
                    clientIp(),
                    serviceName,
                    evaluateString(auditable.comment(), context),
                    oldValue,
                    evaluateObject(auditable.newValue(), context),
                    LoggingContext.correlationId(),
                    Instant.now());
            auditRecorder.record(event);
        } catch (RuntimeException auditFailure) {
            // An audit trail problem must not fail the business operation it is describing -
            // the operation already completed successfully above.
            LOG.error("Failed to record audit event for action={}", auditable.action(), auditFailure);
        }

        return result;
    }

    private EvaluationContext evaluationContext(ProceedingJoinPoint joinPoint, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] parameterNames = PARAMETER_NAMES.getParameterNames(method);
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        context.setVariable("result", result);
        return context;
    }

    private static String evaluateString(String spelExpression, EvaluationContext context) {
        Object value = evaluateObject(spelExpression, context);
        return value == null ? null : value.toString();
    }

    private static Object evaluateObject(String spelExpression, EvaluationContext context) {
        if (spelExpression == null || spelExpression.isBlank()) {
            return null;
        }
        Expression expression = SPEL_PARSER.parseExpression(spelExpression);
        return expression.getValue(context);
    }

    private static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return HttpRequestUtils.clientIp(attributes.getRequest());
    }
}
