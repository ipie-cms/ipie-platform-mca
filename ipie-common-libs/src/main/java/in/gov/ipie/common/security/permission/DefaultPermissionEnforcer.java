package in.gov.ipie.common.security.permission;

import org.springframework.security.access.AccessDeniedException;

import in.gov.ipie.common.security.config.IpieSecurityProperties;
import in.gov.ipie.common.security.context.CurrentUserProvider;

public class DefaultPermissionEnforcer implements PermissionEnforcer {

    private final CurrentUserProvider currentUserProvider;
    private final IpieSecurityProperties properties;

    public DefaultPermissionEnforcer(CurrentUserProvider currentUserProvider, IpieSecurityProperties properties) {
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
    }

    @Override
    public void require(String permission) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean granted = currentUserProvider.currentOrThrow().hasPermission(permission);
        if (!granted) {
            throw new AccessDeniedException("Missing required permission: " + permission);
        }
    }
}
