package dev.ledgerguard.query.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.ledgerguard.query.adapter.out.persistence.AuditEventEntity;
import dev.ledgerguard.query.adapter.out.persistence.AuditEventRepository;
import dev.ledgerguard.query.application.AuditChainService;
import dev.ledgerguard.query.domain.audit.AuditHasher;
import dev.ledgerguard.query.domain.audit.AuditRecord;
import dev.ledgerguard.query.domain.audit.ChainVerificationResult;

/**
 * The Phase 6 audit-chain gate: verification must detect a deliberately tampered record, at the
 * correct index.
 *
 * <p>Tampering is performed with raw SQL as the <b>superuser</b>, deliberately bypassing the
 * application role's {@code REVOKE UPDATE, DELETE}. That is the realistic threat: an attacker with
 * direct database access, not one going through the application. The grant and the hash chain are
 * two independent layers, and this test exercises the second by defeating the first.
 */
@SpringBootTest
@Testcontainers
class AuditChainIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledgerguard_audit")
            .withInitScript("db/testcontainers-init.sql");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        // No broker in this test: the audit chain is independent of Kafka.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    AuditChainService audit;

    @Autowired
    AuditEventRepository repository;

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Connect as postgres superuser for cleanup (the autowired datasource is lg_app and
        // cannot DELETE due to REVOKE).
        org.springframework.jdbc.datasource.DriverManagerDataSource adminDs =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        adminDs.setUrl(POSTGRES.getJdbcUrl());
        adminDs.setUsername(POSTGRES.getUsername());
        adminDs.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(adminDs);

        jdbc.execute("DELETE FROM audit_event");
        jdbc.execute("ALTER SEQUENCE audit_event_chain_index_seq RESTART WITH 1");
    }

    private AuditEventEntity appendEntry(String action) {
        return audit.append(
                UUID.randomUUID(),
                Instant.parse("2026-07-28T00:00:00Z"),
                UUID.randomUUID(),
                null,
                "analyst@ledgerguard.dev",
                "ANALYST",
                "API",
                "query-service",
                action,
                "Transaction",
                UUID.randomUUID(),
                "SUCCESS",
                null,
                null);
    }

    @Nested
    @DisplayName("an untampered chain verifies")
    class IntactChain {

        @Test
        void anEmptyChainIsIntact() {
            ChainVerificationResult result = audit.verify();
            assertThat(result.intact()).isTrue();
            assertThat(result.recordsVerified()).isZero();
        }

        @Test
        void aChainOfManyRecordsVerifies() {
            for (int i = 0; i < 25; i++) {
                appendEntry("ACTION_" + i);
            }
            ChainVerificationResult result = audit.verify();

            assertThat(result.intact()).isTrue();
            assertThat(result.recordsVerified()).isEqualTo(25);
            assertThat(result.brokenAt()).isEmpty();
        }

        @Test
        void eachRecordLinksToItsPredecessor() {
            appendEntry("FIRST");
            appendEntry("SECOND");
            appendEntry("THIRD");

            List<AuditEventEntity> chain = repository.findAll(org.springframework.data.domain.Sort.by("chainIndex"));

            assertThat(chain.get(0).previousHash())
                    .as("genesis has no predecessor")
                    .isNull();
            assertThat(chain.get(1).previousHash()).isEqualTo(chain.get(0).recordHash());
            assertThat(chain.get(2).previousHash()).isEqualTo(chain.get(1).recordHash());
        }
    }

    @Nested
    @DisplayName("tampering is detected at the correct index")
    class TamperDetection {

        @Test
        void modifyingARecordIsDetectedAtThatRecordsIndex() {
            for (int i = 0; i < 10; i++) {
                appendEntry("ACTION_" + i);
            }
            assertThat(audit.verify().intact()).isTrue();

            // Tamper with record 4 as the superuser, defeating the REVOKE.
            jdbc.update("UPDATE audit_event SET action = ? WHERE chain_index = ?", "TAMPERED", 4L);

            ChainVerificationResult result = audit.verify();

            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt()).contains(4L);
            assertThat(result.detail()).contains("record was modified");
            // Records 1-3 verified before the break, so the report bounds what is still trustworthy.
            assertThat(result.recordsVerified()).isEqualTo(3);
        }

        @Test
        void tamperingWithTheGenesisRecordIsDetectedAtIndexOne() {
            appendEntry("FIRST");
            appendEntry("SECOND");

            jdbc.update("UPDATE audit_event SET actor_subject = ? WHERE chain_index = 1", "mallory@evil.example");

            ChainVerificationResult result = audit.verify();
            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt()).contains(1L);
            assertThat(result.recordsVerified()).isZero();
        }

        @Test
        void deletingARecordBreaksTheLinkAtTheFollowingIndex() {
            for (int i = 0; i < 6; i++) {
                appendEntry("ACTION_" + i);
            }
            // Deletion is the attack a content-only check would miss entirely: every remaining
            // record still hashes correctly to its own content.
            jdbc.update("DELETE FROM audit_event WHERE chain_index = 3");

            ChainVerificationResult result = audit.verify();

            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt())
                    .as("the break surfaces at the record whose predecessor vanished")
                    .contains(4L);
            assertThat(result.detail()).contains("deleted, inserted, or reordered");
        }

        @Test
        void changingATimestampIsDetected() {
            appendEntry("FIRST");
            appendEntry("SECOND");

            // Back-dating an audit entry is a classic evasion. occurredAt is part of the canonical
            // form, so it cannot be changed without breaking the hash.
            jdbc.update(
                    "UPDATE audit_event SET occurred_at = ? WHERE chain_index = 2",
                    java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));

            ChainVerificationResult result = audit.verify();
            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt()).contains(2L);
        }

        @Test
        void aRehashedRecordStillBreaksTheLink() {
            // The sophisticated attack: edit a record AND recompute its own hash so the content
            // check passes. The link check catches it, because the NEXT record still stores the old
            // previousHash. Defeating both requires rewriting the whole tail — which is exactly the
            // cost the chain is designed to impose, and exactly why it is tamper-EVIDENT rather than
            // tamper-proof (ADR-0011).
            appendEntry("FIRST");
            appendEntry("SECOND");
            appendEntry("THIRD");

            AuditEventEntity target = repository.findById(2L).orElseThrow();
            AuditRecord tampered = new AuditRecord(
                    2L,
                    target.toRecord().eventId(),
                    target.toRecord().occurredAt(),
                    target.toRecord().correlationId(),
                    target.toRecord().causationId(),
                    target.toRecord().actorSubject(),
                    target.toRecord().actorRole(),
                    target.toRecord().actorSource(),
                    target.toRecord().service(),
                    "TAMPERED_BUT_REHASHED",
                    target.toRecord().aggregateType(),
                    target.toRecord().aggregateId(),
                    target.toRecord().outcome(),
                    null,
                    null,
                    target.previousHash());

            jdbc.update(
                    "UPDATE audit_event SET action = ?, record_hash = ? WHERE chain_index = 2",
                    "TAMPERED_BUT_REHASHED",
                    AuditHasher.hash(tampered));

            ChainVerificationResult result = audit.verify();

            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt())
                    .as("record 3 still expects record 2's original hash")
                    .contains(3L);
            assertThat(result.detail()).contains("deleted, inserted, or reordered");
        }
    }

    @Nested
    @DisplayName("append-only enforcement")
    class AppendOnly {

        @Test
        void theApplicationRoleCannotUpdateOrDelete() {
            appendEntry("FIRST");

            // lg_app is the role every service actually connects as. The REVOKE in
            // V1__audit_chain.sql is only meaningful if it holds for that role.
            JdbcTemplate asAppRole = appRoleTemplate();

            assertThatThrownBy(() -> asAppRole.update("UPDATE audit_event SET action = 'X'"))
                    .satisfies(ex -> {
                        String fullMessage = ex.toString() + " "
                                + (ex.getCause() != null ? ex.getCause().toString() : "");
                        assertThat(fullMessage).contains("permission denied");
                    });
            assertThatThrownBy(() -> asAppRole.update("DELETE FROM audit_event"))
                    .satisfies(ex -> {
                        String fullMessage = ex.toString() + " "
                                + (ex.getCause() != null ? ex.getCause().toString() : "");
                        assertThat(fullMessage).contains("permission denied");
                    });
        }

        @Test
        void theApplicationRoleCanStillAppendAndRead() {
            JdbcTemplate asAppRole = appRoleTemplate();
            appendEntry("FIRST");
            assertThat(asAppRole.queryForObject("SELECT count(*) FROM audit_event", Long.class))
                    .isEqualTo(1L);
        }

        @Test
        void aRecordCannotBeSealedTwice() {
            AuditEventEntity entity = appendEntry("FIRST");
            assertThatThrownBy(() -> entity.seal("0".repeat(64)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already sealed");
        }

        private JdbcTemplate appRoleTemplate() {
            org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource();
            ds.setUrl(POSTGRES.getJdbcUrl());
            ds.setUsername("lg_app");
            ds.setPassword("test_only_not_a_secret");
            return new JdbcTemplate(ds);
        }
    }
}
