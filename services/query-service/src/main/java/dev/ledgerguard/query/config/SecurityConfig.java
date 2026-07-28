package dev.ledgerguard.query.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import dev.ledgerguard.common.security.Role;
import dev.ledgerguard.common.security.UserCatalog;

/**
 * Wires Spring Security so the {@code @PreAuthorize} annotations on controllers are actually
 * evaluated.
 *
 * <p>Without {@link EnableMethodSecurity} the method-security interceptor is never registered and
 * every {@code @PreAuthorize} is silently inert — the annotation compiles, reads correctly, and
 * enforces nothing. That was the state before this class existed.
 *
 * <p>Identity source: {@link UserCatalog}, shared with the auth server so both agree on who exists.
 * Credentials travel as HTTP Basic; there is no bearer token and no token lifetime. What this does
 * give us is a genuine, testable authorization path — the authenticated principal's granted
 * authorities are what {@code hasRole(...)} and the RBAC matrix are checked against, rather than a
 * hardcoded constant.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Prefix Spring Security expects on authorities that {@code hasRole()} should match. */
    public static final String ROLE_PREFIX = "ROLE_";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Teaches Spring Security the role hierarchy that {@link Role} already declares.
     *
     * <p>Without this, {@code hasRole('USER')} means "holds the ROLE_USER authority literally", so
     * an administrator — who holds ROLE_ADMIN and nothing else — is denied every endpoint gated at
     * a lower rung. Logging in as admin would 403 on the entire console.
     *
     * <p>The chain is built from the enum's declaration order rather than written out, so adding a
     * role in the middle cannot leave the two definitions disagreeing.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        Role[] roles = Role.values();
        var chain = new StringBuilder();
        for (int i = 0; i < roles.length - 1; i++) {
            chain.append(roles[i].getSpringRole())
                    .append(" > ")
                    .append(roles[i + 1].getSpringRole())
                    .append('\n');
        }
        return RoleHierarchyImpl.fromHierarchy(chain.toString());
    }

    /**
     * Applies the hierarchy to {@code @PreAuthorize} expressions.
     *
     * <p>Declaring the {@link RoleHierarchy} bean alone is not enough — method security builds its
     * own expression handler and will not pick the hierarchy up unless it is handed one.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        var handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }

    /**
     * One user per role, so a demo (and the authorization tests) can exercise each rung of the
     * hierarchy. Passwords come from configuration in any real deployment; the defaults here exist
     * only so {@code docker compose up} produces a working system.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var users = new InMemoryUserDetailsManager();
        for (UserCatalog.DemoUser demo : UserCatalog.users()) {
            users.createUser(User.withUsername(demo.username())
                    .password(encoder.encode(demo.password()))
                    .authorities(demo.role().getSpringRole())
                    .build());
        }
        return users;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless API consumed by a SPA and by service-to-service calls; there is no
                // browser form login to protect, so the CSRF token exchange buys nothing here.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Preflight carries no credentials by definition; rejecting it would break
                        // every cross-origin call before the real request is ever sent.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        // Everything else is authenticated; the per-operation role check lives on
                        // the handler methods so the RBAC matrix stays the single source of truth.
                        .anyRequest()
                        .authenticated())
                .httpBasic(basic -> {})
                .build();
    }

    /**
     * Allows the console's dev server to call the API directly.
     *
     * <p>Origins are configurable and default to the Vite dev server. In the packaged deployment
     * nginx proxies {@code /api} from the same origin, so this path is not exercised at all.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "http://127.0.0.1:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Basic credentials ride on the Authorization header, so the browser must be told the
        // cross-origin response may be read.
        config.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
