package dev.ledgerguard.common.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Role-Based Access Control matrix defining what each role can do.
 *
 * <p>Rows: operations (what you can do). Columns: roles (who can do it). Cell: whether role can
 * perform operation.
 */
public final class RbacMatrix {

    /**
     * Operations that can be protected by RBAC.
     *
     * <p>Format: "resource:action" (e.g., "dlt:replay", "audit:view", "projection:rebuild").
     */
    public enum Operation {
        // DLT/Retry operations
        DLT_REPLAY("dlt:replay", "Replay dead-letter messages"),
        DLT_VIEW("dlt:view", "View dead-letter topic and entries"),

        // Audit chain operations
        AUDIT_VIEW("audit:view", "View audit chain entries"),
        AUDIT_VERIFY("audit:verify", "Verify audit chain integrity"),

        // Projection operations
        PROJECTION_VIEW("projection:view", "View transaction projections"),
        PROJECTION_REBUILD("projection:rebuild", "Rebuild projection from scratch"),
        PROJECTION_LAG("projection:lag", "View projection lag metrics"),

        // Transaction operations
        TRANSACTION_VIEW("transaction:view", "View transactions"),
        TRANSACTION_RECONCILE("transaction:reconcile", "Trigger reconciliation"),

        // Admin operations
        ROLE_MANAGE("role:manage", "Manage user roles"),
        CONFIG_VIEW("config:view", "View system configuration"),
        CONFIG_EDIT("config:edit", "Edit system configuration");

        private final String code;
        private final String description;

        Operation(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    // RBAC matrix: operation -> set of roles that can perform it
    private static final java.util.Map<Operation, Set<Role>> MATRIX =
            Collections.unmodifiableMap(new java.util.EnumMap<Operation, Set<Role>>(Operation.class) {
                {
                    // DLT operations
                    put(Operation.DLT_REPLAY, set(Role.ADMIN, Role.OPERATIONS));
                    put(Operation.DLT_VIEW, set(Role.ADMIN, Role.OPERATIONS, Role.ANALYST));

                    // Audit operations
                    put(Operation.AUDIT_VIEW, set(Role.ADMIN, Role.OPERATIONS, Role.ANALYST));
                    put(Operation.AUDIT_VERIFY, set(Role.ADMIN, Role.OPERATIONS));

                    // Projection operations
                    put(Operation.PROJECTION_VIEW, set(Role.ADMIN, Role.OPERATIONS, Role.ANALYST, Role.USER));
                    put(Operation.PROJECTION_REBUILD, set(Role.ADMIN, Role.OPERATIONS));
                    put(Operation.PROJECTION_LAG, set(Role.ADMIN, Role.OPERATIONS, Role.ANALYST));

                    // Transaction operations
                    put(Operation.TRANSACTION_VIEW, set(Role.ADMIN, Role.OPERATIONS, Role.ANALYST, Role.USER));
                    put(Operation.TRANSACTION_RECONCILE, set(Role.ADMIN, Role.OPERATIONS));

                    // Admin operations
                    put(Operation.ROLE_MANAGE, set(Role.ADMIN));
                    put(Operation.CONFIG_VIEW, set(Role.ADMIN, Role.OPERATIONS));
                    put(Operation.CONFIG_EDIT, set(Role.ADMIN));
                }
            });

    /**
     * Check if a role can perform an operation.
     *
     * @param role the role to check
     * @param operation the operation to perform
     * @return true if the role is permitted, false otherwise
     */
    public static boolean canPerform(Role role, Operation operation) {
        Set<Role> allowedRoles = MATRIX.getOrDefault(operation, Collections.emptySet());
        return allowedRoles.contains(role);
    }

    /**
     * Get all roles that can perform an operation.
     *
     * @param operation the operation
     * @return unmodifiable set of roles
     */
    public static Set<Role> rolesForOperation(Operation operation) {
        return Collections.unmodifiableSet(MATRIX.getOrDefault(operation, Collections.emptySet()));
    }

    /**
     * Get all operations that a role can perform.
     *
     * @param role the role
     * @return unmodifiable set of operations
     */
    public static Set<Operation> operationsForRole(Role role) {
        Set<Operation> operations = EnumSet.noneOf(Operation.class);
        for (java.util.Map.Entry<Operation, Set<Role>> entry : MATRIX.entrySet()) {
            if (entry.getValue().contains(role)) {
                operations.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(operations);
    }

    @SafeVarargs
    private static <E extends Enum<E>> Set<E> set(E... elements) {
        return Collections.unmodifiableSet(EnumSet.of(elements[0], elements));
    }

    private RbacMatrix() {}
}
