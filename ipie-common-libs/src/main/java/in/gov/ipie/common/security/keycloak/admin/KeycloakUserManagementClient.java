package in.gov.ipie.common.security.keycloak.admin;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Routine, per-request Keycloak user/role management for a business microservice: creating a
 * user at registration, syncing a role definition, assigning realm roles to a user.
 *
 * <p>Deliberately <b>not</b> built on {@link KeycloakAdminClient} - that class authenticates with
 * master-realm admin credentials and is explicitly documented as a one-off provisioning tool, not
 * safe for a service's regular request path (see its Javadoc). This class instead authenticates
 * as the calling service's own Keycloak client via the OAuth2 client-credentials grant, the same
 * {@link OAuth2AuthorizedClientManager} mechanism {@code common-client}'s {@code
 * OAuth2ClientCredentialsInterceptor} already uses for outbound inter-service calls - the calling
 * service's client only needs the narrow {@code realm-management} client roles it was actually
 * granted (typically {@code manage-users}, and {@code manage-realm} only for services that also
 * manage role definitions - see {@code deploy/keycloak/realm-export.json}), not full realm admin.
 */
public class KeycloakUserManagementClient {

    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final KeycloakUserManagementProperties properties;

    public KeycloakUserManagementClient(
            RestClient.Builder restClientBuilder,
            OAuth2AuthorizedClientManager authorizedClientManager,
            KeycloakUserManagementProperties properties) {
        this.restClient = restClientBuilder.build();
        this.authorizedClientManager = authorizedClientManager;
        this.properties = properties;
    }

    /**
     * Creates an enabled Keycloak user with <b>no credentials</b> and returns its Keycloak user id.
     * The account exists and can be granted roles, but nobody can log into it until a password is
     * set (see {@link #setPassword}).
     *
     * <p>This is the variant asynchronous provisioning uses. A password cannot travel on an event
     * without being written to the outbox table and every broker that relays it, so the account is
     * created empty and the user sets their own password later, through the link in the
     * verification email.
     *
     * @throws KeycloakAdminException if the calling service's client-credentials token cannot be
     *     obtained, or Keycloak rejects the create (e.g. {@code 409} if the username/email already
     *     exists there)
     */
    public UUID createUser(String username, String email, String firstName, String lastName) {
        return createUser(username, email, firstName, lastName, null);
    }

