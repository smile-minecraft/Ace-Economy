package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.LockEpochCapacity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity gate for the balance-cache epoch map: per-owner epoch holders are never
 * removed, so the map grows with every distinct owner ever cached. Past the cap a
 * new owner is served uncached (reads fall through to storage, the documented safe
 * degradation) instead of growing the map without bound. The cache is never the
 * source of truth, so a miss is always safe — unlike the session store, no typed
 * failure is needed here, but the refusal is still explicit: publishes report
 * {@code false} and the cap state stays queryable.
 */
final class AccountBalanceCacheCapacityTest {

    private static final String CUR = "coin";

    private static Amount amount(long minor) {
        return Amount.of(minor, 2);
    }

    @Test
    void newOwnerPastCapIsServedUncached() {
        AccountBalanceCache cache = new AccountBalanceCache(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertTrue(cache.putIfStamp(first, CUR, amount(10L), cache.stampOf(first)));
        assertTrue(cache.putIfStamp(second, CUR, amount(20L), cache.stampOf(second)));
        assertTrue(cache.isTrackingCapacityReached());

        AccountBalanceCache.CacheStamp stamp = cache.stampOf(third);
        assertFalse(cache.putIfStamp(third, CUR, amount(30L), stamp),
                "uncached degradation must be reported, never silently published");
        assertFalse(cache.putIfStampIfAbsent(third, CUR, amount(30L), stamp));
        assertTrue(cache.get(third, CUR).isEmpty(),
                "no entry may linger for an owner the epoch map cannot track");
    }

    @Test
    void unconditionalPutPastCapLeavesNoResidue() {
        AccountBalanceCache cache = new AccountBalanceCache(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cache.put(first, CUR, amount(10L));
        cache.put(second, CUR, amount(20L));

        assertTrue(cache.get(second, CUR).isEmpty(),
                "an untracked owner must stay a miss so the next read re-primes from storage");
        assertEquals(amount(10L), cache.get(first, CUR).orElseThrow());
    }

    @Test
    void trackedOwnersKeepWorkingWhenFull() {
        AccountBalanceCache cache = new AccountBalanceCache(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(cache.putIfStamp(first, CUR, amount(10L), cache.stampOf(first)));
        assertTrue(cache.putIfStamp(second, CUR, amount(20L), cache.stampOf(second)));

        cache.invalidate(first);
        assertTrue(cache.get(first, CUR).isEmpty());
        assertTrue(cache.putIfStamp(first, CUR, amount(11L), cache.stampOf(first)),
                "an already-tracked owner is not new growth and must keep working");
        assertEquals(amount(11L), cache.get(first, CUR).orElseThrow());
    }

    @Test
    void invalidateStillCleansBalancesWhenEpochMapIsFull() {
        AccountBalanceCache cache = new AccountBalanceCache(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cache.put(first, CUR, amount(10L));
        cache.put(second, CUR, amount(20L));
        cache.invalidate(second);

        assertTrue(cache.get(second, CUR).isEmpty());
        assertEquals(1, cache.trackedOwnerCount());
    }

    @Test
    void defaultCapMatchesSharedContract() {
        assertEquals(LockEpochCapacity.MAX_TRACKED_IDS, new AccountBalanceCache().trackedOwnerCap());
    }
}
