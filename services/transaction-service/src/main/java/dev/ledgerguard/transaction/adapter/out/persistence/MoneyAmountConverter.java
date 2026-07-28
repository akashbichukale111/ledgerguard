package dev.ledgerguard.transaction.adapter.out.persistence;

import java.math.BigDecimal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists the numeric half of a {@code Money} as {@code NUMERIC(19,4)}.
 *
 * <p>Deliberately <b>not</b> an {@code @Embeddable} over the whole {@code Money}: keeping the
 * converter to the amount and mapping currency as its own {@code CHAR(3)} column makes both
 * independently indexable and queryable, which the matching engine's blocking keys need.
 *
 * <p>This adapter is why {@code common-core} can stay free of JPA (ADR-0013).
 */
@Converter(autoApply = false)
public class MoneyAmountConverter implements AttributeConverter<BigDecimal, BigDecimal> {

    /** Scale 4 is the column's scale; it exceeds every ISO 4217 minor-unit count (max 3). */
    private static final int COLUMN_SCALE = 4;

    @Override
    public BigDecimal convertToDatabaseColumn(BigDecimal attribute) {
        if (attribute == null) {
            return null;
        }
        // setScale without a RoundingMode throws rather than truncating. A value that cannot be
        // represented at the column's scale is a bug upstream, and must not be silently rounded on
        // its way to disk.
        return attribute.setScale(COLUMN_SCALE);
    }

    @Override
    public BigDecimal convertToEntityAttribute(BigDecimal dbData) {
        return dbData;
    }
}
