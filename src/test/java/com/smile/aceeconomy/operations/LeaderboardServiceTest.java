package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.ports.inmemory.FakeLeaderboardSource;
import com.smile.aceeconomy.ports.inmemory.MutableClock;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeaderboardServiceTest {

    private static final String CUR = "coin";

    private LeaderboardRow row(String uuidSuffix, String name, long balance) {
        return new LeaderboardRow(new UUID(0, Long.parseLong(uuidSuffix)), name, Amount.of(balance, 2));
    }

    @Test
    void ranksByBalanceDescTieBreakByIdAsc() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        // A=100, B=200, C=200 (tie with B), D=50
        src.put(CUR, List.of(
                row("1", "A", 100),
                row("2", "B", 200),
                row("3", "C", 200),
                row("4", "D", 50)));
        LeaderboardService svc = new LeaderboardService(src, new MutableClock(Instant.EPOCH),
                new LeaderboardCache(), Duration.ofHours(1));

        LeaderboardPage page = svc.query(CUR, 0, 10);

        assertEquals(4, page.totalEntries());
        // B(200) rank1, C(200) rank2 (id 3 > 2 so after B), A(100) rank3, D(50) rank4
        assertEquals(new UUID(0, 2), page.entries().get(0).accountId());
        assertEquals(1, page.entries().get(0).rank());
        assertEquals(new UUID(0, 3), page.entries().get(1).accountId());
        assertEquals(new UUID(0, 1), page.entries().get(2).accountId());
        assertEquals(new UUID(0, 4), page.entries().get(3).accountId());
    }

    @Test
    void paginates() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row("1", "A", 100), row("2", "B", 200), row("3", "C", 200), row("4", "D", 50)));
        LeaderboardService svc = new LeaderboardService(src, new MutableClock(Instant.EPOCH),
                new LeaderboardCache(), Duration.ofHours(1));

        LeaderboardPage p0 = svc.query(CUR, 0, 2);
        assertEquals(2, p0.entries().size());
        assertEquals(2, p0.totalPages());
        assertEquals(new UUID(0, 2), p0.entries().get(0).accountId());
        assertEquals(new UUID(0, 3), p0.entries().get(1).accountId());

        LeaderboardPage p1 = svc.query(CUR, 1, 2);
        assertEquals(2, p1.entries().size());
        assertEquals(new UUID(0, 1), p1.entries().get(0).accountId());
    }

    @Test
    void cacheHitAvoidsRecompute() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row("1", "A", 100)));
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LeaderboardService svc = new LeaderboardService(src, clock, new LeaderboardCache(), Duration.ofHours(1));

        svc.query(CUR, 0, 10);
        svc.query(CUR, 0, 10);
        assertEquals(1, src.callCount());
    }

    @Test
    void cacheExpiryRecomputes() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row("1", "A", 100)));
        MutableClock clock = new MutableClock(Instant.EPOCH);
        LeaderboardService svc = new LeaderboardService(src, clock, new LeaderboardCache(), Duration.ofMinutes(5));

        svc.query(CUR, 0, 10);
        assertEquals(1, src.callCount());
        // Advance beyond TTL.
        clock.set(Instant.EPOCH.plus(Duration.ofMinutes(10)));
        svc.query(CUR, 0, 10);
        assertEquals(2, src.callCount());
    }

    @Test
    void invalidateForcesRecompute() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row("1", "A", 100)));
        LeaderboardService svc = new LeaderboardService(src, new MutableClock(Instant.EPOCH),
                new LeaderboardCache(), Duration.ofHours(1));

        svc.query(CUR, 0, 10);
        svc.invalidate(CUR);
        svc.query(CUR, 0, 10);
        assertEquals(2, src.callCount());
    }

    @Test
    void rejectsInvalidArguments() {
        LeaderboardService svc = new LeaderboardService(new FakeLeaderboardSource(),
                new MutableClock(Instant.EPOCH), new LeaderboardCache(), Duration.ofHours(1));
        assertThrows(IllegalArgumentException.class, () -> svc.query(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> svc.query("", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> svc.query(CUR, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> svc.query(CUR, 0, 0));
    }

    @Test
    void pageTimesLimitOverflowDoesNotWrapToEarlierRanks() {
        // 429496730 * 10 overflows int to 4; the page must be empty instead of returning ranks 5..6.
        LeaderboardPage p = sixRankedService().query(CUR, 429_496_730, 10);
        assertEquals(6, p.totalEntries());
        assertEquals(1, p.totalPages());
        assertEquals(0, p.entries().size());
    }

    @Test
    void integerMaxPageWithLargeLimitDoesNotWrapToEarlierRanks() {
        // (2^31 - 1) * (2^31 - 5) is 5 modulo 2^32, so an int product would point at rank 6.
        LeaderboardPage p = sixRankedService().query(CUR, Integer.MAX_VALUE, 2_147_483_643);
        assertEquals(6, p.totalEntries());
        assertEquals(0, p.entries().size());
    }

    @Test
    void integerMaxPageReturnsEmpty() {
        LeaderboardPage p = sixRankedService().query(CUR, Integer.MAX_VALUE, 10);
        assertEquals(6, p.totalEntries());
        assertEquals(0, p.entries().size());
    }

    @Test
    void emptyRankingReturnsEmptyPageForHugePage() {
        LeaderboardService svc = new LeaderboardService(new FakeLeaderboardSource(),
                new MutableClock(Instant.EPOCH), new LeaderboardCache(), Duration.ofHours(1));
        LeaderboardPage p = svc.query(CUR, 429_496_730, 10);
        assertEquals(0, p.totalEntries());
        assertEquals(0, p.totalPages());
        assertEquals(0, p.entries().size());
    }

    @Test
    void lastPartialPageKeepsRanksAndTotals() {
        LeaderboardPage last = sixRankedService().query(CUR, 1, 4);
        assertEquals(6, last.totalEntries());
        assertEquals(2, last.totalPages());
        assertEquals(2, last.entries().size());
        assertEquals(5, last.entries().get(0).rank());
        assertEquals(6, last.entries().get(1).rank());
    }

    @Test
    void hugeLimitOnFirstPageReturnsAllRanks() {
        LeaderboardPage p = sixRankedService().query(CUR, 0, Integer.MAX_VALUE);
        assertEquals(6, p.entries().size());
        assertEquals(1, p.totalPages());
    }

    private LeaderboardService sixRankedService() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row("1", "A", 100), row("2", "B", 200), row("3", "C", 200),
                row("4", "D", 50), row("5", "E", 30), row("6", "F", 10)));
        return new LeaderboardService(src, new MutableClock(Instant.EPOCH), new LeaderboardCache(), Duration.ofHours(1));
    }
}
