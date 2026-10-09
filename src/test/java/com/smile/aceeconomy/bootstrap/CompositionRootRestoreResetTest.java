package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A successful restore replaces the world underneath the caches, so the post-restore reset
 * must drop both the shared leaderboard rankings and every cached balance; otherwise
 * placeholders and Vault keep serving pre-restore values.
 */
class CompositionRootRestoreResetTest {

    @Test
    void restoreResetDropsLeaderboardAndBalances() {
        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("coin", "Coin", "C", 2, true)));
        AtomicInteger sourceHits = new AtomicInteger();
        UUID owner = UUID.randomUUID();
        LeaderboardService leaderboard = new LeaderboardService(
                currencyId -> {
                    sourceHits.incrementAndGet();
                    return List.of(new LeaderboardRow(owner, "owner", Amount.of(100_00L, 2)));
                },
                () -> Instant.EPOCH,
                new LeaderboardCache(),
                Duration.ofMinutes(5));
        EconomyService economy = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), new InMemoryAccountRepository(), new RecordingAuditSink(),
                new FixedClock(), new InMemoryTransactionEventPublisher());

        UUID uuid = UUID.randomUUID();
        assertTrue(economy.createAccount(uuid, "owner").isSuccess());
        assertTrue(economy.deposit(uuid, "coin", Amount.of(100_00L, 2)).isSuccess());
        assertEquals(1, leaderboard.query("coin", 0, 10).totalEntries());
        assertEquals(1, sourceHits.get());
        assertEquals(1, leaderboard.query("coin", 0, 10).totalEntries());
        assertEquals(1, sourceHits.get(), "second query must be served from the snapshot");

        CompositionRoot.restoreCacheReset(leaderboard, economy).run();

        assertTrue(economy.cachedBalance(uuid, "coin").isEmpty(),
                "post-restore balances must miss instead of serving the replaced world");
        assertEquals(1, leaderboard.query("coin", 0, 10).totalEntries());
        assertEquals(2, sourceHits.get(), "post-restore rankings must be recomputed");
    }

    @Test
    void restoreResetIsNullSafe() {
        CompositionRoot.restoreCacheReset(null, null).run();
    }
}
