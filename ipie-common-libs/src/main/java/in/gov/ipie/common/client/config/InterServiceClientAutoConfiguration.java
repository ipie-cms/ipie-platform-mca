package in.gov.ipie.common.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import in.gov.ipie.common.client.DefaultInterServiceClient;
import in.gov.ipie.common.client.InterServiceClient;
import in.gov.ipie.common.client.correlation.CorrelationPropagationInterceptor;
import in.gov.ipie.common.client.locale.LocalePropagationInterceptor;
import in.gov.ipie.common.client.security.HmacSigningInterceptor;
import in.gov.ipie.common.client.security.InterServiceMtlsSslBundles;
import in.gov.ipie.common.client.security.OAuth2ClientCredentialsInterceptor;
import in.gov.ipie.common.client.security.OpaAuthorizationInterceptor;
import in.gov.ipie.common.client.security.TokenRelayInterceptor;
import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.resilience.config.IpieResilienceHttpProperties;
import in.gov.ipie.common.security.hmac.HmacSigningProperties;
import in.gov.ipie.common.security.keycloak.KeycloakTokenClient;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Wires {@link InterServiceClient} for every service that depends on {@code common-client}: a
 * {@link RestClient} carrying common-resilience's connect/response timeout (already applied to
 * the injected {@code RestClient.Builder} by common-resilience's own {@code RestClientCustomizer}),
 * this module's correlation-id interceptor, and exactly one security interceptor selected by
 * {@code ipie.client.security.mode} (see {@link InterServiceSecurityProperties}).
 *
 * <p>Mode selection branches on the bound {@link InterServiceSecurityProperties.Mode} enum
 * directly in {@link #interServiceClient} rather than via three {@code @ConditionalOnProperty}-gated
 * configurations - {@code @ConditionalOnProperty}'s {@code havingValue} is a raw, case-sensitive
 * string compare against the environment, which does not go through the same relaxed binding
 * (kebab-case, case-insensitivity) that {@code @ConfigurationProperties} applies to the enum
 * field; branching on the already-bound enum avoids that mismatch entirely.
 *
 * <p><b>Adding {@code common-client} as a dependency must never, by itself, fail a service's
 * startup.</b> {@link #interServiceClient} is a plain singleton {@code @Bean} - Spring
 * eagerly instantiates it during context refresh whether or not anything actually calls
 * {@link InterServiceClient} yet, so its factory method must never throw just because
 * {@code CLIENT_CREDENTIALS} (the default mode) has no matching
 * {@code spring.security.oauth2.client.registration.*}. It resolves
 * {@link OAuth2AuthorizedClientManager} with a plain {@code getIfAvailable()} (returning
 * {@code null}, never throwing) and hands that straight to
 * {@link OAuth2ClientCredentialsInterceptor}, which defers the failure to the first actual
 * {@code CLIENT_CREDENTIALS}-mode call - see that class's Javadoc.
 *
 * <p>The one exception to that rule is {@link InterServiceMtlsConfiguration}, which <em>does</em>
 * fail startup when its key material is wrong. It is allowed to, for the same reason HMAC signing
 * is: it is never on unless a deployment explicitly turned it on, so a broken keystore path is a
 * mistake in something someone just asked for, not a surprise inherited from a dependency. mTLS
 * that silently degrades to plain HTTP is worse than mTLS that refuses to start.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        InterServiceClientProperties.class, InterServiceSecurityProperties.class, OpaAuthorizationProperties.class,
        InterServiceMtlsProperties.class})
