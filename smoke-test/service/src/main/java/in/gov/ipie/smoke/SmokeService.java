package in.gov.ipie.smoke;

import in.gov.ipie.common.core.exception.NotFoundException;

/**
 * Touches a type from the published ipie-common-libs so the smoke test proves the shared library
 * resolves and compiles, not merely that the convention plugin applies.
 */
public final class SmokeService {

    private SmokeService() {
    }

    /** @return an exception from the platform's shared exception hierarchy */
    public static NotFoundException platformTypeResolves() {
        return new NotFoundException("smoke");
    }
}