    /**
     * Creates an enabled Keycloak user and returns its Keycloak user id. A non-null {@code
     * password} is set as a permanent (non-temporary) credential; null or blank creates the account
     * without any credential.
     *
     * @throws KeycloakAdminException if the calling service's client-credentials token cannot be
     *     obtained, or Keycloak rejects the create (e.g. {@code 409} if the username/email already
     *     exists there)
     */
    public UUID createUser(String username, String email, String firstName, String lastName, String password) {
        Map<String, Object> newUser = new LinkedHashMap<>(Map.of(
                "username", username,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", false));
        if (password != null && !password.isBlank()) {
            newUser.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        }

        try {
            URI location = restClient.post()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/users")
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .body(newUser)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
            if (location == null) {
                throw new KeycloakAdminException(
                        "Keycloak did not return a Location header for the newly created user '" + username + "'");
            }
            String path = location.getPath();
            return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to create Keycloak user '" + username + "' (" + e.getStatusCode()
                            + ") - the username or email may already exist", e);
        }
    }

    /**
     * Sets a permanent password on an existing Keycloak user, replacing any current one.
     *
     * <p>The counterpart to {@link #createUser(String, String, String, String)}: asynchronous
     * provisioning creates the account without credentials, and this is what makes it usable, called
     * when the user follows the link in their verification email. Keeping the two apart is what
     * keeps the password off the event that requested the account.
     *
     * <p>{@code temporary: false} - the user chose this password themselves, so Keycloak must not
     * demand they change it at first login.
     *
     * @throws KeycloakAdminException if the token cannot be obtained or Keycloak rejects the reset
     *     (e.g. the password violates the realm's policy)
     */
    public void setPassword(UUID keycloakUserId, String password) {
        String url = properties.getBaseUrl() + "/admin/realms/" + properties.getRealm()
                + "/users/" + keycloakUserId + "/reset-password";
        try {
            restClient.put()
                    .uri(url)
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .body(Map.of("type", "password", "value", password, "temporary", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to set the password for Keycloak user " + keycloakUserId + ": " + e.getStatusCode(), e);
        }
    }

    /**
     * Sets a single custom attribute on an existing Keycloak user - e.g. stamping {@code ipie_id}
     * onto the newly-created user at registration completion, so it becomes a reliable claim on
     * every token this realm issues (see the {@code ipie-identity} client scope).
     *
     * <p>Read-modify-write: Keycloak's user-update endpoint treats a supplied {@code attributes}
     * map as a full replacement, not a merge, so this fetches the user's current attributes first
     * and only adds/overwrites {@code attributeName} within them - found the hard way, by another
     * attribute this same user already had (set independently) silently disappearing otherwise.
     *
     * @throws KeycloakAdminException if Keycloak rejects the read or the update (e.g. the user
     *     does not exist)
     */
    @SuppressWarnings("unchecked")
    public void setUserAttribute(UUID keycloakUserId, String attributeName, String value) {
        String token = bearerToken();
        String userUrl = properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/users/" + keycloakUserId;
        try {
            Map<String, Object> currentUser = restClient.get()
                    .uri(userUrl)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> attributes = currentUser != null && currentUser.get("attributes") instanceof Map
                    ? new java.util.HashMap<>((Map<String, Object>) currentUser.get("attributes"))
                    : new java.util.HashMap<>();
            attributes.put(attributeName, List.of(value));

            // Merge into the representation just fetched, and send the whole thing. Keycloak's user
            // update REPLACES the representation rather than patching it, so a body of only
            // {"attributes": ...} silently clears every field it omits - email, firstName,
            // lastName, emailVerified. That is what this call used to send, and because it runs
            // immediately after createUser, every account provisioned through this client lost its
            // email and name the moment its ipie_id was stamped on. The account then fails login
            // with "Account is not fully set up", which points nowhere near this line.
            Map<String, Object> updatedUser = currentUser == null
                    ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(currentUser);
            updatedUser.put("attributes", attributes);

            restClient.put()
                    .uri(userUrl)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(updatedUser)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to set attribute '" + attributeName + "' on Keycloak user " + keycloakUserId
                            + " (" + e.getStatusCode() + ")", e);
        }
    }

    /**
     * Creates realm role {@code roleName} if it does not already exist - idempotent, safe to call
     * every time a role is created/updated in the owning service's own database.
     *
     * @throws KeycloakAdminException if Keycloak rejects the create for a reason other than the
     *     role already existing
     */
    public void ensureRealmRole(String roleName, String description) {
        Map<String, Object> role = Map.of("name", roleName, "description", description == null ? "" : description);
        try {
            restClient.post()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/roles")
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .body(role)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) {
                throw new KeycloakAdminException(
                        "Failed to create Keycloak realm role '" + roleName + "' (" + e.getStatusCode() + ")", e);
            }
        }
    }

    /**
     * Adds {@code roleNames} to {@code keycloakUserId}'s realm role mappings - Keycloak's {@code
     * POST .../role-mappings/realm} is additive (existing mappings not in {@code roleNames} are
     * left alone, not removed), so callers that need to revoke a role must do so as a separate
     * explicit step. Looks each role up by name first since the endpoint needs the role's id, not
     * just its name.
     *
     * @throws KeycloakAdminException if any named role does not exist in Keycloak yet (call {@link
     *     #ensureRealmRole} first), or the assignment call itself fails
     */
    public void assignRealmRoles(UUID keycloakUserId, List<String> roleNames) {
        String token = bearerToken();
        List<Map<String, Object>> roleRepresentations = roleNames.stream()
                .map(roleName -> fetchRealmRole(roleName, token))
                .toList();

        try {
            restClient.post()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/users/" + keycloakUserId
                            + "/role-mappings/realm")
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(roleRepresentations)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to assign realm roles " + roleNames + " to Keycloak user " + keycloakUserId
                            + " (" + e.getStatusCode() + ")", e);
        }
    }

