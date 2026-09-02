package in.gov.ipie.common.security.keycloak.admin;

/**
 * The client id/secret pair for a newly created Keycloak client (see
 * {@link KeycloakAdminClient#createClient}). {@code clientSecret} is returned exactly once by
 * Keycloak's admin API at creation time - the caller must store it (e.g. in a secrets manager)
 * immediately; it cannot be retrieved again later, only regenerated (which invalidates the old one).
 */
public record KeycloakClientCredentials(String clientId, String clientSecret) {
}
