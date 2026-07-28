#!/usr/bin/env bash
# Create Kafka topics for Phase 7 retry ladder and DLT.
#
# Topics are created with RF=1 (local dev) and 3 partitions.
# Production topology: RF>=3, min.insync.replicas=2, per docs/operations.md.

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-3}"

# Create source topics
create_topic() {
    local topic="$1"
    local partitions="${2:-$PARTITIONS}"

    /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server "$BOOTSTRAP" \
        --create \
        --if-not-exists \
        --topic "$topic" \
        --partitions "$partitions" \
        --replication-factor 1 \
        --config "retention.ms=-1" \
        --config "compression.type=snappy"
}

echo "Creating Kafka topics for transactional events..."

# Transactional events (from transaction-service outbox)
create_topic "transactions.events.v1"
create_topic "transactions.events.v1.retry.1"
create_topic "transactions.events.v1.retry.2"
create_topic "transactions.events.v1.retry.3"
create_topic "transactions.events.v1.dlt"

# Reconciliation events (from reconciliation-service saga)
create_topic "reconciliation.events.v1"
create_topic "reconciliation.events.v1.retry.1"
create_topic "reconciliation.events.v1.retry.2"
create_topic "reconciliation.events.v1.retry.3"
create_topic "reconciliation.events.v1.dlt"

# Query events (for projection consumer)
create_topic "query.events.v1"
create_topic "query.events.v1.retry.1"
create_topic "query.events.v1.retry.2"
create_topic "query.events.v1.retry.3"
create_topic "query.events.v1.dlt"

echo "Topics created successfully."
