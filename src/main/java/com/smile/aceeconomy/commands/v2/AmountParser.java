package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.domain.Amount;

import java.math.BigDecimal;

/**
 * Parses and validates a raw amount string against a currency's scale, throwing a
 * <em>typed</em> {@link CommandException} (code-based, never message-based) on any failure.
 *
 * <p>Validation contract:</p>
 * <ul>
 *   <li>blank / non-numeric → {@code ACELIB-CMD-INVALID-AMOUNT}</li>
 *   <li>more fractional digits than the currency scale → {@code ACELIB-CMD-INVALID-AMOUNT}</li>
 *   <li>zero or negative → {@code ACELIB-CMD-AMOUNT-NON-POSITIVE}</li>
 *   <li>above the economy cap → {@code ACELIB-CMD-AMOUNT-OVERFLOW}</li>
 * </ul>
 */
public final class AmountParser {

    /** Hard economy cap: 1e15 of any currency. */
    private static final BigDecimal MAX_VALUE = new BigDecimal("1000000000000000");

    private AmountParser() {
    }

    public static Amount parse(String raw, int scale) {
        if (raw == null || raw.isBlank()) {
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT", "amount is required");
        }
        final BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT",
                    "amount is not a valid number: " + raw.trim());
        }
        if (decimal.scale() > scale) {
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT",
                    "amount has too many decimal places for the currency");
        }
        if (decimal.signum() <= 0) {
            throw CommandException.custom("ACELIB-CMD-AMOUNT-NON-POSITIVE", "amount must be positive");
        }
        if (decimal.compareTo(MAX_VALUE) > 0) {
            throw CommandException.custom("ACELIB-CMD-AMOUNT-OVERFLOW", "amount exceeds the maximum allowed");
        }
        try {
            return Amount.of(decimal, scale);
        } catch (IllegalArgumentException e) {
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT", e.getMessage());
        }
    }
}
