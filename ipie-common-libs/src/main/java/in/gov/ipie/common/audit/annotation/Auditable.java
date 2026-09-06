package in.gov.ipie.common.audit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import in.gov.ipie.common.audit.model.AuditEventType;

/**
 * Marks an application-service method as an auditable business action. An {@code AuditAspect}
 * records one {@link in.gov.ipie.common.audit.model.AuditEvent} per successful invocation - business
 * code must not bypass this for actions that require an audit trail (master standards doc,
 * section 16).
 *
 * <p>{@code entityId}, {@code caseId}, {@code comment}, {@code oldValue} and {@code newValue} are
 * all SpEL expressions evaluated against the method's parameters (by name); {@code entityId},
 * {@code caseId}, {@code comment} and {@code newValue} additionally see {@code #result} once the
 * method has returned, while {@code oldValue} is evaluated <em>before</em> the method runs (so it
 * can only reference the method's arguments, never {@code #result}) - it exists to capture the
 * entity's state as it was *before* this action changes it. For example, on
 * {@code createUser(CreateUserCommand command)} returning a {@code User}:
 * {@code @Auditable(action = "USER_CREATED", entityType = "USER", entityId = "#result.id()")}.
 *
 * <p>{@code comment} is deliberately never a literal string in application code - it is meant to
 * be the human-supplied reason for the action (e.g. {@code comment = "#command.comment()"},
 * reading a field the caller's own request DTO carries), left empty/not-provided when a
 * particular action has no natural human-supplied reason (e.g. self-registration). This platform's
 * convention is that such a field is optional at the API layer (nullable, no
 * {@code @NotBlank}/{@code @NotNull}) but expected to be mandatory wherever a real UI collects it
 * from a human before submitting the request - see the relevant request DTO's own Javadoc.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

    AuditEventType eventType() default AuditEventType.BUSINESS;

    /** Stable action name recorded on the audit event, e.g. {@code "USER_CREATED"}. */
    String action();

    /** Business entity type the action applies to, e.g. {@code "USER"}. */
    String entityType();

    /** SpEL expression resolving the affected entity's id. Empty means "not recorded". */
    String entityId() default "";

    /** SpEL expression resolving the business case id, when applicable. Empty means "not recorded". */
    String caseId() default "";

    /**
     * SpEL expression resolving the human-supplied reason for this action, typically
     * {@code "#command.comment()"} or similar. Empty means "not provided" - never a literal
     * description of the action (that's what {@link #action()} is for).
     */
    String comment() default "";

    /**
     * SpEL expression, evaluated against the method's arguments <em>before</em> it runs,
     * resolving the entity's prior state (e.g. {@code "#organisationRepository.findById(...)"} is
     * not valid here - only the method's own parameters are in scope; fetch the prior entity in
     * the method body and pass it as part of the command if it's needed here). Empty means "not
     * recorded" - the common case for pure-create actions, where there is no "before".
     */
    String oldValue() default "";

    /**
     * SpEL expression, evaluated after the method returns (parameters plus {@code #result} in
     * scope), resolving the entity's new state. Empty means "not recorded".
     */
    String newValue() default "";
}
