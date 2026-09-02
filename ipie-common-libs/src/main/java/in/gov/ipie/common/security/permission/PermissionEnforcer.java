package in.gov.ipie.common.security.permission;

/**
 * Programmatic permission gate for business operations. Controllers/application services call
 * {@link #require(String)} with a service-defined permission constant instead of hard-coding role
 * names (master standards doc, 5.5). Preferred over {@code @PreAuthorize} in this template because
 * it also honours the {@code ipie.security.enabled=false} local-dev escape hatch uniformly - an
 * HTTP-level permit-all filter chain alone does not stop {@code @PreAuthorize} from still denying
 * every call when there is no authenticated principal.
 */
public interface PermissionEnforcer {

    /** @throws org.springframework.security.access.AccessDeniedException if the caller lacks {@code permission} */
    void require(String permission);
}
