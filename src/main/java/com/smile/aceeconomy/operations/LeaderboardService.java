package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import com.smile.aceeconomy.ports.operations.LeaderboardSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Leaderboard query/cache boundary.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li><b>Deterministic ordering</b> — entries are ranked by balance descending; ties are broken
 *       by {@link UUID} ascending so the ranking is reproducible.</li>
 *   <li><b>Pagination</b> — 0-based {@code page} and strictly positive {@code limit}; invalid
 *       values are rejected rather than clamped.</li>
 *   <li><b>Cache</b> — a per-currency ranking is cached with a TTL; it is recomputed on expiry,
 *       on explicit {@link #invalidate} / {@link #invalidateAll}, or when absent. Cached lists are
 *       returned as immutable copies, so internal state is never exposed.</li>
 * </ul>
 */
public final class LeaderboardService {

    private final LeaderboardSource source;
    private final Clock clock;
    private final LeaderboardCache cache;
    private final Duration ttl;

    public LeaderboardService(LeaderboardSource source, Clock clock, LeaderboardCache cache, Duration ttl) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be non-negative");
        }
    }

    public LeaderboardPage query(String currencyId, int page, int limit) {
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId must not be blank");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }

        Instant now = clock.instant();
        List<LeaderboardEntry> ranked = cache.getIfFresh(currencyId, ttl, now);
        if (ranked == null) {
            long generation = cache.generation();
            ranked = computeRanking(currencyId);
            // A recompute that started before an invalidation must not refill the cache
            // with its stale snapshot; the current caller still gets its computed page
            // while the next query recomputes from the fresh source.
            cache.putIfGenerationUnchanged(currencyId, ranked, now, generation);
        }

        int total = ranked.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / limit);
        // The offset is computed in long: page * limit can exceed int range, and an int product
        // would wrap back onto an earlier page instead of landing past the end.
        long start = (long) page * limit;
        List<LeaderboardEntry> slice;
        if (start >= total) {
            slice = List.of();
        } else {
            int end = (int) Math.min(start + limit, total);
            slice = List.copyOf(ranked.subList((int) start, end));
        }
        return new LeaderboardPage(slice, page, limit, total, totalPages);
    }

    /** Drop the cached ranking for one currency so the next query recomputes it. */
    public void invalidate(String currencyId) {
        cache.invalidate(currencyId);
    }

    /** Drop every cached ranking. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private List<LeaderboardEntry> computeRanking(String currencyId) {
        List<LeaderboardRow> rows = source.rows(currencyId);
        // Sort by balance desc, tie-break by accountId asc for determinism.
        Comparator<LeaderboardRow> byBalance = Comparator.comparing(
                (LeaderboardRow r) -> r.balance().value()).reversed();
        Comparator<LeaderboardRow> byId = Comparator.comparing(LeaderboardRow::accountId);
        List<LeaderboardRow> sorted = new ArrayList<>(rows);
        sorted.sort(byBalance.thenComparing(byId));

        List<LeaderboardEntry> ranked = new ArrayList<>(sorted.size());
        int rank = 1;
        for (LeaderboardRow r : sorted) {
            ranked.add(new LeaderboardEntry(rank++, r.accountId(), r.ownerName(), r.balance()));
        }
        return List.copyOf(ranked);
    }
}
