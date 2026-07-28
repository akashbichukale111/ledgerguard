package dev.ledgerguard.common.security;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Redaction utilities for sensitive personally identifiable information (PII).
 *
 * <p>Redaction format: `[REDACTED: type]` preserves enough context for debugging without exposing
 * PII in logs, error messages, or audit trails.
 */
public final class Redaction {

    private static final String REDACT_FMT = "[REDACTED:%s]";

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\b\\d{10,19}\\b");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(\\+?1[-.]?)?\\(?\\d{3}\\)?[-.]?\\d{3}[-.]?\\d{4}\\b");

    public enum SensitiveType {
        ACCOUNT_NUMBER,
        CARD_NUMBER,
        EMAIL,
        PHONE,
        SSN,
        AMOUNT,
        NAME,
        ADDRESS,
        GENERIC
    }

    /**
     * Redact account numbers (10-19 digits) from text.
     *
     * @param text text to redact
     * @return text with account numbers replaced
     */
    public static String redactAccountNumbers(String text) {
        Objects.requireNonNull(text, "text");
        return ACCOUNT_PATTERN.matcher(text).replaceAll(format(SensitiveType.ACCOUNT_NUMBER));
    }

    /**
     * Redact card numbers (4-4-4-4 format) from text.
     *
     * @param text text to redact
     * @return text with card numbers replaced
     */
    public static String redactCardNumbers(String text) {
        Objects.requireNonNull(text, "text");
        return CARD_PATTERN.matcher(text).replaceAll(format(SensitiveType.CARD_NUMBER));
    }

    /**
     * Redact email addresses from text.
     *
     * @param text text to redact
     * @return text with emails replaced
     */
    public static String redactEmails(String text) {
        Objects.requireNonNull(text, "text");
        return EMAIL_PATTERN.matcher(text).replaceAll(format(SensitiveType.EMAIL));
    }

    /**
     * Redact SSN (XXX-XX-XXXX) from text.
     *
     * @param text text to redact
     * @return text with SSNs replaced
     */
    public static String redactSsn(String text) {
        Objects.requireNonNull(text, "text");
        return SSN_PATTERN.matcher(text).replaceAll(format(SensitiveType.SSN));
    }

    /**
     * Redact phone numbers from text.
     *
     * @param text text to redact
     * @return text with phone numbers replaced
     */
    public static String redactPhones(String text) {
        Objects.requireNonNull(text, "text");
        return PHONE_PATTERN.matcher(text).replaceAll(format(SensitiveType.PHONE));
    }

    /**
     * Redact an amount (preserve for audit: show 0-3 significant figures and currency).
     *
     * <p>Example: 1234.56 USD → [REDACTED:1.2k USD]
     *
     * @param amount amount string (e.g., "1234.56")
     * @param currency currency code (e.g., "USD")
     * @return redacted representation
     */
    public static String redactAmount(String amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        try {
            double val = Double.parseDouble(amount);
            String abbreviated;
            if (Math.abs(val) >= 1_000_000) {
                abbreviated = String.format("%.1fM", val / 1_000_000);
            } else if (Math.abs(val) >= 1_000) {
                abbreviated = String.format("%.1fk", val / 1_000);
            } else {
                abbreviated = String.format("%.2f", val);
            }
            return String.format("[REDACTED:%s %s]", abbreviated, currency);
        } catch (NumberFormatException e) {
            return format(SensitiveType.AMOUNT);
        }
    }

    /**
     * Redact a generic sensitive string.
     *
     * <p>Shows only length and first 2 characters if it's long enough.
     *
     * @param value value to redact
     * @return redacted representation
     */
    public static String redact(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() <= 2) {
            return format(SensitiveType.GENERIC);
        }
        return String.format("[REDACTED:%s...(%d chars)]", value.substring(0, 2), value.length());
    }

    /**
     * Comprehensively redact all common PII from text.
     *
     * @param text text to redact
     * @return text with all common PII patterns removed
     */
    public static String redactAll(String text) {
        Objects.requireNonNull(text, "text");
        return redactPhones(redactSsn(redactEmails(redactCardNumbers(redactAccountNumbers(text)))));
    }

    private static String format(SensitiveType type) {
        return String.format(REDACT_FMT, type.name());
    }

    private Redaction() {}
}
