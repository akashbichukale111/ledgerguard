#!/bin/sh
# ---------------------------------------------------------------------------
# Entrypoint for the hosted demo (Render + Aiven).
#
# Does two things the plain `java -jar` entrypoint cannot:
#
#   1. Materialises a Kafka CA certificate from an environment variable.
#      Aiven signs its brokers with a PER-PROJECT CA, not a public root, so a
#      JVM using only the system trust store fails the TLS handshake with
#      "unable to find valid certification path". The cert cannot be committed
#      (it is per-project), so it arrives as an env var and is turned into a
#      truststore here, at boot, on a tmpfs path.
#
#      Providers that use a PUBLIC CA -- Confluent Cloud, Upstash, Redpanda
#      Cloud -- need none of this. Leave KAFKA_CA_PEM unset and the system
#      trust store is used, which is why this script tests for it rather than
#      requiring it.
#
#   2. Applies heap settings sized for a 512 MB container. See JAVA_OPTS below.
#
# Secrets only ever exist in the process environment and on a tmpfs file that
# dies with the container. Nothing is written to the image or to a volume.
# ---------------------------------------------------------------------------
set -eu

CERT_DIR="${CERT_DIR:-/tmp/certs}"
TRUSTSTORE="$CERT_DIR/kafka-truststore.p12"

if [ -n "${KAFKA_CA_PEM:-}" ]; then
    mkdir -p "$CERT_DIR"

    # The env var holds a PEM. Render's dashboard preserves newlines, but values
    # pasted through other tooling often arrive with literal "\n", which openssl
    # rejects, so both spellings are accepted.
    printf '%s' "$KAFKA_CA_PEM" | sed 's/\\n/\
/g' > "$CERT_DIR/kafka-ca.pem"

    # A generated password protects nothing here -- the file is on tmpfs in a
    # single-tenant container -- but PKCS12 requires one, and a fixed literal in
    # a repo invites being copied somewhere it does matter.
    STORE_PASS="$(head -c 18 /dev/urandom | base64 | tr -d '\n=/+')"

    keytool -importcert \
        -alias aiven-kafka-ca \
        -file "$CERT_DIR/kafka-ca.pem" \
        -keystore "$TRUSTSTORE" \
        -storetype PKCS12 \
        -storepass "$STORE_PASS" \
        -noprompt >/dev/null

    # Readable only by the runtime user.
    chmod 400 "$TRUSTSTORE"
    rm -f "$CERT_DIR/kafka-ca.pem"

    KAFKA_TLS_OPTS="-Dledgerguard.kafka.truststore-location=$TRUSTSTORE -Dledgerguard.kafka.truststore-password=$STORE_PASS"
    echo "cloud-entrypoint: built Kafka truststore from KAFKA_CA_PEM"
else
    KAFKA_TLS_OPTS=""
    echo "cloud-entrypoint: KAFKA_CA_PEM unset -- using the system trust store"
fi

# --- Heap, sized for Render's 512 MB free instance -------------------------
#
# MaxRAMPercentage=55 leaves ~230 MB outside the heap. That is not slack: the
# JVM needs it for metaspace, code cache, GC structures, thread stacks and
# direct buffers, and the Kafka client allocates direct buffers per connection.
# At 75% (the local docker default) a Spring Boot service with a Kafka consumer
# and a JDBC pool is killed by the OOM killer, which Render reports only as an
# unexplained restart.
#
# SerialGC, not G1: a free instance has a fraction of a CPU, where G1's
# concurrent threads cost more than they return. Serial also has a smaller
# native footprint.
#
# TieredStopAtLevel=1 caps JIT at C1. It trades peak throughput -- irrelevant at
# demo volumes -- for materially faster startup, which matters because a free
# instance cold-starts on the first request after it idles out.
JAVA_OPTS="${JAVA_OPTS:--XX:+UseSerialGC -XX:MaxRAMPercentage=55.0 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1 -Xss512k -Dspring.jmx.enabled=false}"

# Render assigns the listening port; nothing else may bind it.
PORT_OPT="-Dserver.port=${PORT:-8080}"

# No -Dspring.profiles.active here on purpose. A -D flag outranks the
# SPRING_PROFILES_ACTIVE environment variable, so hardcoding it (as the local
# Dockerfiles do, to "docker") would make the cloud profile unselectable.
exec java $JAVA_OPTS $KAFKA_TLS_OPTS $PORT_OPT -jar /app/app.jar "$@"
