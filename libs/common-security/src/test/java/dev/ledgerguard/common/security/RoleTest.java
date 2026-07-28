package dev.ledgerguard.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Role")
class RoleTest {

    @Nested
    @DisplayName("Role enum values")
    class RoleEnumValues {

        @Test
        void adminHasRolePrefix() {
            assertThat(Role.ADMIN.toString()).isEqualTo("ADMIN");
        }

        @Test
        void operationsHasRolePrefix() {
            assertThat(Role.OPERATIONS.toString()).isEqualTo("OPERATIONS");
        }

        @Test
        void analystHasRolePrefix() {
            assertThat(Role.ANALYST.toString()).isEqualTo("ANALYST");
        }

        @Test
        void userHasRolePrefix() {
            assertThat(Role.USER.toString()).isEqualTo("USER");
        }
    }

    @Nested
    @DisplayName("Permission levels")
    class PermissionLevels {

        @Test
        void adminHasAllPermissions() {
            assertThat(Role.ADMIN.hasPermissionLevel(Role.ADMIN)).isTrue();
            assertThat(Role.ADMIN.hasPermissionLevel(Role.OPERATIONS)).isTrue();
            assertThat(Role.ADMIN.hasPermissionLevel(Role.ANALYST)).isTrue();
            assertThat(Role.ADMIN.hasPermissionLevel(Role.USER)).isTrue();
        }

        @Test
        void operationsHasOperationsAndBelow() {
            assertThat(Role.OPERATIONS.hasPermissionLevel(Role.ADMIN)).isFalse();
            assertThat(Role.OPERATIONS.hasPermissionLevel(Role.OPERATIONS)).isTrue();
            assertThat(Role.OPERATIONS.hasPermissionLevel(Role.ANALYST)).isTrue();
            assertThat(Role.OPERATIONS.hasPermissionLevel(Role.USER)).isTrue();
        }

        @Test
        void analystHasAnalystAndBelow() {
            assertThat(Role.ANALYST.hasPermissionLevel(Role.ADMIN)).isFalse();
            assertThat(Role.ANALYST.hasPermissionLevel(Role.OPERATIONS)).isFalse();
            assertThat(Role.ANALYST.hasPermissionLevel(Role.ANALYST)).isTrue();
            assertThat(Role.ANALYST.hasPermissionLevel(Role.USER)).isTrue();
        }

        @Test
        void userHasUserOnly() {
            assertThat(Role.USER.hasPermissionLevel(Role.ADMIN)).isFalse();
            assertThat(Role.USER.hasPermissionLevel(Role.OPERATIONS)).isFalse();
            assertThat(Role.USER.hasPermissionLevel(Role.ANALYST)).isFalse();
            assertThat(Role.USER.hasPermissionLevel(Role.USER)).isTrue();
        }
    }
}
