package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebtPolicyTest {

    @Test
    @DisplayName("disabled policy: no negative balance permitted")
    void disabled() {
        DebtPolicy p = DebtPolicy.disabled();
        assertTrue(p.allows(Amount.of(100, 2)));
        assertTrue(p.allows(Amount.of(0, 2)));
        assertFalse(p.allows(Amount.of(-1, 2)));
        assertFalse(p.isAllowNegative());
    }

    @Test
    @DisplayName("enabled policy: bounded by debt limit")
    void enabled() {
        DebtPolicy p = DebtPolicy.enabled(Amount.of(500, 2));
        assertTrue(p.allows(Amount.of(100, 2)));
        assertTrue(p.allows(Amount.of(-500, 2)));   // exactly at limit
        assertFalse(p.allows(Amount.of(-501, 2)));  // beyond limit
        assertTrue(p.isAllowNegative());
        assertEquals(Amount.of(500, 2), p.debtLimit());
    }

    @Test
    @DisplayName("enabled policy rejects negative limit")
    void rejectsNegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> DebtPolicy.enabled(Amount.of(-1, 2)));
    }

    @Test
    @DisplayName("allows() is scale-independent")
    void scaleIndependent() {
        // token scale 0, debt limit expressed at scale 2 — comparison must still work
        DebtPolicy p = DebtPolicy.enabled(Amount.of(500, 2));
        assertTrue(p.allows(Amount.of(-500, 0)));
        assertFalse(p.allows(Amount.of(-501, 0)));
    }
}
