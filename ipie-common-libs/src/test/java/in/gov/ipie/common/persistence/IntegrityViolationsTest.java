package in.gov.ipie.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import in.gov.ipie.common.core.exception.ConflictException;

class IntegrityViolationsTest {

    private static final IntegrityViolations VIOLATIONS = IntegrityViolations.forTable()
            .primaryKey("users_pkey")
            .conflict("uq_users_email", "A user with this email address already exists")
            .conflict("uq_users_phone_number", "A user with this phone number already exists")
            .build();

    /** What Spring hands a repository: its own exception wrapping Hibernate's, wrapping the driver's. */
    private static DataIntegrityViolationException violationOf(String constraintName) {
        org.hibernate.exception.ConstraintViolationException hibernate =
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value violates unique constraint",
                        new SQLException("23505"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    @Test
    void aDuplicatePhoneNumberIsReportedAsAPhoneNumberConflict() {
        // The defect this class exists for: every violation used to be reported as a duplicate
        // username or email, so a caller whose phone number was taken was told about their email.
        RuntimeException translated = VIOLATIONS.translate(violationOf("uq_users_phone_number"));

        assertThat(translated).isInstanceOf(ConflictException.class);
        assertThat(translated).hasMessageContaining("phone number");
        assertThat(translated).hasMessageNotContaining("email");
    }

    @Test
    void aDuplicateEmailIsReportedAsAnEmailConflict() {
        RuntimeException translated = VIOLATIONS.translate(violationOf("uq_users_email"));

        assertThat(translated).isInstanceOf(ConflictException.class);
        assertThat(translated).hasMessageContaining("email");
    }

    @Test
    void aPrimaryKeyCollisionIsNotAConflictWithTheCaller() {
        // A conflict says "your request is wrong and will fail again"; an id collision says "the
        // platform generated a duplicate and a fresh id would work". Sending someone chasing a
        // duplicate email when the id collided is the expensive kind of wrong.
        RuntimeException translated = VIOLATIONS.translate(violationOf("users_pkey"));

        assertThat(translated).isInstanceOf(IdCollisionException.class);
        assertThat(translated).hasMessageContaining("users_pkey");
        assertThat(translated).isNotInstanceOf(ConflictException.class);
        // The failed statement travels with it: the one log line a collision produces is read by
        // someone investigating id generation, and the constraint name alone does not say enough.
        assertThat(translated.getCause()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUndeclaredConstraintIsRethrownRatherThanGuessedAt() {
        // A foreign key or a check constraint is a bug to see, not a conflict to report.
        DataIntegrityViolationException original = violationOf("fk_users_organisation_id");

        assertThat(VIOLATIONS.translate(original)).isSameAs(original);
    }

    @Test
    void aViolationCarryingNoConstraintNameIsRethrownUntouched() {
        DataIntegrityViolationException noConstraint =
                new DataIntegrityViolationException("null value in column \"email\"");

        assertThat(VIOLATIONS.translate(noConstraint)).isSameAs(noConstraint);
    }

    @Test
    void constraintNamesAreMatchedWithoutRegardToCase() {
        // Postgres folds unquoted identifiers to lower case; a declaration written in upper case
        // must not silently stop matching.
        RuntimeException translated = VIOLATIONS.translate(violationOf("UQ_USERS_EMAIL"));

        assertThat(translated).isInstanceOf(ConflictException.class);
        assertThat(translated).hasMessageContaining("email");
    }

    @Test
    void anUndeclaredConstraintIsNamedWhenAFallbackIsConfigured() {
        IntegrityViolations withFallback = IntegrityViolations.forTable()
                .primaryKey("users_pkey")
                .otherwise("The record could not be saved")
                .build();

        RuntimeException translated = withFallback.translate(violationOf("chk_users_pillar_scope"));

        assertThat(translated).isInstanceOf(ConflictException.class);
        // The constraint name is the one piece of information that makes it findable.
        assertThat(translated).hasMessageContaining("chk_users_pillar_scope");
    }

    @Test
    void theConstraintNameIsFoundThroughNestedCauses() {
        assertThat(IntegrityViolations.constraintName(violationOf("uq_users_email")))
                .contains("uq_users_email");
        assertThat(IntegrityViolations.constraintName(new RuntimeException("unrelated"))).isEmpty();
    }

    @Test
    void aDeclarationKnowsWhichConstraintsAreItsOwn() {
        // What the error boundary asks before translating, so that a table configured with a
        // fallback message cannot answer for a constraint belonging to some other table.
        assertThat(VIOLATIONS.declares("uq_users_email")).isTrue();
        assertThat(VIOLATIONS.declares("UQ_USERS_EMAIL")).isTrue();
        assertThat(VIOLATIONS.declares("users_pkey")).isTrue();
        assertThat(VIOLATIONS.declares("fk_users_organisation_id")).isFalse();
        assertThat(VIOLATIONS.declares(null)).isFalse();
    }

    @Test
    void aDeclarationWithoutAPrimaryKeyClaimsNothingByAccident() {
        // A table written through another aggregate declares only its unique rules; the absent
        // primary key must not match every constraint name it is offered.
        IntegrityViolations uniqueOnly = IntegrityViolations.forTable()
                .conflict("uq_user_professional_roles", "That professional role is already recorded")
                .build();

        assertThat(uniqueOnly.declares("uq_user_professional_roles")).isTrue();
        assertThat(uniqueOnly.declares("users_pkey")).isFalse();
    }
}
