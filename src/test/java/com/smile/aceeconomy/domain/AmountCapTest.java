package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary contract for the public amount cap: one limit shared by the command
 * entry, the service gates and the storage write guards.
 */
class AmountCapTest {

    private static final BigDecimal CAP = new BigDecimal("1000000000000000");

    @Test
    @DisplayName("cap is exactly 10^15")
    void capValue() {
        assertEquals(0, CAP.compareTo(AmountCap.max()));
        assertEquals("1000000000000000", AmountCap.max().toPlainString());
    }

    @Test
    @DisplayName("values at the cap are allowed, cap+1 is refused")
    void boundary() {
        assertFalse(AmountCap.exceeds(CAP));
        assertFalse(AmountCap.exceeds(new BigDecimal("1000000000000000.00")));
        assertTrue(AmountCap.exceeds(new BigDecimal("1000000000000001")));
        assertTrue(AmountCap.exceeds(new BigDecimal("1000000000000000.01")));
        assertFalse(AmountCap.exceeds(new BigDecimal("999999999999999.99")));
    }

    @Test
    @DisplayName("huge magnitudes are refused at any scale")
    void hugeValues() {
        assertTrue(AmountCap.exceeds(new BigDecimal("100000000000000000000.00")));
        assertTrue(AmountCap.exceeds(new BigDecimal("9".repeat(80))));
        assertFalse(AmountCap.exceeds(BigDecimal.ZERO));
        assertFalse(AmountCap.exceeds(new BigDecimal("0.00001")));
    }

    @Test
    @DisplayName("Amount overload compares the numeric value, not the scale")
    void amountOverload() {
        assertFalse(AmountCap.exceeds(Amount.of(new BigDecimal("1000000000000000.00"), 2)));
        assertTrue(AmountCap.exceeds(Amount.of(new BigDecimal("1000000000000000.01"), 2)));
        assertTrue(AmountCap.exceeds(Amount.of(1000000000000001L, 0)));
        assertFalse(AmountCap.exceeds(Amount.of(1000000000000000L, 0)));
    }

    @Test
    @DisplayName("refusal diagnosis names the value and the cap")
    void refusalText() {
        Amount over = Amount.of(new BigDecimal("1000000000000001.00"), 2);
        String request = AmountCap.describeRequest("dollar", over);
        assertTrue(request.contains("dollar"), "must name the currency, got: " + request);
        assertTrue(request.contains("1000000000000000"), "must name the cap, got: " + request);
        assertTrue(request.contains("nothing changed"), "must promise no mutation, got: " + request);

        String stored = AmountCap.describeStored("dollar", over);
        assertTrue(stored.contains("dollar"), "got: " + stored);
        assertTrue(stored.contains("stored"), "must say the stored data is at fault, got: " + stored);

        String result = AmountCap.describeResult("dollar", over);
        assertTrue(result.contains("dollar"), "got: " + result);
        assertTrue(result.contains("1000000000000000"), "got: " + result);
    }

    @Test
    @DisplayName("cap representations fit the VARCHAR(64) column at sane scales")
    void columnFit() {
        // 16 digits + dot + scale fraction digits must stay within 64 chars.
        assertTrue(AmountCap.fitsColumn("1000000000000000.00"));
        assertTrue(AmountCap.fitsColumn("1000000000000000.00000000"));
        assertTrue(AmountCap.fitsColumn("1000000000000000." + "0".repeat(47)));
        assertFalse(AmountCap.fitsColumn("1000000000000000." + "0".repeat(48)));
        // A minus sign costs one char: negatives fit up to scale 46.
        assertTrue(AmountCap.fitsColumn("-1000000000000000.00"));
        assertTrue(AmountCap.fitsColumn("-1000000000000000." + "0".repeat(46)));
        assertFalse(AmountCap.fitsColumn("-1000000000000000." + "0".repeat(47)));
        assertEquals(64, AmountCap.MAX_COLUMN_CHARS);
    }

    @Test
    @DisplayName("storage refusal fires for over-cap and over-long plain strings")
    void storableRefusal() {
        assertEquals(Optional.empty(), AmountCap.storableRefusal("1000000000000000.00"));
        assertEquals(Optional.empty(), AmountCap.storableRefusal("0.00"));
        Optional<String> overCap = AmountCap.storableRefusal("1000000000000001.00");
        assertTrue(overCap.isPresent(), "over-cap plain string must be refused");
        assertTrue(overCap.get().contains("1000000000000000"), "got: " + overCap.get());
        Optional<String> overLong = AmountCap.storableRefusal("1." + "0".repeat(80));
        assertTrue(overLong.isPresent(), "over-long plain string must be refused even below cap");
    }
}
