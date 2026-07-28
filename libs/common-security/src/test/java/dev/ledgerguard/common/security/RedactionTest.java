package dev.ledgerguard.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Redaction")
class RedactionTest {

    @Nested
    @DisplayName("Account number redaction")
    class AccountNumberRedaction {

        @Test
        void redactsAccountNumbers() {
            String text = "Account 1234567890 is blocked";
            String redacted = Redaction.redactAccountNumbers(text);

            assertThat(redacted).contains("[REDACTED:ACCOUNT_NUMBER]").doesNotContain("1234567890");
        }

        @Test
        void preservesNonAccountNumbers() {
            String text = "Transaction ID 123 completed";
            String redacted = Redaction.redactAccountNumbers(text);

            assertThat(redacted).isEqualTo(text);
        }
    }

    @Nested
    @DisplayName("Card number redaction")
    class CardNumberRedaction {

        @Test
        void redactsCardNumbers() {
            String text = "Card 4532-1234-5678-9012 declined";
            String redacted = Redaction.redactCardNumbers(text);

            assertThat(redacted).contains("[REDACTED:CARD_NUMBER]").doesNotContain("4532");
        }

        @Test
        void redactsCardNumbersWithoutDashes() {
            String text = "Card 4532123456789012 declined";
            String redacted = Redaction.redactCardNumbers(text);

            assertThat(redacted).contains("[REDACTED:CARD_NUMBER]");
        }
    }

    @Nested
    @DisplayName("Email redaction")
    class EmailRedaction {

        @Test
        void redactsEmails() {
            String text = "Contact user@example.com for support";
            String redacted = Redaction.redactEmails(text);

            assertThat(redacted).contains("[REDACTED:EMAIL]").doesNotContain("user@example.com");
        }

        @Test
        void redactsMultipleEmails() {
            String text = "alice@example.com and bob@example.com";
            String redacted = Redaction.redactEmails(text);

            assertThat(redacted).contains("[REDACTED:EMAIL]").contains("[REDACTED:EMAIL]");
        }
    }

    @Nested
    @DisplayName("SSN redaction")
    class SsnRedaction {

        @Test
        void redactsSsn() {
            String text = "SSN 123-45-6789 verified";
            String redacted = Redaction.redactSsn(text);

            assertThat(redacted).contains("[REDACTED:SSN]").doesNotContain("123-45-6789");
        }
    }

    @Nested
    @DisplayName("Phone redaction")
    class PhoneRedaction {

        @Test
        void redactsPhoneNumbers() {
            String text = "Call +1-555-123-4567 anytime";
            String redacted = Redaction.redactPhones(text);

            assertThat(redacted).contains("[REDACTED:PHONE]").doesNotContain("555-123-4567");
        }

        @Test
        void redactsPhoneWithoutFormatting() {
            String text = "Call 5551234567 anytime";
            String redacted = Redaction.redactPhones(text);

            assertThat(redacted).contains("[REDACTED:PHONE]");
        }
    }

    @Nested
    @DisplayName("Amount redaction")
    class AmountRedaction {

        @Test
        void redactsAmountWithAbbreviation() {
            String redacted = Redaction.redactAmount("1234.56", "USD");

            assertThat(redacted).startsWith("[REDACTED:1.2k USD]");
        }

        @Test
        void redactsLargeAmount() {
            String redacted = Redaction.redactAmount("1234567.89", "USD");

            assertThat(redacted).contains("1.2M USD");
        }

        @Test
        void redactsSmallAmount() {
            String redacted = Redaction.redactAmount("123.45", "USD");

            assertThat(redacted).contains("123.45");
        }

        @Test
        void handlesInvalidAmount() {
            String redacted = Redaction.redactAmount("not-a-number", "USD");

            assertThat(redacted).contains("[REDACTED:AMOUNT]");
        }
    }

    @Nested
    @DisplayName("Generic redaction")
    class GenericRedaction {

        @Test
        void redactsGenericString() {
            String redacted = Redaction.redact("secret123456");

            assertThat(redacted).contains("se...").contains("(12 chars)");
        }

        @Test
        void redactsShortString() {
            String redacted = Redaction.redact("ab");

            assertThat(redacted).contains("[REDACTED:GENERIC]");
        }
    }

    @Nested
    @DisplayName("Comprehensive redaction")
    class ComprehensiveRedaction {

        @Test
        void redactsMultiplePiiTypes() {
            String text = "Account 1234567890 owner user@example.com phone 555-123-4567";
            String redacted = Redaction.redactAll(text);

            assertThat(redacted)
                    .contains("[REDACTED:ACCOUNT_NUMBER]")
                    .contains("[REDACTED:EMAIL]")
                    .contains("[REDACTED:PHONE]")
                    .doesNotContain("1234567890")
                    .doesNotContain("user@example.com")
                    .doesNotContain("555-123-4567");
        }
    }
}
