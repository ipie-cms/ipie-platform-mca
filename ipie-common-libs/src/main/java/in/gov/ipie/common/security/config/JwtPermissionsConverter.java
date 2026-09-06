package in.gov.ipie.common.security.config;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import in.gov.ipie.common.security.permission.PermissionAuthorities;

/**
 * Reads the configured permissions claim off the validated JWT and turns each value into a
 * {@code PERMISSION_*} {@link GrantedAuthority}, so {@code @PreAuthorize("hasAuthority(...)")}
 * checks work the same way in every service regardless of the identity provider's token shape.
 */
public class JwtPermissionsConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String permissionsClaim;

    public JwtPermissionsConverter(String permissionsClaim) {
        this.permissionsClaim = permissionsClaim;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList(permissionsClaim);
        if (permissions == null) {
            return List.of();
        }
        return permissions.stream()
                .map(PermissionAuthorities::authority)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableList());
    }
}
