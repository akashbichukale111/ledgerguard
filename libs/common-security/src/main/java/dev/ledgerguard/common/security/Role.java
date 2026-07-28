package dev.ledgerguard.common.security;

/**
 * Role-based access control roles for LedgerGuard.
 *
 * <p>Hierarchical: ADMIN ⊇ OPERATIONS ⊇ ANALYST ⊇ USER.
 */
public enum Role {
    /** Full system access: users, roles, configuration, audit trails. */
    ADMIN("ROLE_ADMIN"),

    /** Operational access: replay DLT, view audit trails, manage projections. */
    OPERATIONS("ROLE_OPERATIONS"),

    /** Read-only analysis: view transactions, reconciliation, projections. */
    ANALYST("ROLE_ANALYST"),

    /** Basic user access: view own transactions. */
    USER("ROLE_USER");

    private final String springRole;

    Role(String springRole) {
        this.springRole = springRole;
    }

    public String getSpringRole() {
        return springRole;
    }

    /** Check if this role has at least the permission level of another. */
    public boolean hasPermissionLevel(Role required) {
        return this.ordinal() <= required.ordinal();
    }
}
