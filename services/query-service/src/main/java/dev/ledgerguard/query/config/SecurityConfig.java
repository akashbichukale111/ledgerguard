package dev.ledgerguard.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import dev.ledgerguard.common.security.Role;

/**
 * Wires Spring Security so the {@code @PreAuthorize} annotations on controllers are actually
 * evaluated.
 *
 * <p>Without {@link EnableMethodSecurity} the method-security interceptor is never registered and
 * every {@code @PreAuthorize} is silently inert — the annotation compiles, reads correctly, and
 * enforces nothing. That was the state before this class existed.
 *
 * <p>Identity source: an in-memory user store, one user per {@link Role}. This is deliberately not
 * an authorization server — issuing and validating real tokens is deferred (see phase-16 report).
 * What it does give us is a genuine, testable authorization path: the authenticated principal's
 * granted authorities are what {@code hasRole(...)} and {@link Role#hasPermissionLevel} are checked
 * against, rather than a hardcoded constant.
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
     * One user per role, so a demo (and the authorization tests) can exercise each rung of the
     * hierarchy. Passwords come from configuration in any real deployment; the defaults here exist
     * only so {@code docker compose up} produces a working system.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var users = new InMemoryUserDetailsManager();
        for (Role role : Role.values()) {
            users.createUser(User.withUsername(role.name().toLowerCase())
                    .password(encoder.encode(role.name().toLowerCase()))
                    .authorities(role.getSpringRole())
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
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Everything else is authenticated; the per-operation role check lives on
                        // the handler methods so the RBAC matrix stays the single source of truth.
                        .anyRequest()
                        .authenticated())
                .httpBasic(basic -> {})
                .build();
    }
}
