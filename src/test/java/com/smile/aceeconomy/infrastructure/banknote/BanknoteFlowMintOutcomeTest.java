package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A mint failure must distinguish a compensated deduction from an
 * undecidable one: refund success closes as refunded with the mint-failure
 * reason, while a failed or uncertain refund stays pending with the nonce
 * for manual reconciliation — never a plain refusal that hides lost money.
 */
class BanknoteFlowMintOutcomeTest {

    static final class ImmediateFolia implements com.smile.aceeconomy.ports.FoliaContextExecutor {
        Player player;

        @Override
        public void runForPlayer(@NotNull Player target, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        }

        @Override
        public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
        }

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            if (player == null) {
                return false;
            }
            action.accept(player);
            return true;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final ImmediateFolia folia = new ImmediateFolia();
    private final EconomyApi api = mock(EconomyApi.class);
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private EconomyService economy;
    private ExecutorService io;
    private java.util.concurrent.ScheduledExecutorService watchdogs;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mint-outcome-test-io");
            t.setDaemon(true);
            return t;
        });
        watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mint-outcome-test-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
        watchdogs.shutdownNow();
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        return player;
    }

    private BanknoteFlow flow() {
        return new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class), banknotes,
                display, folia, io, watchdogs, Duration.ofMillis(200));
    }

    private static BanknoteFlow.WithdrawOutcome join(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("mint outcome must complete bounded", e);
        }
    }

    @Test
    void mintFailureWithSuccessfulRefundIsRefundedWithTheMintReason() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        banknotes.failNextMint = true;

        BanknoteFlow.WithdrawOutcome outcome = join(flow().withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefunded(), "a compensated mint failure must read as refunded: " + outcome);
        assertEquals(BanknoteFlow.REASON_MINT_FAILED, outcome.reason());
        assertTrue(outcome.refundOk());
        verify(inventory, never()).addItem(any());
    }

    @Test
    void mintFailureWithFailedRefundIsPendingWithTheNonce() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(
                EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing"));
        banknotes.failNextMint = true;

        BanknoteFlow.WithdrawOutcome outcome = join(flow().withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isPending(),
                "an uncompensated mint failure must stay pending, never a plain refusal: " + outcome);
        assertNotNull(outcome.claim(), "the pending case must carry the claim for diagnosis");
        assertTrue(banknotes.minted().isEmpty(), "the note never materialised");
        verify(inventory, never()).addItem(any());
    }

    @Test
    void mintFailureWithUncertainRefundIsPendingWithTheCause() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        IndeterminatePersistenceException indeterminate = new IndeterminatePersistenceException(
                "refund response lost", new RuntimeException("conn reset"));
        when(api.deposit(playerId, "dollar", amount)).thenThrow(indeterminate);
        banknotes.failNextMint = true;

        BanknoteFlow.WithdrawOutcome outcome = join(flow().withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isPending(), "an uncertain mint refund must stay pending: " + outcome);
        assertEquals(indeterminate, outcome.pendingCause());
        assertNotNull(outcome.claim());
    }
}
