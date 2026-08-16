package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AmountTest {

    @Test
    @DisplayName("NaN/Infinity input is rejected")
    void rejectsNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> Amount.of(Double.NaN, 2));
        assertThrows(IllegalArgumentException.class, () -> Amount.of(Double.POSITIVE_INFINITY, 2));
        assertThrows(IllegalArgumentException.class, () -> Amount.of(Double.NEGATIVE_INFINITY, 2));
    }

    @Test
    @DisplayName("over-scale input is rejected (no implicit rounding)")
    void rejectsOverScale() {
        assertThrows(IllegalArgumentException.class, () -> Amount.of(new BigDecimal("0.001"), 2));
        assertThrows(IllegalArgumentException.class, () -> Amount.of(new BigDecimal("1.234"), 2));
        // exact scale is accepted
        assertDoesNotThrow(() -> Amount.of(new BigDecimal("0.10"), 2));
        assertDoesNotThrow(() -> Amount.of(new BigDecimal("100"), 0));
    }

    @Test
    @DisplayName("null value is rejected")
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Amount.of((BigDecimal) null, 2));
    }

    @Test
    @DisplayName("arithmetic is pure and preserves scale")
    void arithmetic() {
        Amount a = Amount.of(100, 2);
        Amount b = Amount.of(40, 2);
        assertEquals(Amount.of(140, 2), a.add(b));
        assertEquals(Amount.of(60, 2), a.subtract(b));
        assertEquals(Amount.of(-100, 2), a.negate());
        assertEquals(Amount.of(100, 2), a.abs());
    }

    @Test
    @DisplayName("sign predicates")
    void signs() {
        assertTrue(Amount.of(1, 2).isPositive());
        assertTrue(Amount.of(0, 2).isZero());
        assertTrue(Amount.of(-1, 2).isNegative());
        assertTrue(Amount.of(-1, 2).isNonPositive());
        assertTrue(Amount.of(0, 2).isNonNegative());
    }

    @Test
    @DisplayName("equality is value+scale based")
    void equality() {
        assertEquals(Amount.of(100, 2), Amount.of(new BigDecimal("100.00"), 2));
        // different scale with same numeric value are NOT equal (scale is part of the contract)
        assertNotEquals(Amount.of(100, 2), Amount.of(100, 0));
    }

    @Test
    @DisplayName("mismatched scale arithmetic throws")
    void scaleMismatch() {
        assertThrows(IllegalArgumentException.class, () -> Amount.of(1, 2).add(Amount.of(1, 0)));
    }
}
