package in.gov.ipie.common.i18n.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import in.gov.ipie.common.observability.correlation.LoggingContext;

/**
 * Publishes the locale DispatcherServlet has already resolved (via the configured
 * {@code LocaleResolver}) to the SLF4J MDC, so every structured log line for this request shows
 * which language it was served in - the same treatment correlationId/traceId already get.
 *
 * <p>Runs as a {@link HandlerInterceptor}, not a {@code Filter}: DispatcherServlet only calls the
 * {@code LocaleResolver} and populates {@link LocaleContextHolder} after the filter chain has
 * already run, so a filter would still see the *previous* request's locale, not this one.
 */
public class LocaleLoggingInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoggingContext.putLocale(LocaleContextHolder.getLocale().toLanguageTag());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        LoggingContext.clearLocale();
    }
}
