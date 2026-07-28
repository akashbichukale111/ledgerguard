package dev.ledgerguard.query.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import dev.ledgerguard.common.security.Role;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;
import dev.ledgerguard.query.config.SecurityConfig;

/**
 * The transaction read endpoints the console's search and detail views call.
 *
 * <p>These had no controller at all until this phase — every one of these requests returned 404.
 */
@DisplayName("TransactionQueryController")
@WebMvcTest(controllers = TransactionQueryController.class)
@Import(SecurityConfig.class)
class TransactionQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private Transaction360Repository transactions;

    private static RequestPostProcessor as(Role role) {
        return user(role.name().toLowerCase()).authorities(role::getSpringRole);
    }

    private static Transaction360Document document(String id, String reference, String counterparty) {
        var doc = new Transaction360Document(id);
        doc.setReference(reference);
        doc.setCounterpartyId(counterparty);
        doc.setAmount("1234.56");
        doc.setCurrency("USD");
        doc.setDirection("DEBIT");
        doc.setStatus("RECEIVED");
        doc.setOccurredAt(Instant.parse("2026-01-15T10:30:00Z"));
        doc.setUpdatedAt(Instant.parse("2026-01-15T10:30:01Z"));
        doc.setCorrelationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        doc.addNode(new Transaction360Document.LifecycleNode(
                "INGESTED",
                "transaction-service",
                Instant.parse("2026-01-15T10:30:00Z"),
                Instant.parse("2026-01-15T10:30:01Z"),
                "SUCCESS",
                "transaction accepted"));
        return doc;
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        void aBlankQueryReturnsTheMostRecent() throws Exception {
            when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenReturn(List.of(document("tx-1", "REF-1", "cp-1"), document("tx-2", "REF-2", "cp-2")));

            mockMvc.perform(get("/api/v1/transactions/search").with(as(Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        void narrowsByReference() throws Exception {
            when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenReturn(List.of(document("tx-1", "REF-ALPHA", "cp-1"), document("tx-2", "REF-BETA", "cp-2")));

            mockMvc.perform(get("/api/v1/transactions/search")
                            .param("q", "alpha")
                            .with(as(Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].transactionId").value("tx-1"));
        }

        @Test
        void matchIsCaseInsensitive() throws Exception {
            when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenReturn(List.of(document("tx-1", "REF-ALPHA", "cp-1")));

            mockMvc.perform(get("/api/v1/transactions/search")
                            .param("q", "AlPhA")
                            .with(as(Role.USER)))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        void searchResultsOmitTheTimelineToKeepThePayloadSmall() throws Exception {
            when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenReturn(List.of(document("tx-1", "REF-1", "cp-1")));

            mockMvc.perform(get("/api/v1/transactions/search").with(as(Role.USER)))
                    .andExpect(jsonPath("$[0].timeline.length()").value(0));
        }

        @Test
        void theAmountStaysAStringSoPrecisionSurvivesTheJsonBoundary() throws Exception {
            when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenReturn(List.of(document("tx-1", "REF-1", "cp-1")));

            mockMvc.perform(get("/api/v1/transactions/search").with(as(Role.USER)))
                    .andExpect(jsonPath("$[0].amount").value("1234.56"))
                    .andExpect(jsonPath("$[0].amount").isString());
        }
    }

    @Nested
    @DisplayName("detail")
    class Detail {

        @Test
        void returnsTheFullDocumentIncludingTheTimeline() throws Exception {
            when(transactions.findById("tx-1")).thenReturn(Optional.of(document("tx-1", "REF-1", "cp-1")));

            mockMvc.perform(get("/api/v1/transactions/tx-1").with(as(Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value("tx-1"))
                    .andExpect(jsonPath("$.timeline.length()").value(1))
                    .andExpect(jsonPath("$.timeline[0].stage").value("INGESTED"));
        }

        @Test
        void anUnknownTransactionIs404NotAnEmptyBody() throws Exception {
            when(transactions.findById("nope")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/transactions/nope").with(as(Role.USER)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void lifecycleReturnsJustTheStages() throws Exception {
            when(transactions.findById("tx-1")).thenReturn(Optional.of(document("tx-1", "REF-1", "cp-1")));

            mockMvc.perform(get("/api/v1/transactions/tx-1/lifecycle").with(as(Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].service").value("transaction-service"));
        }
    }

    @Nested
    @DisplayName("correlation lookup")
    class CorrelationLookup {

        @Test
        void aMalformedCorrelationIdIsABadRequestNotAServerError() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/by-correlation/not-a-uuid")
                            .with(as(Role.ANALYST)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void requiresAnalyst() throws Exception {
            // Correlating across transactions is a wider view than a user's own records.
            mockMvc.perform(get("/api/v1/transactions/by-correlation/11111111-1111-1111-1111-111111111111")
                            .with(as(Role.USER)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        void anonymousIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/search")).andExpect(status().isUnauthorized());
        }

        @Test
        void everyAuthenticatedRoleCanRead() throws Exception {
            when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenReturn(List.of());

            for (Role role : Role.values()) {
                mockMvc.perform(get("/api/v1/transactions/search").with(as(role)))
                        .andExpect(status().isOk());
            }
        }
    }
}
