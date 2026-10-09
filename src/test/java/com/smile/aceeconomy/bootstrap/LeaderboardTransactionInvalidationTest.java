package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.ports.CommittedTransactionListener;
import com.smile.aceeconomy.ports.inmemory.FakeLeaderboardSource;
import com.smile.aceeconomy.ports.inmemory.MutableClock;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the live {@code /baltop top} symptom: after a balance change the ranking kept
 * serving the pre-change snapshot until the TTL expired. The production composition root
 * registers one post-commit transaction-listener that drops the shared leaderboard cache; these
 * tests lock that wiring, including the shutdown unregister path and the rule that uncommitted
 * (pre-commit, even uncancelled) events never invalidate.
 */
class LeaderboardTransactionInvalidationTest {

    private static final String CUR = "coin";

    private static LeaderboardRow row(long id, String name, long balance) {
        return new LeaderboardRow(new UUID(0, id), name, Amount.of(balance, 2));
    }

    private static Transaction committedDeposit() {
        return new Transaction(UUID.randomUUID(), UUID.randomUUID(), null, CUR,
                Amount.of(1, 2), TransactionType.DEPOSIT, Amount.of(0, 2), Amount.of(1, 2),
                Instant.EPOCH, "deposit");
    }

    @Test
    void committedTransactionInvalidatesTheSharedLeaderboardCacheOnNextQuery() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row(1, "A", 100)));
        LeaderboardCache cache = new LeaderboardCache();
        LeaderboardService service = new LeaderboardService(
                src, new MutableClock(Instant.EPOCH), cache, Duration.ofHours(1));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();

        CommittedTransactionListener listener = CompositionRoot.wireLeaderboardInvalidation(publisher, service);

        // Prime the cache with A at the top.
        assertEquals(new UUID(0, 1), service.query(CUR, 0, 10).entries().get(0).accountId());

        // The persisted rows changed, but the TTL cache still answers from the old snapshot.
        src.put(CUR, List.of(row(2, "B", 500)));
        assertEquals(new UUID(0, 1), service.query(CUR, 0, 10).entries().get(0).accountId(),
                "precondition: without an event the TTL cache must still serve the old ranking");
        assertEquals(1, src.callCount(), "precondition: the second query was served from cache");

        // A committed transaction event drops the cache, so the next query recomputes.
        publisher.publishPostCommit(committedDeposit());

        assertEquals(new UUID(0, 2), service.query(CUR, 0, 10).entries().get(0).accountId(),
                "after a transaction the ranking must be recomputed, not served stale");
        assertEquals(2, src.callCount(), "the post-event query must hit the source again");

        // Unregistering stops the invalidation, proving the shutdown cleanup is observable.
        publisher.unregisterPostCommit(listener);
        src.put(CUR, List.of(row(3, "C", 900)));
        publisher.publishPostCommit(committedDeposit());
        assertEquals(new UUID(0, 2), service.query(CUR, 0, 10).entries().get(0).accountId(),
                "an unregistered listener must not invalidate the cache");
    }

    @Test
    void emptyLeaderboardStillRecomputesAfterATransaction() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of());
        LeaderboardService service = new LeaderboardService(src,
                new MutableClock(Instant.EPOCH), new LeaderboardCache(), Duration.ofHours(1));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        CompositionRoot.wireLeaderboardInvalidation(publisher, service);

        assertTrue(service.query(CUR, 0, 10).entries().isEmpty());
        assertEquals(1, src.callCount());

        src.put(CUR, List.of(row(1, "A", 100)));
        publisher.publishPostCommit(committedDeposit());

        assertEquals(1, service.query(CUR, 0, 10).entries().size(),
                "a previously empty ranking must refresh after a transaction");
        assertEquals(2, src.callCount());
    }

    @Test
    void preCommitEventsNeverInvalidateTheCache() {
        FakeLeaderboardSource src = new FakeLeaderboardSource();
        src.put(CUR, List.of(row(1, "A", 100)));
        LeaderboardService service = new LeaderboardService(src,
                new MutableClock(Instant.EPOCH), new LeaderboardCache(), Duration.ofHours(1));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        CompositionRoot.wireLeaderboardInvalidation(publisher, service);

        assertEquals(new UUID(0, 1), service.query(CUR, 0, 10).entries().get(0).accountId());

        // Rows changed behind the cache; a pre-commit event (cancelled or not) must not drop it:
        // only a committed transaction may. This keeps cancelled/uncommitted operations and
        // pre-commit observers from ever serving a recompute off uncommitted state.
        src.put(CUR, List.of(row(2, "B", 500)));
        publisher.publishPreCommit(new com.smile.aceeconomy.domain.TransactionEvent(
                UUID.randomUUID(), Amount.of(1, 2), TransactionType.DEPOSIT, Amount.of(0, 2)));

        assertEquals(new UUID(0, 1), service.query(CUR, 0, 10).entries().get(0).accountId(),
                "a pre-commit event must not invalidate the leaderboard cache");
        assertEquals(1, src.callCount(), "the query after a pre-commit event must still be served from cache");
    }
}