public class InterServiceClientAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(InterServiceClientAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CorrelationPropagationInterceptor.class)
    public CorrelationPropagationInterceptor correlationPropagationInterceptor() {
        return new CorrelationPropagationInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(LocalePropagationInterceptor.class)
    public LocalePropagationInterceptor localePropagationInterceptor() {
        return new LocalePropagationInterceptor();
    }

    /**
     * Only registered when {@code spring.security.oauth2.client.registration.*} is actually
     * configured (that's what produces a {@link ClientRegistrationRepository} bean via
     * {@code spring-boot-starter-oauth2-client}'s own autoconfiguration) - a service that has not
     * configured a registration yet still starts regardless of which {@code
     * ipie.client.security.mode} it selects (see {@link #interServiceClient}).
     */
    @Bean
    @ConditionalOnBean(ClientRegistrationRepository.class)
    @ConditionalOnMissingBean(OAuth2AuthorizedClientManager.class)
    public OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository clientRegistrationRepository) {
        OAuth2AuthorizedClientService authorizedClientService = new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }

    /**
     * Split out from {@link #interServiceClient} to keep that method's own parameter count under
     * Checkstyle's {@code ParameterNumber} limit - resolving the mode-selected security
     * interceptor is a self-contained concern on its own. {@code null} means no security
     * interceptor at all ({@code NONE} mode, after logging its warning).
     *
     * <p>Both {@code ObjectProvider.getIfAvailable()} calls below return {@code null} rather than
     * throwing when the corresponding bean is absent - this method runs during eager singleton
     * instantiation at context refresh, so throwing here would fail the whole application's
     * startup merely for depending on common-client, not for actually attempting a call. Both
     * {@link OAuth2ClientCredentialsInterceptor} and {@link TokenRelayInterceptor} defer that
     * failure (or, for {@code TokenRelayInterceptor}, simply skip the refresh) to the first real
     * call instead - see their Javadoc.
     *
     * <p>{@code NONE} mode returns a no-op passthrough rather than {@code null} - a {@code @Bean}
     * method returning {@code null} produces Spring's internal {@code NullBean} placeholder,
     * which behaves inconsistently once other post-processors are involved; a real, harmless
     * interceptor sidesteps that entirely.
     *
     * <p>HMAC request signing (see {@link HmacSigningInterceptor}) is composed on top of whichever
     * mode-selected interceptor above, when {@code ipie.client.security.hmac-signing-enabled=true}
     * - it is an additive control, not a fourth {@code Mode}, so it is wired here rather than
     * adding a fifth parameter to {@link #interServiceClient} (which would exceed Checkstyle's
     * parameter-count limit). Unlike the mode-selection defaults above, misconfiguring this one
     * (enabling it without a matching {@code ipie.security.hmac.keys} entry) fails fast at bean
     * creation rather than being deferred to first call - this is always an explicit opt-in, never
     * a default a service could be surprised by, so catching the mistake immediately at startup is
     * more useful than deferring it.
     *
     * <p>OPA-based authorization ({@link OpaAuthorizationInterceptor}) is composed *outermost* of
     * all of the above when {@code ipie.client.security.opa.enabled=true} - it answers "is this
     * call allowed at all", which should run before any signing/authentication work happens, not
     * after. Also additive, also opt-in, also wired here for the same parameter-count reason as
     * HMAC signing above; unlike HMAC signing's key-mismatch case, misconfiguration here (OPA
     * unreachable) is a runtime condition, not a startup-detectable one, so it is handled per-call
     * via {@link OpaAuthorizationProperties#isFailClosed()} instead of failing at bean creation.
     *
     * <p>{@link LocalePropagationInterceptor} (common-i18n) is composed just inside OPA, for the
     * same "avoid another parameter on {@link #interServiceClient}" reason as HMAC signing above -
     * it is a plain header addition, not a security decision, so its position relative to
     * auth/signing does not matter functionally.
     */
    @Bean
    @ConditionalOnMissingBean(name = "interServiceSecurityInterceptor")
    public ClientHttpRequestInterceptor interServiceSecurityInterceptor(
            InterServiceSecurityProperties securityProperties,
            HmacSigningProperties hmacSigningProperties,
            OpaAuthorizationProperties opaAuthorizationProperties,
            ObjectProvider<OAuth2AuthorizedClientManager> authorizedClientManager,
            ObjectProvider<KeycloakTokenClient> keycloakTokenClient,
            LocalePropagationInterceptor localePropagationInterceptor,
            Environment environment) {
        ClientHttpRequestInterceptor authInterceptor = switch (securityProperties.getMode()) {
            case CLIENT_CREDENTIALS -> new OAuth2ClientCredentialsInterceptor(
                    authorizedClientManager.getIfAvailable(), securityProperties.getRegistrationId());
            case TOKEN_RELAY -> new TokenRelayInterceptor(keycloakTokenClient.getIfAvailable());
            case NONE -> {
                LOG.warn("ipie.client.security.mode=NONE - outbound inter-service calls made through "
                        + "InterServiceClient will not carry an Authorization header. Only use this for calls to a "
                        + "genuinely public/unauthenticated downstream endpoint.");
                yield (request, body, execution) -> execution.execute(request, body);
            }
        };

        ClientHttpRequestInterceptor authAndSigning;
        if (!securityProperties.isHmacSigningEnabled()) {
            authAndSigning = authInterceptor;
        } else {
            String keyId = securityProperties.getHmacSigningKeyId();
            String secret = keyId == null ? null : hmacSigningProperties.getKeys().get(keyId);
            if (secret == null) {
                throw new IllegalStateException(
                        "ipie.client.security.hmac-signing-enabled=true requires ipie.client.security.hmac-signing-key-id "
                                + "to name an entry present in ipie.security.hmac.keys (found key id '" + keyId + "')");
            }
            authAndSigning = compose(new HmacSigningInterceptor(keyId, secret), authInterceptor);
        }

        ClientHttpRequestInterceptor withLocale = compose(localePropagationInterceptor, authAndSigning);

        if (!opaAuthorizationProperties.isEnabled()) {
            return withLocale;
        }
        OpaAuthorizationInterceptor opaInterceptor = new OpaAuthorizationInterceptor(
                RestClient.builder(), opaAuthorizationProperties, callingServiceName(environment));
        return compose(opaInterceptor, withLocale);
    }

    private static String callingServiceName(Environment environment) {
        return environment.getProperty("spring.application.name", "unknown-service");
    }

    /** Chains {@code first} to run (and mutate the request) before {@code second} does, both ahead of the real execution. */
    private static ClientHttpRequestInterceptor compose(ClientHttpRequestInterceptor first, ClientHttpRequestInterceptor second) {
        return (request, body, execution) -> first.intercept(request, body, (req, b) -> second.intercept(req, b, execution));
    }

    @Bean
    @ConditionalOnMissingBean
    public ResilienceRegistries resilienceRegistries(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            BulkheadRegistry bulkheadRegistry,
            RateLimiterRegistry rateLimiterRegistry) {
        return new ResilienceRegistries(circuitBreakerRegistry, retryRegistry, bulkheadRegistry, rateLimiterRegistry);
    }

    /**
     * Everything {@link InterServiceClient} adds to an outbound request, as one interceptor:
     * correlation-id propagation first, then the security stack
     * {@link #interServiceSecurityInterceptor} assembled.
     *
     * <p>Exists as its own bean purely so {@link #interServiceClient} stays inside Checkstyle's
     * {@code ParameterNumber} limit once the optional mTLS request factory is added to it - the
     * same reason {@link #interServiceSecurityInterceptor} was split out. Composing the two here
     * produces exactly the order the two separate {@code requestInterceptor(...)} registrations
     * used to: correlation runs first, and mutates the request before anything signs or
     * authenticates it.
     */
    @Bean
    @ConditionalOnMissingBean(name = "interServiceRequestInterceptor")
    public ClientHttpRequestInterceptor interServiceRequestInterceptor(
            CorrelationPropagationInterceptor correlationPropagationInterceptor,
            @Qualifier("interServiceSecurityInterceptor") ClientHttpRequestInterceptor interServiceSecurityInterceptor) {
        return compose(correlationPropagationInterceptor, interServiceSecurityInterceptor);
    }

    /**
     * @param interServiceMtlsRequestFactory present only when
     *     {@code ipie.client.security.mtls.enabled=true} (see {@link InterServiceMtlsConfiguration});
     *     {@code getIfAvailable()} yields {@code null} otherwise and the client keeps the
     *     plain-HTTP request factory common-resilience already installed on the builder.
     */
    @Bean
    @ConditionalOnMissingBean
    public InterServiceClient interServiceClient(
            RestClient.Builder restClientBuilder,
            InterServiceClientProperties properties,
            ResilienceRegistries resilienceRegistries,
            @Qualifier("interServiceRequestInterceptor") ClientHttpRequestInterceptor interServiceRequestInterceptor,
            @Qualifier("interServiceMtlsRequestFactory") ObjectProvider<ClientHttpRequestFactory> interServiceMtlsRequestFactory,
            AuditRecorder auditRecorder,
            Environment environment) {
        restClientBuilder.requestInterceptor(interServiceRequestInterceptor);

        ClientHttpRequestFactory mtlsRequestFactory = interServiceMtlsRequestFactory.getIfAvailable();
        if (mtlsRequestFactory != null) {
            restClientBuilder.requestFactory(mtlsRequestFactory);
        }

        return new DefaultInterServiceClient(
                restClientBuilder, properties, resilienceRegistries, auditRecorder, callingServiceName(environment));
    }

    /**
     * Mutual TLS for inter-service calls - see {@link InterServiceMtlsProperties} for why this is
     * an application-level control on a platform whose documented end state is a service mesh, and
     * for how it sits alongside (not instead of) the existing HMAC request signing.
     *
     * <p>Kept as a separate, {@code @ConditionalOnClass}-guarded nested configuration - not a
     * {@code @Bean} method directly on the outer class - so that {@code RestClient}'s absence from
     * a future consumer's classpath cannot fail classloading/verification of this
     * auto-configuration itself; only this nested class ever references that type. The
     * {@code @ConditionalOnProperty} guard is what keeps this <b>off by default</b>: with no
     * {@code ipie.client.security.mtls.enabled=true} the bean definition below is never
     * registered, {@link #interServiceClient}'s {@code ObjectProvider} stays empty, and a service
     * that has never heard of mTLS behaves exactly as it did before this class existed.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnProperty(prefix = "ipie.client.security.mtls", name = "enabled", havingValue = "true")
    static class InterServiceMtlsConfiguration {

        /**
         * Scoped deliberately to the inter-service client, rather than registered as a
         * {@link org.springframework.boot.web.client.RestClientCustomizer} the way
         * common-resilience's timeout customizer is.
         *
         * <p>A {@code RestClientCustomizer} applies to <em>every</em> auto-configured
         * {@code RestClient.Builder} in the service - which here would include
         * {@code KeycloakTokenClient}, {@code KeycloakAdminClient} and ipie-user-service's pillar
         * IdP back-channel clients. Those talk to identity providers outside the platform's own
         * CA, so replacing their trust store with the internal one would break them the moment
         * mTLS was switched on, in a way that has nothing to do with inter-service traffic. mTLS
         * is a statement about calls between iPIE services; this is the bean that only those calls
         * go through.
         *
         * <p>The connect/response timeouts are re-stated here from {@link IpieResilienceHttpProperties}
         * because {@code RestClient.Builder#requestFactory} <em>replaces</em> the factory
         * common-resilience installed rather than decorating it - and a factory built from bare
         * {@code ClientHttpRequestFactorySettings.defaults()} has no timeouts at all. Silently
         * dropping every remote call's timeout while enabling a security feature is precisely the
         * kind of trade nobody would agree to if it were visible, so it is made visible here: the
         * same two properties, read from the same class, applied to the same settings object as
         * the SSL bundle.
         */
        @Bean
        @ConditionalOnMissingBean(name = "interServiceMtlsRequestFactory")
        ClientHttpRequestFactory interServiceMtlsRequestFactory(
                InterServiceMtlsProperties mtlsProperties,
                IpieResilienceHttpProperties httpProperties,
                ResourceLoader resourceLoader) {
            LOG.info("ipie.client.security.mtls.enabled=true - inter-service calls will present this service's client "
                    + "certificate from {} and validate peers against {}. This authenticates the transport; it does not "
                    + "replace ipie.client.security.hmac-signing-enabled, which authenticates individual messages and "
                    + "survives TLS termination at a proxy or mesh sidecar.",
                    mtlsProperties.getKeyStore(),
                    mtlsProperties.getTrustStore() == null ? "the JVM default trust store" : mtlsProperties.getTrustStore());

            return ClientHttpRequestFactoryBuilder.detect().build(settings(mtlsProperties, httpProperties, resourceLoader));
        }

        /**
         * Split out from the bean method above only so the three things that must all be true of
         * the resulting transport - the SSL bundle is present, and neither timeout was lost when
         * the request factory was replaced - are assertable from a plain unit test.
         * {@code ClientHttpRequestFactorySettings} is a record, so every one of them is readable;
         * the built {@code ClientHttpRequestFactory} exposes none of them.
         */
        static ClientHttpRequestFactorySettings settings(
                InterServiceMtlsProperties mtlsProperties,
                IpieResilienceHttpProperties httpProperties,
                ResourceLoader resourceLoader) {
            SslBundle sslBundle = InterServiceMtlsSslBundles.from(mtlsProperties, resourceLoader);
            return ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(httpProperties.getConnectTimeout())
                    .withReadTimeout(httpProperties.getResponseTimeout())
                    .withSslBundle(sslBundle);
        }
    }
}
