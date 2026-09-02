package in.gov.ipie.common.security.context;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import in.gov.ipie.common.security.permission.PermissionAuthorities;

/** Default {@link CurrentUserProvider} backed by the Spring Security JWT authentication token. */
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return Optional.empty();
        }

        Jwt jwt = jwtAuthentication.getToken();
        Set<String> permissions = jwtAuthentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(PermissionAuthorities.PREFIX))
                .map(authority -> authority.substring(PermissionAuthorities.PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());

        String username = jwt.getClaimAsString("preferred_username");
        return Optional.of(new CurrentUser(jwt.getSubject(), username, permissions));
    }
}
