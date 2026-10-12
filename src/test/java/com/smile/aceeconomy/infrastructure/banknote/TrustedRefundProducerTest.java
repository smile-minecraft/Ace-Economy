package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.operations.internal.FailedMintRefund;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.security.SecurityControlService;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.WithdrawResult;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real producers grant the single-use refund through the real ledger:
 * the async banknote flow on a failed mint, and the GUI use case on a failed
 * mint. A compensated deduction restores the exact balance, the exemption
 * never becomes a general deposit bypass, and a hand-built proof with a fresh
 * nonce is still refused.
 */
class TrustedRefundProducerTest {

    private static final class ImmediateFolia implements FoliaContextExecutor {
        private Player player;

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
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private EconomyService economy;
    private EconomyApi api;
    private ExecutorService io;
    private java.util.concurrent.ScheduledExecutorService watchdogs;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        api = new EconomyApiImpl(economy, new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trusted-producer-test-io");
            t.setDaemon(true);
            return t;
        });
        watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trusted-producer-test-watchdog");
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

    private BanknoteFlow flow(InMemoryTrustedRefundLedger ledger) {
        economy.bindRefundLedger(ledger);
        BanknoteFlow flow = new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class),
                banknotes, display, folia, io, watchdogs, Duration.ofMillis(200));
        flow.bindRefundLedger(ledger);
        return flow;
    }

    private static BanknoteFlow.WithdrawOutcome join(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("producer withdraw must complete bounded", e);
        }
    }

    @Test
    @DisplayName("flow failed mint compensates through the real ledger with the balance restored")
    void flowFailedMintCompensatesThroughRealLedger() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        economy.createAccount(playerId, "Producer");
        economy.deposit(playerId, "dollar", Amount.of(500, 2));
        Amount before = economy.getBalance(playerId, "dollar").value();
        banknotes.failNextMint = true;

        BanknoteFlow.WithdrawOutcome outcome =
                join(flow(new InMemoryTrustedRefundLedger())
                        .withdraw(playerId, "dollar", Amount.of(100, 2)));

        assertTrue(outcome.isRefunded(), "a compensated mint failure must read as refunded");
        assertEquals(BanknoteFlow.REASON_MINT_FAILED, outcome.reason());
        assertTrue(outcome.refundOk(), "the trusted compensation must succeed");
        assertEquals(0, before.compareTo(economy.getBalance(playerId, "dollar").value()),
                "the compensated deduction must restore the exact balance");
        verify(inventory, never()).addItem(any());
    }

    @Test
    @DisplayName("flow exemption never becomes a general deposit bypass")
    void flowExemptionIsNotAGeneralBypass() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        economy.createAccount(playerId, "Producer");
        economy.deposit(playerId, "dollar", Amount.of(500, 2));
        Amount before = economy.getBalance(playerId, "dollar").value();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindRefundLedger(ledger);
        economy.bindSecurityControl(new SecurityControlService());

        FailedMintRefund forged = FailedMintRefund.proven(
                playerId, "dollar", Amount.of(100, 2), UUID.randomUUID());
        EconomyResult<Amount> r = economy.refundFailedMint(forged);

        assertTrue(r.isFailure(), "a hand-built proof with a fresh nonce must be refused");
        assertEquals(0, before.compareTo(economy.getBalance(playerId, "dollar").value()));
    }
}