    /**
     * Makes realm role {@code roleName} a composite of exactly {@code childRoleNames}, adding what
     * is missing and removing what is no longer wanted - the diff is computed here so callers can
     * simply declare the desired end state.
     *
     * <p>This is what makes a synced role actually grant anything. A role created by {@link
     * #ensureRealmRole} carries a name and nothing else, so a token issued for a user holding it
     * has no permissions behind it; the permissions a role confers are themselves realm roles, and
     * a role only confers them by containing them as composites. A bare role looks entirely correct
     * in the admin console while granting nothing, which is why this is called on every role
     * create/update rather than left to whoever remembers.
     *
     * @throws KeycloakAdminException if any named role does not exist in Keycloak (call {@link
     *     #ensureRealmRole} for each first), or Keycloak rejects the change
     */
    public void syncRealmRoleComposites(String roleName, Set<String> childRoleNames) {
        String token = bearerToken();
        String compositesUrl = properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/roles/" + roleName
                + "/composites";

        Set<String> current = fetchCompositeNames(roleName, compositesUrl, token);
        List<Map<String, Object>> toAdd = childRoleNames.stream()
                .filter(name -> !current.contains(name))
                .map(name -> fetchRealmRole(name, token))
                .toList();
        List<Map<String, Object>> toRemove = current.stream()
                .filter(name -> !childRoleNames.contains(name))
                .map(name -> fetchRealmRole(name, token))
                .toList();

        if (!toAdd.isEmpty()) {
            exchangeComposites(HttpMethod.POST, compositesUrl, toAdd, token, roleName, "add");
        }
        if (!toRemove.isEmpty()) {
            exchangeComposites(HttpMethod.DELETE, compositesUrl, toRemove, token, roleName, "remove");
        }
    }

    /**
     * Removes {@code roleNames} from {@code keycloakUserId}'s realm role mappings - the counterpart
     * to {@link #assignRealmRoles}, which is additive and never revokes.
     *
     * @throws KeycloakAdminException if any named role does not exist in Keycloak, or the removal
     *     call itself fails
     */
    public void removeRealmRoles(UUID keycloakUserId, List<String> roleNames) {
        String token = bearerToken();
        List<Map<String, Object>> roleRepresentations = roleNames.stream()
                .map(roleName -> fetchRealmRole(roleName, token))
                .toList();

        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/users/" + keycloakUserId
                            + "/role-mappings/realm")
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(roleRepresentations)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to remove realm roles " + roleNames + " from Keycloak user " + keycloakUserId
                            + " (" + e.getStatusCode() + ")", e);
        }
    }

    /**
     * Deletes realm role {@code roleName}. A {@code 404} is treated as success - the caller's
     * intent (the role is gone from Keycloak) already holds, and a role deleted in the owning
     * database must not be blocked from deletion by having already vanished here.
     *
     * @throws KeycloakAdminException if Keycloak rejects the delete for any other reason
     */
    public void deleteRealmRole(String roleName) {
        try {
            restClient.delete()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/roles/" + roleName)
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw new KeycloakAdminException(
                        "Failed to delete Keycloak realm role '" + roleName + "' (" + e.getStatusCode() + ")", e);
            }
        }
    }

    private Set<String> fetchCompositeNames(String roleName, String compositesUrl, String token) {
        try {
            List<RoleRepresentation> composites = restClient.get()
                    .uri(compositesUrl)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<RoleRepresentation>>() {});
            return composites == null
                    ? Set.of()
                    : composites.stream().map(RoleRepresentation::name).collect(Collectors.toSet());
        } catch (HttpStatusCodeException e) {
            // A role with no composites yet answers 200 with [], so 404 means the role itself is
            // missing - the same condition fetchRealmRole reports, worded the same way.
            throw new KeycloakAdminException(
                    "Realm role '" + roleName + "' was not found in Keycloak (" + e.getStatusCode()
                            + ") - call ensureRealmRole first", e);
        }
    }

    private void exchangeComposites(
            HttpMethod method, String compositesUrl, List<Map<String, Object>> roles, String token, String roleName,
            String operation) {
        try {
            restClient.method(method)
                    .uri(compositesUrl)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(roles)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to " + operation + " composites on Keycloak realm role '" + roleName + "' ("
                            + e.getStatusCode() + ")", e);
        }
    }

    private Map<String, Object> fetchRealmRole(String roleName, String token) {
        try {
            RoleRepresentation role = restClient.get()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/roles/" + roleName)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(RoleRepresentation.class);
            if (role == null) {
                throw new KeycloakAdminException("Keycloak returned an empty response body for realm role '" + roleName + "'");
            }
            return Map.of("id", role.id(), "name", role.name());
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Realm role '" + roleName + "' was not found in Keycloak (" + e.getStatusCode()
                            + ") - call ensureRealmRole first", e);
        }
    }

    private String bearerToken() {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(properties.getRegistrationId())
                .principal(properties.getRegistrationId())
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new KeycloakAdminException(
                    "Unable to obtain a client-credentials access token for registration '" + properties.getRegistrationId()
                            + "' - check spring.security.oauth2.client.registration." + properties.getRegistrationId()
                            + ".* and that this service's Keycloak client has serviceAccountsEnabled=true");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RoleRepresentation(String id, @JsonProperty("name") String name) {
    }
}
