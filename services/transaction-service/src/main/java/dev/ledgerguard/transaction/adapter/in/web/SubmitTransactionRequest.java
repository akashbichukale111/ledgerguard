package dev.ledgerguard.transaction.adapter.in.web;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body.
 *
 * <p>{@code amount} is a <b>string</b>, not a number. Accepting a JSON number would mean Jackson
 * parses it into a double before we ever see it, and the precision is gone before validation runs
 * (ADR-0009).
 *
 * <p>Bean Validation here is the edge check only. Domain invariants — non-zero amount, distinct
 * accounts, currency-scale compatibility — are asserted again in {@code SubmitTransactionCommand}.
 * A validated DTO is not a domain invariant.
 */
public record SubmitTransactionRequest(
        @NotBlank @Size(max = 140) String reference,
        @NotBlank @Pattern(regexp = "^-?[0-9]+(\\.[0-9]+)?$", message = "amount must be a decimal string")
                String amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 alphabetic code")
                String currency,
        @NotBlank @Pattern(regexp = "^(DEBIT|CREDIT)$") String direction,
        @NotBlank @Size(max = 128) String counterpartyId,
        @NotBlank @Size(max = 64) String debitAccount,
        @NotBlank @Size(max = 64) String creditAccount,
        @NotNull LocalDate valueDate,
        LocalDate postingDate,
        @Size(max = 64) String settlementSystem) {}
