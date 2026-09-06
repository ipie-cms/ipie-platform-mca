package in.gov.ipie.common.security.config;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.security.context.SecurityContextCurrentUserProvider;
import in.gov.ipie.common.security.permission.DefaultPermissionEnforcer;
import in.gov.ipie.common.security.permission.PermissionCheckAspect;
import in.gov.ipie.common.resilience.config.YamlPropertySourceFactory;
import in.gov.ipie.common.security.permission.PermissionEnforcer;

/**
 * Approved security baseline for every iPIE backend service: validates the JWT (signature,
 * issuer, audience, expiry - via {@code spring.security.oauth2.resourceserver.jwt.*}), maps
 * permissions onto Spring Security authorities and enforces authentication by default on every
 * endpoint except the configured public paths.
 *
 * <p>A service overrides this only by defining its own {@code SecurityFilterChain} bean -
 * business modules must not bypass this baseline (master standards doc, section 16).
 */
@AutoConfiguration
@EnableMethodSecurity
@EnableConfigurationProperties(IpieSecurityProperties.class)
@PropertySource(value = "classpath:ipie-security-defaults.yml", factory = YamlPropertySourceFactory.class)
public class ResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    @ConditionalOnProperty(prefix = "ipie.security", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain ipieSecurityFilterChain(
            HttpSecurity http,
            IpieSecurityProperties properties,
            @Qualifier("ipieCorsConfigurationSource") CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return configureBaseline(http, properties, corsConfigurationSource).build();
    }

    /**
     * The chain-building logic {@link #ipieSecurityFilterChain} applies, extracted so a service
     * that needs one additional opt-in filter (e.g. {@code HmacSignatureVerificationFilter},
     * {@code RateLimitFilter}) can define its own {@code SecurityFilterChain} bean - the only
     * documented way to override this auto-configuration's default (see this class's own Javadoc)
     * - without re-typing this baseline and risking it drifting from every other service's. Such a
     * service calls this, adds its filter(s) via {@code http.addFilterBefore(...)}, then calls
     * {@code http.build()} itself; every other service is unaffected, since
     * {@link #ipieSecurityFilterChain} above is still the only bean that runs unless a service
     * defines its own.
     *
     * @return the same, still-open {@code http} builder passed in, for the caller to add filters
     *     to and build
     */
    public static HttpSecurity configureBaseline(
            HttpSecurity http, IpieSecurityProperties properties, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(new JwtPermissionsConverter(properties.getPermissionsClaim()));

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    properties.getPublicPaths().forEach(path -> authorize.requestMatchers(path).permitAll());
                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));

        return http;
    }

    /**
     * Local-development-only escape hatch: {@code ipie.security=false} permits every request
     * instead of validating a JWT, so the API can be exercised without a running identity
     * provider. Must never be set outside a developer machine or the local docker-compose stack
     * - see {@code application-local.yml} in ipie-service-template.
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    @ConditionalOnProperty(prefix = "ipie.security", name = "enabled", havingValue = "false")
    public SecurityFilterChain ipieDisabledSecurityFilterChain(
            HttpSecurity http, @Qualifier("ipieCorsConfigurationSource") CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Cross-origin access for browser-based frontends (e.g. a local Vite dev server). Empty by
     * default via {@link IpieSecurityProperties#getCorsAllowedOrigins()} - a service opts in by
     * setting {@code ipie.security.cors-allowed-origins}.
     *
     * <p>Named explicitly and wired via {@code @Qualifier} rather than
     * {@code @ConditionalOnMissingBean(CorsConfigurationSource.class)}: Spring MVC's own
     * {@code HandlerMappingIntrospector} bean also implements {@link CorsConfigurationSource} (it
     * exists so Spring Security can delegate to {@code @CrossOrigin}/{@code WebMvcConfigurer} CORS
     * mappings), and it is registered before this auto-configuration runs - a type-based
     * {@code @ConditionalOnMissingBean} would silently defer to it instead of this bean, and no
     * CORS headers would ever be sent.
     */
    @Bean("ipieCorsConfigurationSource")
    @ConditionalOnMissingBean(name = "ipieCorsConfigurationSource")
    public CorsConfigurationSource ipieCorsConfigurationSource(IpieSecurityProperties properties) {
        List<String> allowedOrigins = properties.getCorsAllowedOrigins().stream()
                .filter(origin -> !origin.isBlank())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    public CurrentUserProvider currentUserProvider() {
        return new SecurityContextCurrentUserProvider();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionEnforcer.class)
    public PermissionEnforcer permissionEnforcer(CurrentUserProvider currentUserProvider, IpieSecurityProperties properties) {
        return new DefaultPermissionEnforcer(currentUserProvider, properties);
    }

    /**
     * Enforces {@code @RequiresPermission} on controller methods, replacing a manual
     * {@code permissionEnforcer.require(...)} call at the top of the method body. AspectJ proxying
     * is already enabled platform-wide via {@code AuditAutoConfiguration}'s
     * {@code @EnableAspectJAutoProxy}, which - like this auto-configuration - is always present
     * (both ship in the same merged {@code AutoConfiguration.imports}).
     */
    @Bean
    public PermissionCheckAspect permissionCheckAspect(PermissionEnforcer permissionEnforcer) {
        return new PermissionCheckAspect(permissionEnforcer);
    }
}
