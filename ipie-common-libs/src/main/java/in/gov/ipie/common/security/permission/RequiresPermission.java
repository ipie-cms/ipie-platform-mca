package in.gov.ipie.common.security.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring a permission, checked via {@link PermissionEnforcer}
 * before the method runs - replaces a manual {@code permissionEnforcer.require(...)} call at the
 * top of the method body. A {@code PermissionCheckAspect} enforces this.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    /** The permission name, e.g. {@code UserPermissions.USER_WRITE}. */
    String value();
}
