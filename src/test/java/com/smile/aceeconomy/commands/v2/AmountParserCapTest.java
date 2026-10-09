package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the public cap boundary at the command entry: exactly 10^15 parses,
 * anything above is a typed overflow — the same limit the service and storage
 * gates enforce.
 */
class AmountParserCapTest {

    @Test
    @DisplayName("exactly the cap parses; cap+1 and huge values overflow")
    void boundary() {
        assertEquals(new BigDecimal("1000000000000000.00"),
                AmountParser.parse("1000000000000000.00", 2).value());
        assertEquals("ACELIB-CMD-AMOUNT-OVERFLOW", assertThrows(CommandException.class,
                () -> AmountParser.parse("1000000000000001", 2)).getCode());
        assertEquals("ACELIB-CMD-AMOUNT-OVERFLOW", assertThrows(CommandException.class,
                () -> AmountParser.parse("1000000000000000.01", 2)).getCode());
        assertEquals("ACELIB-CMD-AMOUNT-OVERFLOW", assertThrows(CommandException.class,
                () -> AmountParser.parse("99999999999999999999", 2)).getCode());
    }

    @Test
    @DisplayName("cap is scale-independent: integer-scale cap parses, cap+1 overflows")
    void integerScaleBoundary() {
        assertEquals(new BigDecimal("1000000000000000"),
                AmountParser.parse("1000000000000000", 0).value());
        assertEquals("ACELIB-CMD-AMOUNT-OVERFLOW", assertThrows(CommandException.class,
                () -> AmountParser.parse("1000000000000001", 0)).getCode());
    }
}
