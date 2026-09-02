package in.gov.ipie.common.security.context;

import java.util.Optional;

/**
 * Application and domain code reads the caller through this port instead of touching
 * {@code SecurityContextHolder} directly - keeps business code independent of the security
 * framework and easy to unit test (just implement/stub this interface).
 */
public interface CurrentUserProvider {

    Optional<CurrentUser> current();

    default CurrentUser currentOrThrow() {
        return current().orElseThrow(() -> new IllegalStateException("No authenticated user in the current context"));
    }
}
