package in.gov.ipie.common.security.keycloak;

/**
 * Carries the current request's refresh token (if the caller chose to supply one - see {@code
 * RefreshTokenCaptureFilter}), so {@code common-client}'s {@code TokenRelayInterceptor} can use
 * {@link KeycloakTokenClient} to mint a fresh access token when the JWT it would otherwise relay
 * has expired.
 *
 * <p><b>Passing a refresh token beyond its original client is a real security trade-off, not a
 * free convenience.</b> A refresh token is normally long-lived and highly sensitive; forwarding
 * it to a backend microservice (even indirectly, via a header the original client opts into
 * sending) widens its blast radius if that microservice - or anything downstream of it - is ever
 * compromised. Only wire {@code RefreshTokenCaptureFilter} in for a service where the calling
 * client, the transport, and this service's own handling of the value have all been reviewed for
 * that risk; most inter-service calls should prefer {@code common-client}'s default
 * {@code CLIENT_CREDENTIALS} mode instead, which never touches a refresh token at all.
 *
 * <p>Thread-local, mirroring {@code common-observability}'s {@code LoggingContext} - always
 * {@link #clear()} in a {@code finally} block (see {@code RefreshTokenCaptureFilter}) so a value
 * never leaks onto a pooled thread's next, unrelated request.
 */
public final class RefreshTokenContextHolder {

    private static final ThreadLocal<String> CURRENT_REFRESH_TOKEN = new ThreadLocal<>();

    private RefreshTokenContextHolder() {
    }

    public static void set(String refreshToken) {
        CURRENT_REFRESH_TOKEN.set(refreshToken);
    }

    /** The current request's refresh token, or {@code null} if the caller did not supply one. */
    public static String get() {
        return CURRENT_REFRESH_TOKEN.get();
    }

    public static void clear() {
        CURRENT_REFRESH_TOKEN.remove();
    }
}
