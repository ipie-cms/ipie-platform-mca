package in.gov.ipie.common.security.keycloak.directory;

import java.util.UUID;

/**
 * The account half of the platform's identity directory: create an authentication subject, set its
 * password, stamp an attribute on it. Implemented by {@code KeycloakUserManagementClient} and
 * wired by its auto-configuration - a service injects this interface, never that class.
 *
 * <p>Split from {@link RealmRoleDirectory} rather than shipped as one nine-method interface
 * because the two halves have different callers and different Keycloak privileges: account
 * operations need {@code manage-users}, role-definition operations need {@code manage-realm}, and
 * {@code deploy/keycloak/realm-export.json} grants them to different service clients. A service
 * that only provisions accounts should not compile against role management it is not permitted to
 * perform (SOLID/Interface Segregation).
 *
 * <p>Kept technology-neutral in name and signature: nothing here mentions Keycloak, so the one
 * concrete adapter can be replaced without touching a caller. The exception it throws
 * ({@code KeycloakAdminException}) still names the current implementation, which is the honest
 * state of things and the next thing to generalise if a second directory ever appears.
 */
public interface AccountDirectory {

    /**
     * Creates an enabled account with <b>no credentials</b> and returns its directory id. The
     * account exists and can be granted roles, but nobody can log into it until a password is set.
     * This is the variant asynchronous provisioning uses - a password cannot travel on an event
     * without being written to the outbox table and every broker that relays it.
     */
    UUID createUser(String username, String email, String firstName, String lastName);

    /** As above, but sets an initial password in the same call. */
    UUID createUser(String username, String email, String firstName, String lastName, String password);

    /** Sets (or replaces) the account's password. */
    void setPassword(UUID accountId, String password);

    /** Sets a single custom attribute on the account, preserving the attributes already there. */
    void setUserAttribute(UUID accountId, String attributeName, String value);
}
