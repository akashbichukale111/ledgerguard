package dev.ledgerguard.query.adapter.in.messaging;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.ledgerguard.common.kafka.RetryEnvelope;
import dev.ledgerguard.common.kafka.RetryEnvelopeCodec;
import dev.ledgerguard.common.observability.KafkaTracingConsumer;
import dev.ledgerguard.common.observability.MdcContext;
import dev.ledgerguard.query.adapter.out.persistence.DltMessageEntity;
import dev.ledgerguard.query.adapter.out.persistence.DltMessageRepository;

/**
 * Captures dead-lettered messages into the database so the console can show them.
 *
 * <p>Without this the DLT existed only as a Kafka topic: messages aged out with the retention
 * policy and no operator surface could list them. The console's DLT explorer had nothing behind it.
 *
 * <p>Identity is the record's coordinate on the DLT topic. A rebalance can redeliver the same
 * record, and inserting it twice would inflate the depth an operator sees, so an existing row for
 * the same coordinate wins and the redelivery is dropped.
 */
@Service
public class DltConsumer {
    private static final Logger log = LoggerFactory.getLogger(DltConsumer.class);

    private final DltMessageRepository dltMessages;
    private final Clock clock;

    public DltConsumer(DltMessageRepository dltMessages, Clock clock) {
        this.dltMessages = Objects.requireNonNull(dltMessages, "dltMessages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @KafkaListener(
            topics = "${ledgerguard.dlt.topic:transactions.events.v1.dlt}",
            groupId = "${spring.application.name}-dlt-capture")
    @Transactional
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            KafkaTracingConsumer.populateMdcFromHeaders(record);
            MdcContext.put(MdcContext.EVENT_TYPE, "dlt");

            if (dltMessages
                    .findBySourceTopicAndPartitionNumberAndRecordOffset(
                            record.topic(), record.partition(), record.offset())
                    .isPresent()) {
                log.debug(
                        "dead letter at {}-{}@{} already captured",
                        record.topic(),
                        record.partition(),
                        record.offset());
                return;
            }

            dltMessages.save(toEntity(record));
            log.info("captured dead letter from {} (offset={})", record.topic(), record.offset());

        } catch (Exception e) {
            // Capture is best-effort observability, not the system of record — the message is still
            // on the topic. Failing here must not stall the partition.
            log.error("failed to capture dead letter from {} (offset={})", record.topic(), record.offset(), e);
        } finally {
            KafkaTracingConsumer.clearMdc();
            acknowledgment.acknowledge();
        }
    }

    /**
     * Builds the row. A dead letter that is not a well-formed retry envelope is still captured —
     * an operator needs to see a corrupt message more than a well-formed one, so the raw value is
     * preserved and the parse failure recorded as the reason.
     */
    private DltMessageEntity toEntity(ConsumerRecord<String, String> record) {
        var now = clock.instant();
        try {
            RetryEnvelope envelope = RetryEnvelopeCodec.fromJson(record.value());
            return new DltMessageEntity(
                    UUID.randomUUID(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    envelope.reason(),
                    envelope.stackTraceDigest(),
                    envelope.attemptCount(),
                    envelope.originalEnvelope(),
                    envelope.firstFailedAt(),
                    envelope.lastFailedAt(),
                    now);
        } catch (Exception parseFailure) {
            return new DltMessageEntity(
                    UUID.randomUUID(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    "Unreadable dead letter: " + parseFailure.getMessage(),
                    null,
                    0,
                    record.value() == null ? "" : record.value(),
                    null,
                    now,
                    now);
        }
    }
}
