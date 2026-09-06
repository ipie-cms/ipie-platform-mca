package in.gov.ipie.common.security.keycloak.directory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The authorization half of the platform's identity directory: define realm roles, compose them
 * out of permissions, grant and revoke them. Implemented by {@code KeycloakUserManagementClient}
 * and wired by its auto-configuration - a service injects this interface, never that class.
 *
 * <p>See {@link AccountDirectory} for why the two are separate interfaces over one adapter.
 *
 * <p>Note that this interface still bundles the define/compose operations with the grant/revoke
 * ones, which the platform's own permission model separates ({@code RBAC_DEFINE} vs
 * {@code ROLES_MANAGE}, split 2026-08-13). That split belongs at the application boundary
 * ({@code RoleService}) first; splitting it here as well before that happens would leave the two
 * layers disagreeing about where the line falls.
 */
public interface RealmRoleDirectory {

    /** Creates the role if it does not exist; updates its description if it does. */
    void ensureRealmRole(String roleName, String description);

    /** Makes {@code roleName} a composite of exactly {@code childRoleNames} - additive and subtractive. */
    void syncRealmRoleComposites(String roleName, Set<String> childRoleNames);

    /** Grants the named realm roles to an account. */
    void assignRealmRoles(UUID accountId, List<String> roleNames);

    /** Revokes the named realm roles from an account. */
    void removeRealmRoles(UUID accountId, List<String> roleNames);

    /** Deletes the realm role outright, revoking it from everyone who held it. */
    void deleteRealmRole(String roleName);
}
