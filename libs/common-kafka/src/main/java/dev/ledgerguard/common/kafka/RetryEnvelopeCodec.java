package dev.ledgerguard.common.kafka;

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Serialises and parses {@link RetryEnvelope} as JSON.
 *
 * <p>This replaces a pair of hand-rolled routines that did not round-trip. The writer escaped only
 * the double-quote character, so any payload containing a backslash produced invalid JSON; the
 * reader then scanned for the first unescaped-looking quote after {@code "originalEnvelope":"},
 * which lands inside the payload as soon as the payload itself contains a quote — and every
 * realistic event envelope does, because it is itself JSON. The net effect was a silently truncated
 * envelope on the retry path. {@link RetryEnvelopeCodecTest} pins the round-trip.
 */
public final class RetryEnvelopeCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // Jackson defaults to writing Instant as an epoch decimal ("1.768473E9"). The wire form
            // has to stay ISO-8601 so the field is readable in a DLT dump and parseable by
            // Instant.parse on the way back in.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    private RetryEnvelopeCodec() {}

    /** Serialise an envelope to its JSON wire form. */
    public static String toJson(RetryEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (IOException e) {
            // RetryEnvelope is a closed shape of strings, an int and two Instants; a failure here
            // means the type changed in a way that is not serialisable, which is a bug not a
            // runtime condition.
            throw new IllegalStateException("RetryEnvelope is not serialisable", e);
        }
    }

    /** Parse the JSON wire form back into an envelope. */
    public static RetryEnvelope fromJson(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        return new RetryEnvelope(
                requiredText(node, "originalEnvelope"),
                node.path("attemptCount").asInt(),
                Instant.parse(requiredText(node, "firstFailedAt")),
                Instant.parse(requiredText(node, "lastFailedAt")),
                requiredText(node, "reason"),
                requiredText(node, "stackTraceDigest"));
    }

    /**
     * Extract just the original event envelope from a retry message.
     *
     * <p>The retry consumer only needs this field, but it must come from a real parse — reading it
     * out with string arithmetic is what broke before.
     */
    public static String originalEnvelopeOf(String retryJson) throws IOException {
        return requiredText(MAPPER.readTree(retryJson), "originalEnvelope");
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IOException("Retry envelope is missing required field '" + field + "'");
        }
        return value.asText();
    }
}
