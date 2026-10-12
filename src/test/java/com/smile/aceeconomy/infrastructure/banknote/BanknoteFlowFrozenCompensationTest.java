package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.security.SecurityControlService;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An uncertain banknote handover (the region callback threw, so the note may
 * or may not have landed) must still void the nonce and refund atomically
 * when the account was frozen or the server turned read-only after the
 * deduction committed. The money must not be left stranded.
 */
class BanknoteFlowFrozenCompensationTest {

    /** Inline region executor that can hold the delivery dispatch for later. */
    static final class HoldingFolia implements FoliaContextExecutor {
        Player player;
        Integer captureDispatchAt;
        final List<UUID> dispatched = new ArrayList<>();
        final List<Consumer<Player>> captured = new ArrayList<>();

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
            dispatched.add(playerId);
            if (player == null) {
                return false;
            }
            if (captureDispatchAt != null && dispatched.size() == captureDispatchAt) {
                captured.add(action);
                return true;
            }
            action.accept(player);
            return true;
        }
    }

    /** Watchdog scheduler that only arms: nothing fires by timeout in these tests. */
    static final class CapturingWatchdogs extends ScheduledThreadPoolExecutor {
        final List<Runnable> armed = new ArrayList<>();

        CapturingWatchdogs() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            armed.add(command);
            return null;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final HoldingFolia folia = new HoldingFolia();
    private final CapturingWatchdogs watchdogs = new CapturingWatchdogs();
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    @AfterEach
    void tearDown() {
        watchdogs.shutdownNow();
    }

    private Player throwingPlayer(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        Mockito.doThrow(new RuntimeException("slot outage after unknown placement"))
                .when(inventory).addItem(any());
        return player;
    }

    private static BanknoteFlow.WithdrawOutcome join(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("uncertain withdraw must complete bounded", e);
        }
    }

    @Test
    @DisplayName("uncertain handover voids and refunds despite a freeze after the deduction")
    void uncertainHandoverCompensatesUnderFreeze(@TempDir java.nio.file.Path dir) throws Exception {
        UUID playerId = UUID.randomUUID();
        folia.player = throwingPlayer(playerId);
        folia.captureDispatchAt = 2; // probe runs now; the delivery stays held

        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("frozen-uncertain.json"));
        backend.initialize();
        EconomyService economy = new EconomyService(registry,
                com.smile.aceeconomy.domain.DebtPolicy.disabled(), Amount.zero(2), backend, tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, Instant::now, new InMemoryTransactionEventPublisher());
        economy.createAccount(playerId, "Holder");
        economy.deposit(playerId, "dollar", Amount.of(1000L, 2));

        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);
        BanknoteFlow flow = new BanknoteFlow(
                new EconomyApiImpl(economy, new InMemoryTransactionEventPublisher()),
                economy, backend, banknotes, display, folia, Runnable::run,
                watchdogs, Duration.ofMillis(200));
        flow.bindRefundLedger(ledger);

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", Amount.of(100L, 2));
        assertFalse(future.isDone(), "the held delivery must park the outcome");
        assertEquals(1, folia.captured.size(), "the delivery dispatch must be held");
        assertEquals(0, Amount.of(900L, 2).compareTo(
                backend.load(playerId).orElseThrow().balanceOf("dollar")),
                "the deduction must have committed before the freeze");

        control.freeze(playerId, AdminAudit.console("incident"));

        // The held handover finally runs and throws at an unknown placement.
        folia.captured.get(0).accept(folia.player);
        BanknoteFlow.WithdrawOutcome outcome = join(future);

        assertTrue(outcome.isVoidRefunded(),
                "an uncertain handover must void the note and refund together despite the freeze: "
                        + outcome);
        assertEquals(0, Amount.of(1000L, 2).compareTo(
                backend.load(playerId).orElseThrow().balanceOf("dollar")),
                "the frozen account must be whole again, nothing stranded");
        assertTrue(backend.isConsumed(outcome.claim().nonce()),
                "the uncertain note must be dead: no redeemable note next to the refund");
    }

    @Test
    @DisplayName("uncertain handover voids and refunds while the server is read-only")
    void uncertainHandoverCompensatesUnderReadonly(@TempDir java.nio.file.Path dir) throws Exception {
        UUID playerId = UUID.randomUUID();
        folia.player = throwingPlayer(playerId);
        folia.captureDispatchAt = 2; // probe runs now; the delivery stays held

        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("readonly-uncertain.json"));
        backend.initialize();
        EconomyService economy = new EconomyService(registry,
                com.smile.aceeconomy.domain.DebtPolicy.disabled(), Amount.zero(2), backend, tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, Instant::now, new InMemoryTransactionEventPublisher());
        economy.createAccount(playerId, "Holder");
        economy.deposit(playerId, "dollar", Amount.of(1000L, 2));

        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);
        BanknoteFlow flow = new BanknoteFlow(
                new EconomyApiImpl(economy, new InMemoryTransactionEventPublisher()),
                economy, backend, banknotes, display, folia, Runnable::run,
                watchdogs, Duration.ofMillis(200));
        flow.bindRefundLedger(ledger);

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", Amount.of(100L, 2));
        assertFalse(future.isDone(), "the held delivery must park the outcome");

        control.setGlobalReadonly(true, AdminAudit.console("emergency"));

        folia.captured.get(0).accept(folia.player);
        BanknoteFlow.WithdrawOutcome outcome = join(future);

        assertTrue(outcome.isVoidRefunded(),
                "an uncertain handover must void the note and refund together while read-only: "
                        + outcome);
        assertEquals(0, Amount.of(1000L, 2).compareTo(
                backend.load(playerId).orElseThrow().balanceOf("dollar")),
                "the account must be whole again, nothing stranded");
        assertTrue(backend.isConsumed(outcome.claim().nonce()),
                "the uncertain note must be dead: no redeemable note next to the refund");
    }
}
