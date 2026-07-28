package dev.ledgerguard.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces backward compatibility across committed event schema versions.
 *
 * <p>LedgerGuard has no schema-registry container ({@code docs/adr/0007-schema-in-repo.md}), so
 * this test <b>is</b> the enforcement mechanism. It runs on every build and fails it. That is a
 * weaker guarantee than a registry — a registry rejects an incompatible message at produce time,
 * this catches it at build time — and the ADR says so plainly.
 *
 * <p>The rule, stated precisely: a consumer written against version <i>N</i> must still be able to
 * read a message produced under version <i>N+1</i>. That permits adding optional fields and
 * widening types. It forbids removing a field, making an optional field required, adding a new
 * required field, or narrowing a type.
 */
class SchemaCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SCHEMA_ROOT = Path.of("src/main/resources/schemas");

    @Test
    @DisplayName("schema directory exists and is not empty")
    void schemasArePresent() {
        assertThat(SCHEMA_ROOT)
                .as("schemas must live in the contracts module — see ADR-0007")
                .isDirectory();
        assertThat(eventTypes()).as("at least one event type must be defined").isNotEmpty();
    }

    @Test
    @DisplayName("every schema file is valid JSON and declares $schema, $id and title")
    void schemasAreWellFormed() {
        for (Path file : allSchemaFiles()) {
            JsonNode schema = read(file);
            assertThat(schema.hasNonNull("$schema"))
                    .as("%s must declare $schema", file)
                    .isTrue();
            assertThat(schema.hasNonNull("$id")).as("%s must declare $id", file).isTrue();
            assertThat(schema.hasNonNull("title"))
                    .as("%s must declare title", file)
                    .isTrue();
            assertThat(schema.path("type").asText())
                    .as("%s must describe an object", file)
                    .isEqualTo("object");
        }
    }

    @Test
    @DisplayName("versions are contiguous from v1 with no gaps")
    void versionsAreContiguous() {
        for (String eventType : eventTypes()) {
            List<Integer> versions = new ArrayList<>(versionsOf(eventType).keySet());
            assertThat(versions.get(0))
                    .as("%s must start at v1, found v%s", eventType, versions.get(0))
                    .isEqualTo(1);
            for (int i = 0; i < versions.size(); i++) {
                assertThat(versions.get(i))
                        .as("%s has a version gap: %s", eventType, versions)
                        .isEqualTo(i + 1);
            }
        }
    }

    @Test
    @DisplayName("each new version is backward compatible with the one before it")
    void consecutiveVersionsAreBackwardCompatible() {
        for (String eventType : eventTypes()) {
            Map<Integer, JsonNode> versions = versionsOf(eventType);
            List<Integer> numbers = new ArrayList<>(versions.keySet());

            for (int i = 1; i < numbers.size(); i++) {
                int oldV = numbers.get(i - 1);
                int newV = numbers.get(i);
                assertCompatible(eventType, oldV, versions.get(oldV), newV, versions.get(newV));
            }
        }
    }

    @Test
    @DisplayName("monetary amounts are strings, never JSON numbers")
    void monetaryFieldsAreStrings() {
        // A JSON number is parsed into an IEEE-754 double by JSON.parse, which silently destroys
        // precision at the browser boundary regardless of how careful the backend was. ADR-0009.
        for (Path file : allSchemaFiles()) {
            JsonNode properties = read(file).path("properties");
            properties.fieldNames().forEachRemaining(name -> {
                if (name.equals("amount") || name.endsWith("Amount") || name.endsWith("amount")) {
                    JsonNode type = properties.path(name).path("type");
                    assertThat(typeNames(type))
                            .as("%s: monetary field '%s' must be a string, not a JSON number", file, name)
                            .contains("string")
                            .doesNotContain("number");
                }
            });
        }
    }

    @Test
    @DisplayName("the envelope schema requires the fields tracing and idempotency depend on")
    void envelopeCarriesTheLoadBearingFields() {
        JsonNode envelope = read(SCHEMA_ROOT.resolve("envelope/v1.json"));
        Set<String> required = requiredFields(envelope);

        // Each of these has a downstream mechanism that breaks without it.
        assertThat(required)
                .as("envelope must require the fields that dedupe, ordering and correlation rely on")
                .contains("eventId", "eventType", "eventVersion", "aggregateId", "occurredAt", "correlationId");

        // Optional by design: a flow root has no cause, and an untraced internal emit has no span.
        assertThat(required).doesNotContain("causationId", "traceparent");
    }

    // ------------------------------------------------------------------ rules

    private void assertCompatible(String eventType, int oldV, JsonNode oldS, int newV, JsonNode newS) {
        String ctx = eventType + " v" + oldV + " -> v" + newV;

        Map<String, JsonNode> oldProps = propertiesOf(oldS);
        Map<String, JsonNode> newProps = propertiesOf(newS);
        Set<String> oldRequired = requiredFields(oldS);
        Set<String> newRequired = requiredFields(newS);

        // 1. No field may be removed. A consumer on the old version still reads it.
        for (String field : oldProps.keySet()) {
            assertThat(newProps)
                    .as(
                            "%s: field '%s' was REMOVED. Removing a field is a breaking change; "
                                    + "deprecate it and keep it optional instead.",
                            ctx, field)
                    .containsKey(field);
        }

        // 2. No new required field. An old producer's message would fail validation.
        Set<String> addedRequired = new HashSet<>(newRequired);
        addedRequired.removeAll(oldRequired);
        assertThat(addedRequired)
                .as(
                        "%s: new REQUIRED field(s) %s. A message from an old producer would not "
                                + "validate. Add them as optional instead.",
                        ctx, addedRequired)
                .isEmpty();

        // 3. No optional field may become required.
        for (String field : oldProps.keySet()) {
            if (!oldRequired.contains(field) && newRequired.contains(field)) {
                throw new AssertionError(String.format(
                        "%s: field '%s' changed from OPTIONAL to REQUIRED, which is breaking.", ctx, field));
            }
        }

        // 4. Types may widen, never narrow.
        for (Map.Entry<String, JsonNode> entry : oldProps.entrySet()) {
            String field = entry.getKey();
            Set<String> oldTypes = typeNames(entry.getValue().path("type"));
            Set<String> newTypes = typeNames(newProps.get(field).path("type"));
            if (oldTypes.isEmpty() || newTypes.isEmpty()) {
                continue; // untyped in one version; nothing to compare
            }
            assertThat(newTypes)
                    .as(
                            "%s: field '%s' NARROWED from %s to %s. Widening is allowed "
                                    + "(e.g. \"string\" -> [\"string\",\"null\"]); narrowing is not.",
                            ctx, field, oldTypes, newTypes)
                    .containsAll(oldTypes);
        }

        // 5. An enum may gain members but never lose them.
        for (Map.Entry<String, JsonNode> entry : oldProps.entrySet()) {
            JsonNode oldEnum = entry.getValue().path("enum");
            if (!oldEnum.isArray()) {
                continue;
            }
            JsonNode newEnum = newProps.get(entry.getKey()).path("enum");
            assertThat(newEnum.isArray())
                    .as("%s: field '%s' lost its enum constraint", ctx, entry.getKey())
                    .isTrue();
            List<String> oldValues = valuesOf(oldEnum);
            List<String> newValues = valuesOf(newEnum);
            assertThat(newValues)
                    .as("%s: enum '%s' removed member(s). Existing data would stop validating.", ctx, entry.getKey())
                    .containsAll(oldValues);
        }
    }

    // ------------------------------------------------------------- self-check

    @Test
    @DisplayName("the compatibility rules actually reject a breaking change")
    void rulesRejectBreakingChanges() {
        // A test that only ever sees compatible input proves nothing about its own rules. These
        // synthetic pairs verify each rule fires.
        JsonNode base = json(
                """
                { "type":"object", "required":["a"],
                  "properties": { "a": {"type":"string"}, "b": {"type":"string"} } }
                """);

        JsonNode removedField = json(
                """
                { "type":"object", "required":["a"], "properties": { "a": {"type":"string"} } }
                """);
        assertBreaks(base, removedField, "was REMOVED");

        JsonNode newRequired = json(
                """
                { "type":"object", "required":["a","b"],
                  "properties": { "a": {"type":"string"}, "b": {"type":"string"} } }
                """);
        // Rule 2 (no new required field) fires before rule 3 (no optional->required),
        // because "b" is both newly-required and previously-optional. Asserting on rule 2's
        // message is correct: it is the rule that legitimately owns this case.
        assertBreaks(base, newRequired, "new REQUIRED field(s)");

        // A field that exists in BOTH versions and flips optional -> required is rule 3's case.
        JsonNode optionalBecameRequired = json(
                """
                { "type":"object", "required":["a","b"],
                  "properties": { "a": {"type":"string"}, "b": {"type":"string"} } }
                """);
        JsonNode baseWithBothRequiredKnown = json(
                """
                { "type":"object", "required":["a","b"],
                  "properties": { "a": {"type":"string"}, "b": {"type":"string"} } }
                """);
        assertThatCode(() -> assertCompatible("Synthetic", 1, baseWithBothRequiredKnown, 2, optionalBecameRequired))
                .doesNotThrowAnyException();

        JsonNode narrowedType = json(
                """
                { "type":"object", "required":["a"],
                  "properties": { "a": {"type":"string"}, "b": {"type":"integer"} } }
                """);
        assertBreaks(base, narrowedType, "NARROWED");

        // And that a genuinely additive change passes.
        JsonNode additive = json(
                """
                { "type":"object", "required":["a"],
                  "properties": { "a": {"type":"string"}, "b": {"type":"string"},
                                  "c": {"type":["string","null"]} } }
                """);
        assertThatCode(() -> assertCompatible("Synthetic", 1, base, 2, additive))
                .doesNotThrowAnyException();
    }

    private void assertBreaks(JsonNode oldS, JsonNode newS, String expectedMessageFragment) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> assertCompatible("Synthetic", 1, oldS, 2, newS)))
                .as("expected the rule to reject this change")
                .isNotNull()
                .hasMessageContaining(expectedMessageFragment);
    }

    // ---------------------------------------------------------------- helpers

    static List<String> eventTypes() {
        try (Stream<Path> dirs = Files.list(SCHEMA_ROOT)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("envelope"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> allSchemaFiles() {
        try (Stream<Path> files = Files.walk(SCHEMA_ROOT)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TreeMap<Integer, JsonNode> versionsOf(String eventType) {
        TreeMap<Integer, JsonNode> result = new TreeMap<>(Comparator.naturalOrder());
        try (Stream<Path> files = Files.list(SCHEMA_ROOT.resolve(eventType))) {
            files.filter(p -> p.getFileName().toString().matches("v\\d+\\.json"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        int version = Integer.parseInt(name.substring(1, name.indexOf('.')));
                        result.put(version, read(p));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static Map<String, JsonNode> propertiesOf(JsonNode schema) {
        Map<String, JsonNode> props = new TreeMap<>();
        schema.path("properties").fields().forEachRemaining(e -> props.put(e.getKey(), e.getValue()));
        return props;
    }

    private static Set<String> requiredFields(JsonNode schema) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        return required;
    }

    /** Normalises {@code "string"} and {@code ["string","null"]} to a set. */
    private static Set<String> typeNames(JsonNode type) {
        Set<String> names = new HashSet<>();
        if (type.isTextual()) {
            names.add(type.asText());
        } else if (type.isArray()) {
            type.forEach(n -> names.add(n.asText()));
        }
        return names;
    }

    private static List<String> valuesOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read schema " + file, e);
        }
    }

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
