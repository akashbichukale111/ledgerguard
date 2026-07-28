package dev.ledgerguard.auth.adapter.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("LoginController")
@WebMvcTest(controllers = LoginController.class)
class LoginControllerTest {

    private static final String ENDPOINT = "/api/v1/auth/login";

    @Autowired
    private MockMvc mockMvc;

    private static String body(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }

    @Test
    void validCredentialsReturnTheRoleAndAReplayableBasicCredential() throws Exception {
        String expected = Base64.getEncoder().encodeToString("operations:operations".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("operations", "operations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme").value("Basic"))
                .andExpect(jsonPath("$.token").value(expected))
                .andExpect(jsonPath("$.user.name").value("operations"))
                .andExpect(jsonPath("$.user.roles[0]").value("OPERATIONS"));
    }

    @Test
    void theReturnedOperationsMatchTheRolesRights() throws Exception {
        // OPERATIONS may replay from the DLT; it may not manage roles. The console uses this to
        // hide controls, so getting it wrong shows an operator a button that will 403.
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("operations", "operations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.permittedOperations", org.hamcrest.Matchers.hasItem("DLT_REPLAY")))
                .andExpect(jsonPath(
                        "$.user.permittedOperations",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ROLE_MANAGE"))));
    }

    @Test
    void adminGetsRoleManagement() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body("admin", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.permittedOperations", org.hamcrest.Matchers.hasItem("ROLE_MANAGE")));
    }

    @Test
    void aWrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("admin", "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownUserIsRejectedWithTheSameMessageAsAWrongPassword() throws Exception {
        // Identical responses: a different message here would enumerate valid usernames.
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body("nobody", "nobody")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void aMissingFieldIsABadRequest() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest());
    }
}
