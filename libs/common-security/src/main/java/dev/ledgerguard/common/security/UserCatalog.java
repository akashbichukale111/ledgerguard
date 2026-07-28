package dev.ledgerguard.common.security;

import java.util.List;
import java.util.Optional;

/**
 * The demo user set, one account per {@link Role}.
 *
 * <p>This exists so the auth server and the resource servers agree on who exists. Two independent
 * hardcoded lists would drift, and the failure mode — login succeeds, then every subsequent request
 * 401s — is tedious to diagnose.
 *
 * <p><b>This is not an identity store.</b> Credentials are fixed, equal to the username, and
 * compiled in. It exists so the console has a working login during development and demos. A real
 * deployment replaces this with the organisation's directory; nothing outside this class encodes
 * the assumption that users are static.
 */
public final class UserCatalog {

    /** A demo account. The password is deliberately equal to the username. */
    public record DemoUser(String username, String password, Role role) {}

    private static final List<DemoUser> USERS = List.of(
            new DemoUser("admin", "admin", Role.ADMIN),
            new DemoUser("operations", "operations", Role.OPERATIONS),
            new DemoUser("analyst", "analyst", Role.ANALYST),
            new DemoUser("user", "user", Role.USER));

    private UserCatalog() {}

    public static List<DemoUser> users() {
        return USERS;
    }

    /**
     * Verifies a username and password.
     *
     * <p>The comparison is not constant-time. That is acceptable only because these credentials are
     * public knowledge; a real store must not copy this.
     */
    public static Optional<DemoUser> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        return USERS.stream()
                .filter(u -> u.username().equals(username) && u.password().equals(password))
                .findFirst();
    }
}
