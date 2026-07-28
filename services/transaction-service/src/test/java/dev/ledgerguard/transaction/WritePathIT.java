package dev.ledgerguard.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import dev.ledgerguard.transaction.adapter.out.messaging.OutboxPoller;
import dev.ledgerguard.transaction.adapter.out.persistence.LedgerEntryEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.LedgerEntryRepository;
import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRepository;
import dev.ledgerguard.transaction.adapter.out.persistence.TransactionEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.TransactionRepository;
import dev.ledgerguard.transaction.domain.Direction;
import dev.ledgerguard.transaction.domain.TransactionStatus;

/**
 * The Phase 4 acceptance gate, against real PostgreSQL and real Kafka.
 *
 * <p>These run against containers rather than an in-memory substitute deliberately. H2 does not
 * enforce the same constraints, does not implement {@code FOR UPDATE SKIP LOCKED} the same way, and
 * would let all four of these tests pass while the production code was broken.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WritePathIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledgerguard_txn")
            // The migrations GRANT to lg_app, which the infra bootstrap creates in the real stack.
            // Creating it here keeps the migrations byte-identical between test and production
            // rather than maintaining a test-only variant that could drift.
            .withInitScript("db/testcontainers-init.sql");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // The scheduler is disabled so tests drive publishBatch() explicitly. Waiting on a timer
        // makes tests slow and flaky, and proves less than calling the method under test.
        registry.add("ledgerguard.outbox.scheduler.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    LedgerEntryRepository ledgerEntries;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    OutboxPoller poller;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        ledgerEntries.deleteAll();
        transactions.deleteAll();
        outbox.deleteAll();
    }

    // ---------------------------------------------------------------- gate 1

    @Test
    @DisplayName("aggregate, ledger entries and outbox row are written in ONE transaction")
    void aggregateAndOutboxAreWrittenAtomically() {
        ResponseEntity<String> response = submit(newRequest(), "key-atomic-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // All four effects are present...
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(ledgerEntries.count()).isEqualTo(2);
        assertThat(outbox.count()).isEqualTo(1);

        TransactionEntity saved = transactions.findAll().get(0);
        assertThat(saved.status()).isEqualTo(TransactionStatus.RECEIVED);
        assertThat(saved.money().amount()).isEqualByComparingTo(new BigDecimal("1250.75"));
        assertThat(saved.money().currency().code()).isEqualTo("USD");

        // ...and the outbox row points at the aggregate that was actually written.
        assertThat(outbox.findByAggregateIdOrderByIdAsc(saved.id())).hasSize(1);
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);
    }

    @Test
    @DisplayName("ledger entries balance: one debit and one credit of equal amount")
    void ledgerEntriesBalance() {
        submit(newRequest(), "key-balance-1");

        List<LedgerEntryEntity> entries =
                ledgerEntries.findByTransactionId(transactions.findAll().get(0).id());

        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(LedgerEntryEntity::entryType)
                .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT);

        // Debits equal credits, per currency. The invariant that matters most in a ledger.
        var debit = entries.stream()
                .filter(e -> e.entryType() == Direction.DEBIT)
                .findFirst()
                .orElseThrow();
        var credit = entries.stream()
                .filter(e -> e.entryType() == Direction.CREDIT)
                .findFirst()
                .orElseThrow();
        assertThat(debit.money()).isEqualTo(credit.money());
    }

    @Test
    @DisplayName("a failed command writes NOTHING — no partial aggregate, no orphan outbox row")
    void failureLeavesNoPartialState() {
        // 1250.755 has three decimals; USD permits two. Money rejects it rather than truncating,
        // so the command fails after validation but before any write completes.
        Map<String, Object> bad = newRequest();
        bad.put("amount", "1250.755");

        ResponseEntity<String> response = submit(bad, "key-rollback-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transactions.count()).isZero();
        assertThat(ledgerEntries.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    // ---------------------------------------------------------------- gate 2

    @Test
    @DisplayName("duplicate request with the same key: one aggregate, one outbox row, identical response")
    void duplicateRequestIsIdempotent() {
        Map<String, Object> request = newRequest();

        ResponseEntity<String> first = submit(request, "key-dup-1");
        ResponseEntity<String> second = submit(request, "key-dup-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");

        // Byte-identical response body — the caller cannot tell it retried, except by the header.
        assertThat(second.getBody()).isEqualTo(first.getBody());

        // Exactly one of everything. This is the assertion that matters.
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(ledgerEntries.count()).isEqualTo(2);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("same key with a DIFFERENT body is a 409, not a silent replay")
    void sameKeyDifferentBodyConflicts() {
        submit(newRequest(), "key-conflict-1");

        Map<String, Object> different = newRequest();
        different.put("amount", "9999.00");
        ResponseEntity<String> response = submit(different, "key-conflict-1");

        // Returning the first response here would tell the caller a DIFFERENT request succeeded.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("field order in the request body does not defeat idempotency")
    void canonicalisationIgnoresFieldOrder() {
        // A client that reserialises its retry with different key order must still be recognised
        // as the same request. Hashing raw bytes would reject it as a conflicting reuse.
        submit(newRequest(), "key-canonical-1");

        Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        Map<String, Object> original = newRequest();
        original.keySet().stream()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(k -> reordered.put(k, original.get(k)));

        ResponseEntity<String> response = submit(reordered, "key-canonical-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent duplicate requests create exactly one aggregate")
    void concurrentDuplicatesCreateOneAggregate() throws Exception {
        // The unique constraint, not a prior read, is what makes this safe. A check-then-act in
        // application code has a window between the two that this test would find.
        int threads = 8;
        Map<String, Object> request = newRequest();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (submit(request, "key-race-1").getStatusCode() == HttpStatus.ACCEPTED) {
                            accepted.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // A losing thread may see 409 IN_FLIGHT; that is correct behaviour.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(accepted.get()).as("exactly one request should be accepted").isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- gate 3

    @Test
    @DisplayName("a real event reaches a real Kafka topic and a real consumer reads it")
    void eventIsPublishedToKafkaAndConsumed() throws Exception {
        submit(newRequest(), "key-kafka-1");
        UUID transactionId = transactions.findAll().get(0).id();

        int publishedCount = poller.publishBatch();
        assertThat(publishedCount).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();

        try (KafkaConsumer<String, String> consumer = testConsumer()) {
            consumer.subscribe(List.of("transactions.events.v1"));

            // Match on the key rather than taking the first record: the topic is shared across
            // tests in this class and a new consumer group starting from `earliest` would otherwise
            // read an earlier test's event and assert against the wrong transaction.
            ConsumerRecord<String, String> record = pollForKey(consumer, transactionId.toString());
            assertThat(record)
                    .as("an event keyed by %s should have been published", transactionId)
                    .isNotNull();

            // The message key is the aggregate id — this is what gives per-aggregate ordering.
            assertThat(record.key()).isEqualTo(transactionId.toString());

            JsonNode envelope = objectMapper.readTree(record.value());
            assertThat(envelope.get("eventType").asText()).isEqualTo("TransactionReceived");
            assertThat(envelope.get("eventVersion").asInt()).isEqualTo(2);
            assertThat(envelope.get("aggregateId").asText()).isEqualTo(transactionId.toString());
            assertThat(envelope.get("eventId").asText()).isNotBlank();
            assertThat(envelope.get("correlationId").asText()).isNotBlank();

            // Amount travels as a STRING. A JSON number would be an IEEE-754 double by the time a
            // browser parsed it, and the precision would already be gone (ADR-0009).
            JsonNode payload = objectMapper.readTree(envelope.get("payload").asText());
            assertThat(payload.get("amount").isTextual()).isTrue();
            assertThat(payload.get("amount").asText()).isEqualTo("1250.75");

            // Headers carry correlation across the broker hop.
            assertThat(record.headers().lastHeader("correlationId")).isNotNull();
            assertThat(record.headers().lastHeader("eventType")).isNotNull();
        }
    }

    @Test
    @DisplayName("publishing twice does not republish an already-published record")
    void publishedRecordsAreNotResent() {
        submit(newRequest(), "key-once-1");

        assertThat(poller.publishBatch()).isEqualTo(1);
        assertThat(poller.publishBatch()).as("nothing left to claim").isZero();
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    // ---------------------------------------------------------------- gate 4

    @Test
    @DisplayName("optimistic locking prevents a lost update under concurrent writers")
    void optimisticLockingPreventsLostUpdates() throws Exception {
        submit(newRequest(), "key-lock-1");
        UUID id = transactions.findAll().get(0).id();

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        TransactionEntity entity = transactions.findById(id).orElseThrow();
                        entity.transitionTo(TransactionStatus.VALIDATING, java.time.Instant.now());
                        transactions.saveAndFlush(entity);
                        succeeded.incrementAndGet();
                    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                        conflicted.incrementAndGet();
                    } catch (Exception ignored) {
                        // An illegal transition from a thread that already lost is expected noise.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        // The version column must have advanced exactly as many times as writes succeeded — no
        // write was silently overwritten by another.
        TransactionEntity finalState = transactions.findById(id).orElseThrow();
        assertThat(succeeded.get()).as("at least one writer must succeed").isPositive();
        assertThat(finalState.version())
                .as("version advances once per successful write; a lost update would show fewer")
                .isEqualTo(succeeded.get());
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> submit(Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        return rest.exchange(
                "http://localhost:" + port + "/api/v1/transactions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static Map<String, Object> newRequest() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("reference", "INV-2026-0001");
        body.put("amount", "1250.75");
        body.put("currency", "USD");
        body.put("direction", "DEBIT");
        body.put("counterpartyId", "CP-ACME-001");
        body.put("debitAccount", "1000-CASH");
        body.put("creditAccount", "2000-PAYABLE");
        body.put("valueDate", "2026-07-28");
        return body;
    }

    private KafkaConsumer<String, String> testConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    }

    private static ConsumerRecord<String, String> pollForKey(KafkaConsumer<String, String> consumer, String key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }
}
