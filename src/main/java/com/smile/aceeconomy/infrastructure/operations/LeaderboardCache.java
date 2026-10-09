package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.operations.LeaderboardEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory leaderboard ranking cache. Holds an immutable snapshot per currency together with the
 * instant it was computed, and serves it only while it remains within the requested TTL.
 *
 * <p>Callers receive immutable copies ({@link List#copyOf}); the cache never hands out its internal
 * mutable collections.</p>
 *
 * <p>Invalidation generations: every {@link #invalidate} / {@link #invalidateAll} advances a
 * monotone generation. A recompute captures the generation before reading the source and only
 * installs its result when the generation is unchanged, so a recompute that started before an
 * invalidation can never refill the cache with a stale snapshot.</p>
 */
public final class LeaderboardCache {

    private record CachedRanking(List<LeaderboardEntry> entries, Instant computedAt) {
    }

    private final Map<String, CachedRanking> cache = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong(0);

    /**
     * @return the cached ranking when present and still within {@code ttl} of {@code now}, else {@code null}.
     */
    public List<LeaderboardEntry> getIfFresh(String currencyId, Duration ttl, Instant now) {
        CachedRanking c = cache.get(currencyId);
        if (c == null) {
            return null;
        }
        if (c.computedAt().plus(ttl).isBefore(now)) {
            return null;
        }
        return List.copyOf(c.entries());
    }

    public void put(String currencyId, List<LeaderboardEntry> entries, Instant computedAt) {
        cache.put(currencyId, new CachedRanking(List.copyOf(entries), computedAt));
    }

    /**
     * Install a recomputed ranking only when no invalidation happened since
     * {@code expectedGeneration} was observed. Returns {@code true} when installed.
     */
    public synchronized boolean putIfGenerationUnchanged(String currencyId, List<LeaderboardEntry> entries,
                                                         Instant computedAt, long expectedGeneration) {
        if (generation.get() != expectedGeneration) {
            return false;
        }
        cache.put(currencyId, new CachedRanking(List.copyOf(entries), computedAt));
        return true;
    }

    /** Current invalidation generation. Captured before a recompute, checked on install. */
    public long generation() {
        return generation.get();
    }

    public synchronized void invalidate(String currencyId) {
        cache.remove(currencyId);
        generation.incrementAndGet();
    }

    public synchronized void invalidateAll() {
        cache.clear();
        generation.incrementAndGet();
    }
}
