package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.operations.LeaderboardEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory leaderboard ranking cache. Holds an immutable snapshot per currency together with the
 * instant it was computed, and serves it only while it remains within the requested TTL.
 *
 * <p>Callers receive immutable copies ({@link List#copyOf}); the cache never hands out its internal
 * mutable collections.</p>
 */
public final class LeaderboardCache {

    private record CachedRanking(List<LeaderboardEntry> entries, Instant computedAt) {
    }

    private final Map<String, CachedRanking> cache = new ConcurrentHashMap<>();

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

    public void invalidate(String currencyId) {
        cache.remove(currencyId);
    }

    public void invalidateAll() {
        cache.clear();
    }
}
