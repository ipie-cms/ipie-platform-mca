package in.gov.ipie.common.security.context;

import java.util.Set;

/** The authenticated caller for the current request, resolved from the validated JWT. */
public record CurrentUser(String userId, String username, Set<String> permissions) {

    public CurrentUser {
        permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
