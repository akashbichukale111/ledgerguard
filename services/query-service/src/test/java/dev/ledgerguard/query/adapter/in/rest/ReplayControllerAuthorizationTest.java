package dev.ledgerguard.query.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.ledgerguard.common.security.Role;
import dev.ledgerguard.query.adapter.out.messaging.RetryPublishingService;
import dev.ledgerguard.query.config.SecurityConfig;

/**
 * Authorization on the DLT replay endpoint.
 *
 * <p>These assertions are the acceptance gate the security phase never actually had. Before
 * {@link SecurityConfig} existed, {@code @PreAuthorize} was inert — no method-security interceptor
 * was ever registered — and the in-method matrix check was handed a hardcoded
 * {@code Role.OPERATIONS}, so it evaluated to a constant that could not fail. Every rejection case
 * below would have reached the publisher.
 *
 * <p>Scope is the web layer only: importing {@link SecurityConfig} brings the real filter chain and
 * the real method-security interceptor, while leaving JPA, Flyway and Kafka out of the context.
 */
@DisplayName("ReplayController authorization")
@WebMvcTest(controllers = ReplayController.class)
@Import(SecurityConfig.class)
class ReplayControllerAuthorizationTest {

    private static final String ENDPOINT = "/api/v1/replay/dlt-message";
    private static final String BODY = "{\"originalEnvelope\":\"{\\\"transactionId\\\":\\\"tx-1\\\"}\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RetryPublishingService retryPublisher;

    /** Authenticates as the single-role user for the given role. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(Role role) {
        return user(role.name().toLowerCase()).authorities(role::getSpringRole);
    }

    @Test
    void operationsCanReplay() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(as(Role.OPERATIONS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verify(retryPublisher).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void adminCanReplayBecauseTheHierarchyIncludesOperations() throws Exception {
        // ROLE_ADMIN only — no ROLE_OPERATIONS authority. This passes solely because
        // SecurityConfig declares the role hierarchy; an earlier version of this test granted both
        // authorities, which hid the fact that the hierarchy was not wired and an administrator
        // was being denied every endpoint gated below their own rung.
        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").authorities(Role.ADMIN::getSpringRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void analystIsRejected() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(as(Role.ANALYST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        verify(retryPublisher, never()).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void plainUserIsRejected() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(as(Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        verify(retryPublisher, never()).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verify(retryPublisher, never()).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void aBlankEnvelopeIsRejectedAsBadRequestNotServerError() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(as(Role.OPERATIONS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalEnvelope\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(retryPublisher, never()).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
    }
}
