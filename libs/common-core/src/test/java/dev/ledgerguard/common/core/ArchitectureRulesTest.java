package dev.ledgerguard.common.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules for {@code common-core}, enforced at build time.
 *
 * <p>Layered architectures decay because nothing stops them decaying. A developer under deadline
 * pressure injects a repository into a controller; nothing fails; the next developer copies the
 * pattern. These rules are what make {@code docs/adr/0013-hexagonal-layering.md} survive contact
 * with a deadline — the package structure is the easy part.
 *
 * <p>Scope note: this class covers {@code common-core}, which is the module with the strictest
 * constraint (no framework at all). Service-level rules — controllers not touching repositories,
 * no cross-service imports — are added with those services in Phases 4 to 6, since a rule about
 * classes that do not exist yet passes vacuously and proves nothing.
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ledgerguard.common.core");
    }

    @Test
    @DisplayName("no floating point anywhere near money")
    void noFloatingPointTypes() {
        // The single most disqualifying thing a reviewer can find in a financial system.
        // Enforced structurally rather than trusted to discipline. See ADR-0009.
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(Double.class.getName())
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(Float.class.getName())
                .because("monetary values must be BigDecimal — binary floating point cannot "
                        + "represent decimal fractions exactly (ADR-0009)");
        rule.check(classes);
    }

    @Test
    @DisplayName("no legacy date/time API")
    void noLegacyTimeApi() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Calendar")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.sql.Timestamp")
                .because("all time is UTC java.time.Instant, read from an injected Clock so tests "
                        + "can use a fixed clock instead of sleeping");
        rule.check(classes);
    }

    @Test
    @DisplayName("no calls to System.currentTimeMillis or Instant.now")
    void timeComesFromAnInjectedClock() {
        // Reading the wall clock directly is what makes a test need Thread.sleep. Every timestamp
        // must come from an injected Clock so a fixed clock can drive saga timeouts and SLA
        // expiry deterministically.
        ArchRule rule = noClasses()
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .because("time must be read from an injected java.time.Clock");
        rule.check(classes);
    }

    @Test
    @DisplayName("common-core depends on no framework")
    void domainIsFrameworkFree() {
        // This is what keeps Money and the state machines unit-testable in milliseconds with no
        // Spring context. The moment an annotation creeps in, that property is gone.
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "org.apache.kafka..")
                .because("common-core holds framework-free domain primitives (ADR-0013); "
                        + "persistence and serialization belong in adapters");
        rule.check(classes);
    }

    @Test
    @DisplayName("no printing to stdout or stderr")
    void noConsoleOutput() {
        ArchRule rule = noClasses()
                .should()
                .accessField(System.class, "out")
                .orShould()
                .accessField(System.class, "err")
                .orShould()
                .callMethod(Throwable.class, "printStackTrace")
                .because("all output is structured JSON logging with correlation IDs; "
                        + "a println is invisible to an operator");
        rule.check(classes);
    }

    @Test
    @DisplayName("no raw java.util.Random for anything security-adjacent")
    void noWeakRandomness() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Random")
                .because("java.util.Random is predictable from observed output; use SecureRandom");
        rule.check(classes);
    }
}
