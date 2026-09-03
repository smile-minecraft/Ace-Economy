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
        return parse(null, raw, scale);
    }

    public static Amount parse(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                               String raw, int scale) {
        if (raw == null || raw.isBlank()) {
            String msg = messages != null
                    ? messages.plainMessage("general.invalid-amount", java.util.Map.of("amount", raw == null ? "" : raw.trim()))
                    : "general.invalid-amount";
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT", msg);
        }
        final BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            String msg = messages != null
                    ? messages.plainMessage("general.invalid-amount", java.util.Map.of("amount", raw.trim()))
                    : "general.invalid-amount";
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT", msg);
        }
        if (decimal.scale() > scale) {
            String msg = messages != null
                    ? messages.plainMessage("general.invalid-amount", java.util.Map.of("amount", raw.trim()))
                    : "general.invalid-amount";
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT", msg);
        }
        if (decimal.signum() <= 0) {
            String msg = messages != null
                    ? messages.plainMessage("general.amount-must-be-positive", java.util.Map.of())
                    : "general.amount-must-be-positive";
            throw CommandException.custom("ACELIB-CMD-AMOUNT-NON-POSITIVE", msg);
        }
        if (decimal.compareTo(MAX_VALUE) > 0) {
            String msg = messages != null
                    ? messages.plainMessage("general.amount-overflow", java.util.Map.of())
                    : "general.amount-overflow";
            if (msg.startsWith("Missing translation")) {
                msg = messages != null ? messages.plainMessage("command.amount-overflow", java.util.Map.of()) : msg;
            }
            throw CommandException.custom("ACELIB-CMD-AMOUNT-OVERFLOW", msg);
        }
        try {
            return Amount.of(decimal, scale);
        } catch (IllegalArgumentException e) {
            String msg = messages != null
                    ? messages.plainMessage("general.invalid-amount", java.util.Map.of("amount", raw.trim()))
                    : "general.invalid-amount";
            throw CommandException.custom("ACELIB-CMD-INVALID-AMOUNT", msg);
        }
    }
}
