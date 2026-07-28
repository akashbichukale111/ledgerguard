package dev.ledgerguard.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RbacMatrix")
class RbacMatrixTest {

    @Nested
    @DisplayName("Permission checks")
    class PermissionChecks {

        @Test
        void adminCanPerformAnyOperation() {
            for (RbacMatrix.Operation op : RbacMatrix.Operation.values()) {
                assertThat(RbacMatrix.canPerform(Role.ADMIN, op))
                        .as("ADMIN should have permission for " + op)
                        .isTrue();
            }
        }

        @Test
        void operationsRoleCanReplayDlt() {
            assertThat(RbacMatrix.canPerform(Role.OPERATIONS, RbacMatrix.Operation.DLT_REPLAY))
                    .isTrue();
        }

        @Test
        void operationsRoleCannotManageRoles() {
            assertThat(RbacMatrix.canPerform(Role.OPERATIONS, RbacMatrix.Operation.ROLE_MANAGE))
                    .isFalse();
        }

        @Test
        void analystCanViewButNotReplay() {
            assertThat(RbacMatrix.canPerform(Role.ANALYST, RbacMatrix.Operation.DLT_VIEW))
                    .isTrue();
            assertThat(RbacMatrix.canPerform(Role.ANALYST, RbacMatrix.Operation.DLT_REPLAY))
                    .isFalse();
        }

        @Test
        void userCanOnlyViewTransactions() {
            assertThat(RbacMatrix.canPerform(Role.USER, RbacMatrix.Operation.TRANSACTION_VIEW))
                    .isTrue();
            assertThat(RbacMatrix.canPerform(Role.USER, RbacMatrix.Operation.AUDIT_VIEW))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Role permissions")
    class RolePermissions {

        @Test
        void roleOperationsIncludesExpectedOperations() {
            Set<RbacMatrix.Operation> analystOps = RbacMatrix.operationsForRole(Role.ANALYST);

            assertThat(analystOps)
                    .contains(
                            RbacMatrix.Operation.AUDIT_VIEW,
                            RbacMatrix.Operation.DLT_VIEW,
                            RbacMatrix.Operation.PROJECTION_VIEW,
                            RbacMatrix.Operation.TRANSACTION_VIEW)
                    .doesNotContain(RbacMatrix.Operation.DLT_REPLAY, RbacMatrix.Operation.ROLE_MANAGE);
        }

        @Test
        void adminHasMorePermissionsThanAnalyst() {
            Set<RbacMatrix.Operation> adminOps = RbacMatrix.operationsForRole(Role.ADMIN);
            Set<RbacMatrix.Operation> analystOps = RbacMatrix.operationsForRole(Role.ANALYST);

            assertThat(adminOps).containsAll(analystOps);
            assertThat(adminOps).hasSizeGreaterThan(analystOps.size());
        }
    }

    @Nested
    @DisplayName("Operation permissions")
    class OperationPermissions {

        @Test
        void dltReplayOnlyAllowsAdminAndOperations() {
            Set<Role> roles = RbacMatrix.rolesForOperation(RbacMatrix.Operation.DLT_REPLAY);

            assertThat(roles).contains(Role.ADMIN, Role.OPERATIONS).doesNotContain(Role.ANALYST, Role.USER);
        }

        @Test
        void transactionViewAllowedForAllRoles() {
            Set<Role> roles = RbacMatrix.rolesForOperation(RbacMatrix.Operation.TRANSACTION_VIEW);

            assertThat(roles).containsAll(java.util.Arrays.asList(Role.values()));
        }

        @Test
        void roleManageOnlyAllowsAdmin() {
            Set<Role> roles = RbacMatrix.rolesForOperation(RbacMatrix.Operation.ROLE_MANAGE);

            assertThat(roles).containsExactly(Role.ADMIN);
        }
    }

    @Nested
    @DisplayName("Role hierarchy")
    class RoleHierarchy {

        @Test
        void adminHasPermissionOfOperations() {
            assertThat(Role.ADMIN.hasPermissionLevel(Role.OPERATIONS)).isTrue();
        }

        @Test
        void operationsHasPermissionOfAnalyst() {
            assertThat(Role.OPERATIONS.hasPermissionLevel(Role.ANALYST)).isTrue();
        }

        @Test
        void userCannotAccessAnalystLevel() {
            assertThat(Role.USER.hasPermissionLevel(Role.ANALYST)).isFalse();
        }

        @Test
        void operationsCannotAccessAdminLevel() {
            assertThat(Role.OPERATIONS.hasPermissionLevel(Role.ADMIN)).isFalse();
        }
    }
}
