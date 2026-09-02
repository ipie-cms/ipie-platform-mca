package in.gov.ipie.common.security.permission;

/**
 * Turns a business permission name (e.g. {@code "USER_WRITE"}, defined per service - never here)
 * into the Spring Security authority string used in {@code @PreAuthorize} checks, e.g.:
 *
 * <pre>{@code @PreAuthorize("hasAuthority('" + PermissionAuthorities.PREFIX + "USER_WRITE')")}</pre>
 *
 * Business code must check permissions, never role names directly (master standards doc, 5.5:
 * "Do not hard-code role names inside business logic").
 */
public final class PermissionAuthorities {

    public static final String PREFIX = "PERMISSION_";

    public static String authority(String permission) {
        return PREFIX + permission;
    }

    private PermissionAuthorities() {
    }
}
