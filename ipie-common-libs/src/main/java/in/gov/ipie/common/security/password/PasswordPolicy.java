package in.gov.ipie.common.security.password;

/**
 * The one definition of what makes an acceptable password, so every place a user chooses or changes
 * one applies the same rule and reports it the same way.
 *
 * <p><b>Keycloak enforces this, not the platform.</b> The realm carries an equivalent
 * {@code passwordPolicy} (see {@code deploy/keycloak/realm-export.json}), and that is the control:
 * it applies to every path that can set a credential, including Keycloak's own account console and
 * any administrative reset, which never pass through this codebase. The constants here exist so a
 * request DTO can reject a weak password early, with a message the user can act on, instead of
 * letting them fill in a form and then relaying a Keycloak error back. Validate here, rely on
 * Keycloak - and keep the two in step, since a mismatch shows up as a form that accepts a password
 * the identity provider then refuses.
 *
 * <p>Length carries more of the strength than the character classes do, which is why the floor is
 * 12 rather than the 8 this platform started with. The class requirements are kept because they are
 * a stated requirement for this platform, not because they add much on their own.
 */
public final class PasswordPolicy {

    /** Minimum length. Mirrors {@code length(12)} in the realm policy. */
    public static final int MIN_LENGTH = 12;

    /** Maximum length - guards against a denial-of-service through absurdly long inputs to hash. */
    public static final int MAX_LENGTH = 100;

    /**
     * At least one lower-case letter, one upper-case letter, one digit and one symbol, and at least
     * {@link #MIN_LENGTH} characters. Written as lookaheads so each requirement is independent and
     * the order of characters does not matter.
     *
     * <p>Must stay a compile-time constant: it is used in {@code @Pattern} annotations, which only
     * accept constant expressions.
     */
    public static final String REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,100}$";

    /** The message shown when {@link #REGEX} rejects a password. Describes the rule rather than which part failed. */
    public static final String MESSAGE =
            "must be at least 12 characters and include an upper-case letter, a lower-case letter, a number and a symbol";

    private PasswordPolicy() {
    }
}
