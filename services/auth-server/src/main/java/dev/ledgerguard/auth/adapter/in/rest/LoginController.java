package dev.ledgerguard.auth.adapter.in.rest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.common.security.RbacMatrix;
import dev.ledgerguard.common.security.UserCatalog;

/**
 * Verifies a username and password for the operations console.
 *
 * <p><b>This issues no token.</b> It returns the caller's roles and a pre-encoded HTTP Basic
 * credential, because Basic is what the resource services actually accept today
 * ({@code query-service}'s {@code SecurityConfig}). The console stores that string and replays it
 * on subsequent calls. Calling the returned value a "token" would overstate it: it is the
 * credential itself, base64-encoded, with no expiry, no signature and no revocation.
 *
 * <p>The consequence, stated plainly because it matters: <b>the console holds a replayable
 * credential in browser storage.</b> That is acceptable for a local demo and not acceptable in
 * production. The upgrade is this endpoint minting a short-lived signed JWT and the resource
 * services validating it as a bearer token; the authorization decisions downstream do not change,
 * only where identity comes from. See {@code docs/phase-reports/phase-17.md}.
 *
 * <p>The endpoint also returns the operations each role may perform, so the console can hide
 * controls the caller cannot use. That is a usability affordance only — every one of those
 * operations is independently enforced server-side.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {
    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    public record LoginRequest(String username, String password) {}

    public record AuthenticatedUser(String name, List<String> roles, List<String> permittedOperations) {}

    public record LoginResponse(String token, String scheme, AuthenticatedUser user) {}

    public record LoginFailure(String message) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(new LoginFailure("username and password are required"));
        }

        Optional<UserCatalog.DemoUser> authenticated = UserCatalog.authenticate(request.username(), request.password());

        if (authenticated.isEmpty()) {
            // One message for both "no such user" and "wrong password": distinguishing them tells
            // an attacker which usernames are real.
            log.info("failed login attempt for username={}", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new LoginFailure("Invalid credentials"));
        }

        UserCatalog.DemoUser user = authenticated.get();
        String basic = Base64.getEncoder()
                .encodeToString((user.username() + ":" + user.password()).getBytes(StandardCharsets.UTF_8));

        log.info("successful login for username={} role={}", user.username(), user.role());

        return ResponseEntity.ok(new LoginResponse(
                basic,
                "Basic",
                new AuthenticatedUser(
                        user.username(),
                        List.of(user.role().name()),
                        RbacMatrix.operationsForRole(user.role()).stream()
                                .map(Enum::name)
                                .sorted()
                                .toList())));
    }
}
