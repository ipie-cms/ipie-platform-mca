package in.gov.ipie.common.client.locale;

import java.io.IOException;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Carries the current request's resolved locale onto every outbound inter-service call, so the
 * downstream service's own {@code SupportedLocaleResolver} (common-i18n) sees the same language
 * the inbound request was served in, instead of falling back to its own default - required for a
 * call chain like {@code ipie-user-service -> ipie-communication-service} to keep a user's
 * chosen language for a notification triggered by their request. Mirrors
 * {@code CorrelationPropagationInterceptor}'s propagation pattern.
 *
 * <p>Only sends a header when {@link LocaleContextHolder} has an explicitly set locale context -
 * during real HTTP request handling, DispatcherServlet always sets one via the configured
 * {@code LocaleResolver} before any handler or outbound call runs, so this is effectively always
 * true in production. Guarding on it anyway avoids propagating a bare JVM/host default locale for
 * calls made outside any HTTP request (e.g. a scheduled job), which would otherwise leak
 * environment-specific behaviour into what should be a stable, request-scoped signal.
 */
public class LocalePropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (LocaleContextHolder.getLocaleContext() != null) {
            request.getHeaders().add(HttpHeaders.ACCEPT_LANGUAGE, LocaleContextHolder.getLocale().toLanguageTag());
        }
        return execution.execute(request, body);
    }
}
