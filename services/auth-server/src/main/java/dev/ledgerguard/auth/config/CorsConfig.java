package dev.ledgerguard.auth.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin policy for the login endpoint, driven by configuration.
 *
 * <p>{@code LoginController} carried a hardcoded {@code http://localhost:3000}, which is correct
 * for the Vite dev server and wrong everywhere else. In the hosted demo the console is served from
 * a Vercel domain, so the browser's preflight would have been rejected and login would fail with a
 * CORS error rather than a credentials error — an unusually confusing way to break.
 *
 * <p><b>No wildcard default.</b> An unset property allows nothing. Defaulting to {@code *} on an
 * endpoint that accepts a username and password would let any site on the internet POST
 * credentials here and read the response.
 *
 * <p>Note that when the console is reached through the gateway (as the hosted demo is set up), the
 * browser sees a single origin and no preflight happens at all. This exists for the direct-to-service
 * setup and for local development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${ledgerguard.cors.allowed-origins:http://localhost:3000}") String origins) {
        this.allowedOrigins = origins.isBlank() ? List.of() : List.of(origins.split("\\s*,\\s*"));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            log.info("no CORS origins configured — cross-origin browser calls will be rejected");
            return;
        }

        log.info("CORS allowed origins: {}", allowedOrigins);

        registry.addMapping("/api/v1/auth/**")
                // Patterns, not exact origins, so a Vercel preview deployment
                // (https://ledgerguard-*.vercel.app) works without redeploying the backend for
                // every preview URL.
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
