package in.gov.ipie.common.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;

import in.gov.ipie.common.core.exception.ConflictException;

/**
 * Turns a database integrity violation into the error it actually is.
 *
 * <p><b>The problem this exists for.</b> Every repository used to catch
 * {@code DataIntegrityViolationException} and report one fixed message - in ipie-user-service, "a
 * user with the same username or email already exists". That catch is unconditional, so it also
 * answered for a duplicate <em>phone number</em>, a bad foreign key, a failed check constraint and,
 * in principle, a primary-key collision. A caller whose phone number was taken was told their email
 * was, and whoever debugged it started in the wrong place.
 *
 * <p><b>Why not simply retry.</b> Retrying looks like the obvious defence and is actively harmful as
 * a blanket rule: a duplicate-email insert would be retried too, turning one correct 409 into
 * several pointless attempts and then a 500. Only an id collision is fixable by trying again, which
 * is precisely why the constraint name has to be read before deciding anything.
 *
 * <p><b>And why even the id case is not retried here.</b> By the time the exception surfaces,
 * Hibernate has already assigned the generated id to the entity instance; saving it again reuses the
 * same value and collides identically. A genuine retry has to mint a new id, which only the caller
 * can do for its own entity. So this reports {@link IdCollisionException} - naming the cause and
 * saying a fresh id would work - rather than pretending to recover.
 *
 * <p>Usage: each repository declares the constraint names of the table it owns, because only it
 * knows them.
 *
 * <pre>{@code
 * private static final IntegrityViolations VIOLATIONS = IntegrityViolations.forTable()
 *         .primaryKey("users_pkey")
 *         .conflict("uq_users_email", "A user with this email address already exists")
 *         .conflict("uq_users_phone_number", "A user with this phone number already exists")
 *         .build();
 * ...
 * } catch (DataIntegrityViolationException e) {
 *     throw VIOLATIONS.translate(e);
 * }
 * }</pre>
 */
public final class IntegrityViolations {

    private final String primaryKeyConstraint;
    private final Map<String, String> conflictMessages;
    private final String fallbackMessage;

    private IntegrityViolations(Builder builder) {
        this.primaryKeyConstraint = builder.primaryKeyConstraint;
        this.conflictMessages = Map.copyOf(builder.conflictMessages);
        this.fallbackMessage = builder.fallbackMessage;
    }

    public static Builder forTable() {
        return new Builder();
    }

    /**
     * The constraint the database rejected on, when it reported one.
     *
     * <p>Read from Hibernate's {@code ConstraintViolationException} rather than from the JDBC driver:
     * Hibernate already extracts it, so this needs no dependency on a particular database's
     * exception type and keeps working if the driver changes. Empty when the failure carries no
     * constraint - a nullability violation, for instance - which is itself worth not guessing about.
     */
    public static Optional<String> constraintName(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return Optional.ofNullable(violation.getConstraintName());
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this declaration knows the constraint the database rejected on.
     *
     * <p>The error boundary holds every table's declaration at once, and asks this before
     * translating: a declaration that configured {@link Builder#otherwise} answers <em>any</em>
     * constraint with its fallback, which is right for the one table it describes and wrong for
     * another table's foreign key. Beside a repository's own {@code catch} the table is already
     * known, so there the fallback still applies as written.
     */
    public boolean declares(String constraintName) {
        if (constraintName == null) {
            return false;
        }
        return constraintName.equalsIgnoreCase(primaryKeyConstraint)
                || conflictMessages.containsKey(constraintName.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The exception to throw for this violation. Always returns rather than throws, so the call site
     * reads {@code throw VIOLATIONS.translate(e)} and the compiler still sees the method end.
     */
    public RuntimeException translate(DataIntegrityViolationException failure) {
        String constraint = constraintName(failure).orElse(null);
        if (constraint == null) {
            // No constraint name means this is not a uniqueness problem at all - a not-null or a
            // type failure. Reporting it as a conflict would be a guess, and a wrong one; the
            // original travels intact so the real cause reaches the log.
            return failure;
        }
        if (constraint.equalsIgnoreCase(primaryKeyConstraint)) {
            return new IdCollisionException(constraint, failure);
        }
        String message = conflictMessages.get(constraint.toLowerCase(java.util.Locale.ROOT));
        if (message != null) {
            return new ConflictException(message);
        }
        // A constraint this table did not declare: a foreign key, a check, or one added since. Named
        // in the message rather than absorbed into a generic conflict, because the name is the one
        // piece of information that makes it findable.
        return fallbackMessage == null
                ? failure
                : new ConflictException(fallbackMessage + " (constraint '" + constraint + "')");
    }

    /** Declares the constraint names of one table. */
    public static final class Builder {

        private final Map<String, String> conflictMessages = new LinkedHashMap<>();
        private String primaryKeyConstraint;
        private String fallbackMessage;

        /** The primary-key constraint, whose violation means the generated id collided. */
        public Builder primaryKey(String constraintName) {
            this.primaryKeyConstraint = constraintName;
            return this;
        }

        /** A unique constraint and the message that names the field the caller actually repeated. */
        public Builder conflict(String constraintName, String message) {
            this.conflictMessages.put(constraintName.toLowerCase(java.util.Locale.ROOT), message);
            return this;
        }

        /**
         * What to say for a constraint that was not declared here. Omit it and such a violation is
         * rethrown unchanged, which is the safer default: an unexpected constraint is a bug to see,
         * not a conflict to report.
         */
        public Builder otherwise(String message) {
            this.fallbackMessage = message;
            return this;
        }

        public IntegrityViolations build() {
            return new IntegrityViolations(this);
        }
    }
}
