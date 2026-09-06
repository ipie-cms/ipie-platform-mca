package in.gov.ipie.common.security.permission;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

/** Enforces {@link RequiresPermission} before the annotated method runs. */
@Aspect
public class PermissionCheckAspect {

    private final PermissionEnforcer permissionEnforcer;

    public PermissionCheckAspect(PermissionEnforcer permissionEnforcer) {
        this.permissionEnforcer = permissionEnforcer;
    }

    @Before("@annotation(requiresPermission)")
    public void checkPermission(RequiresPermission requiresPermission) {
        permissionEnforcer.require(requiresPermission.value());
    }
}
