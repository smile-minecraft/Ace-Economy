package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared lock/epoch capacity contract: one explicit bound for every in-memory
 * tracking map that grows with distinct IDs, plus the overflow argument for the
 * 64-bit epoch counters that are deliberately left uncapped.
 */
final class LockEpochCapacityTest {

    @Test
    void trackedIdCapIsPositiveAndDocumented() {
        assertTrue(LockEpochCapacity.MAX_TRACKED_IDS >= 1_000,
                "cap must cover far more distinct players than any single restart cycle serves");
        assertEquals(100_000, LockEpochCapacity.MAX_TRACKED_IDS,
                "default cap is 100k distinct IDs; change deliberately with a new measurement");
    }

    @Test
    void sessionRefusalNamesLimitAndPromisesNoMutation() {
        UUID uuid = UUID.randomUUID();
        String message = LockEpochCapacity.describeSessionRefusal(uuid, LockEpochCapacity.MAX_TRACKED_IDS);

        assertTrue(message.contains("100000") || message.contains("100,000"),
                "refusal must name the limit so the operator can act on it");
        assertTrue(message.contains("nothing changed"),
                "refusal must promise no mutation, mirroring the amount-cap wording");
    }

    @Test
    void flushFailureOverflowNamesLimit() {
        String message = LockEpochCapacity.describeFlushFailureOverflow(3);

        assertTrue(message.contains("100000") || message.contains("100,000"),
                "overflow diagnosis must name the limit");
    }

    @Test
    void epochCountersDoNotOverflowWithinAnyOperationalHorizon() {
        // A million invalidations per second is already absurd for one server;
        // the 64-bit counters still outlive the hardware by five orders of magnitude.
        double years = LockEpochCapacity.overflowYearsAt(1_000_000L);

        assertTrue(years > 100_000.0,
                "64-bit epoch must be practically unreachable, got " + years + " years");
    }

    @Test
    void overflowMathRejectsNonPositiveRates() {
        assertThrows(IllegalArgumentException.class, () -> LockEpochCapacity.overflowYearsAt(0L));
        assertThrows(IllegalArgumentException.class, () -> LockEpochCapacity.overflowYearsAt(-5L));
    }
}
