package dev.ledgerguard.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when an event type has no entry in {@code docs/event-catalog.md}.
 *
 * <p>Documentation that can silently drift is documentation nobody trusts. ADR-0007 promises the
 * catalog is verified rather than maintained by hope; this is that verification.
 */
class EventCatalogCompletenessTest {

    private static final Path CATALOG = Path.of("../../docs/event-catalog.md");

    @Test
    @DisplayName("every event type with a schema appears in the event catalog")
    void everyEventTypeIsDocumented() {
        String catalog = readCatalog();
        List<String> eventTypes = SchemaCompatibilityTest.eventTypes();

        assertThat(eventTypes).isNotEmpty();
        for (String eventType : eventTypes) {
            assertThat(catalog)
                    .as(
                            "event type '%s' has a schema but no entry in docs/event-catalog.md. "
                                    + "Add it there, or delete the schema.",
                            eventType)
                    .contains(eventType);
        }
    }

    @Test
    @DisplayName("the catalog documents the current version of each event type")
    void catalogRecordsCurrentVersions() {
        String catalog = readCatalog();
        for (String eventType : SchemaCompatibilityTest.eventTypes()) {
            int latest = latestVersionOf(eventType);
            assertThat(catalog)
                    .as("catalog must state the current version (v%d) of %s", latest, eventType)
                    .containsPattern(eventType + "[\\s\\S]{0,400}?v" + latest);
        }
    }

    private static int latestVersionOf(String eventType) {
        Path dir = Path.of("src/main/resources/schemas", eventType);
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("v\\d+\\.json"))
                    .map(n -> Integer.parseInt(n.substring(1, n.indexOf('.'))))
                    .max(Integer::compareTo)
                    .orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readCatalog() {
        try {
            assertThat(CATALOG).as("docs/event-catalog.md must exist").exists();
            return Files.readString(CATALOG);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
