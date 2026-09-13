package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.commands.v2.CommandModels;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the live {@code /withdraw cash} failure: the adapter minted the banknote and
 * threw the ItemStack away, so the player was charged without receiving anything. These tests lock
 * the port contract documented on {@link com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService}:
 * the port implementation hands out the physical item, on the player's Folia region thread, and a
 * full inventory is refused before any deduction (GUI parity), never leaving a charged account
 * without a note.
 */
class WithdrawCommandDeliveryTest {

    /** Fake region executor: resolves a programmed player by UUID and runs actions synchronously. */
    private static final class FakeRegionExecutor implements FoliaContextExecutor {
        Player player;
        /** Set to the 1-based dispatch number that must be refused (probe is 1, delivery is 2). */
        Integer refuseDispatchAt;
        /**
         * Set to the 1-based dispatch number whose action must be accepted but never run
         * (Folia retired the task); the action is stored in {@link #captured} instead.
         */
        Integer captureDispatchAt;
        final List<UUID> dispatched = new ArrayList<>();
        final List<Consumer<Player>> captured = new ArrayList<>();

        private boolean dispatch() {
            if (refuseDispatchAt != null && dispatched.size() == refuseDispatchAt) {
                return false;
            }
            return player != null;
        }

        @Override
        public void runForPlayer(@NotNull Player target, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
            action.run();
        }

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            dispatched.add(playerId);
            if (!dispatch()) {
                return false;
            }
            if (captureDispatchAt != null && dispatched.size() == captureDispatchAt) {
                // Scheduler accepted the task, but Folia retired it: the callback never runs.
                captured.add(action);
                return true;
            }
            action.accept(player);
            return true;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final FakeRegionExecutor folia = new FakeRegionExecutor();
    private final EconomyApi api = mock(EconomyApi.class);
    /** Deterministic bounded-wait scheduler: arms are captured here, never auto-fired. */
    private final WatchdogCaptureScheduler timeouts = new WatchdogCaptureScheduler();

    private static final class WatchdogCaptureScheduler extends ScheduledThreadPoolExecutor {
        final List<Runnable> armed = new ArrayList<>();

        private WatchdogCaptureScheduler() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            armed.add(command);
            // The withdraw adapter ignores the handle; nothing armed here ever fires.
            return null;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            // Tripwire: a value-compatible watchdog lambda would silently bind to this
            // overload and never be captured by the Runnable seam above.
            armed.add(() -> {
                try {
                    callable.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return null;
        }
    }

    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private PlayerInventory inventory;
    private ItemStack delivered;

    private ProductionAdapters.Withdrawals withdrawals() {
        return new ProductionAdapters.Withdrawals(api, display, banknotes, Runnable::run, folia,
                timeouts, Duration.ofMillis(200));
    }

    /** Real single-thread watchdog scheduler for end-to-end bounded-wait tests (no manual firing). */
    private static ScheduledExecutorService realWatchdogs() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "withdraw-dispatch-watchdog-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private Player onlinePlayer(UUID playerId) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(playerId);
        when(p.isOnline()).thenReturn(true);
        inventory = mock(PlayerInventory.class);
        when(p.getInventory()).thenReturn(inventory);
        org.mockito.Mockito.doAnswer(invocation -> {
            delivered = invocation.getArgument(0);
            // addItem declares HashMap as its return type; the mock casts the answer, so return exactly that.
            return new java.util.HashMap<Integer, ItemStack>();
        }).when(inventory).addItem(any(ItemStack.class));
        return p;
    }

    private void withdrawSucceeds(UUID playerId, Amount amount) {
        when(api.withdraw(playerId, "dollar", amount))
                .thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount))
                .thenReturn(EconomyResult.success(amount));
    }

    // ---------------- happy path ----------------

    @Test
    void successDeliversOneDecodableBanknoteMatchingTheDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);

        CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                withdrawals().withdraw(playerId, "dollar", amount);
        EconomyResult<CommandModels.WithdrawReceipt> result = future.join();

        assertTrue(result.isSuccess(), "withdraw must succeed: " + result);
        CommandModels.WithdrawReceipt receipt = result.value();
        assertEquals("dollar", receipt.currencyId());
        assertEquals(0, amount.value().compareTo(receipt.value()));

        // exactly one item handed over on the region seam
        verify(inventory).addItem(any(ItemStack.class));
        assertNotNull(delivered, "the minted note must be handed to the player, not discarded");

        // the delivered item decodes back to the claim that was paid for
        Optional<BanknoteClaim> decoded = banknotes.decode(delivered);
        assertTrue(decoded.isPresent(), "delivered item must decode as a v2 banknote");
        BanknoteClaim claim = decoded.get();
        assertEquals(100L, claim.value());
        assertEquals("dollar", claim.currency());
        assertEquals(playerId, claim.issuer());
        assertEquals(receipt.noteId(), claim.nonce(), "receipt and item must carry the same nonce");
    }

    // ---------------- full inventory ----------------

    @Test
    void fullInventoryRefusedBeforeAnyDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(-1);
        Amount amount = Amount.of(100L, 2);

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "a full inventory must refuse the withdraw");
        assertEquals(EconomyError.INVENTORY_FULL, result.error());
        verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
        assertTrue(banknotes.minted().isEmpty(), "no note may be minted when refusing");
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void inventoryFilledDuringDeliveryRefundsAndFailsClosed() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        // space exists at the pre-check, but fills before the delivery dispatch runs
        when(inventory.firstEmpty()).thenReturn(0, -1);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "a delivery that cannot place the note must fail");
        assertEquals(EconomyError.INVENTORY_FULL, result.error());
        verify(inventory, never()).addItem(any(ItemStack.class));
        // the deduction is compensated: the account is made whole through a refund deposit
        verify(api).deposit(playerId, "dollar", amount);
    }

    // ---------------- player disappears ----------------

    @Test
    void playerGoneBeforeStartFailsWithoutDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = null; // nothing resolvable
        Amount amount = Amount.of(100L, 2);

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess());
        verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void playerGoneAfterMintRefundsAndFailsClosed() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        folia.refuseDispatchAt = 2; // probe (1) succeeds; the delivery dispatch (2) finds nobody

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "an undeliverable note must fail closed");
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api).deposit(playerId, "dollar", amount);
    }

    // ---------------- withdraw failure ----------------

    @Test
    void withdrawFailureDeliversAndRefundsNothing() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount))
                .thenReturn(EconomyResult.failure(EconomyError.INSUFFICIENT_FUNDS, "not enough"));

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess());
        assertEquals(EconomyError.INSUFFICIENT_FUNDS, result.error());
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api, never()).deposit(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void mintFailureAfterDeductionRefundsAndFailsClosed() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        banknotes.failNextMint = true;

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "a mint that cannot materialise the note must fail closed");
        verify(inventory, never()).addItem(any(ItemStack.class));
        // the committed deduction is compensated so no charge is left without a note
        verify(api).deposit(playerId, "dollar", amount);
    }

    // ---------------- mint throws after the deduction (unchecked item failure) ----------------

    @Test
    void mintThrowingRuntimeExceptionAfterDeductionRefundsOnceAndFailsTyped() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        banknotes.throwNextMint = new RuntimeException("simulated item factory outage");

        // The reply must be a typed failure, never an exceptional future: the deduction
        // already committed, so the caller must observe a closed outcome either way.
        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "a throwing mint must fail the withdraw closed");
        assertEquals(EconomyError.INVALID_AMOUNT, result.error());
        verify(inventory, never()).addItem(any(ItemStack.class));
        // the committed deduction is compensated exactly once with the requested amount
        verify(api, times(1)).deposit(playerId, "dollar", amount);
        assertTrue(banknotes.minted().isEmpty(), "no note exists, so nothing may be delivered");
    }

    @Test
    void mintThrowingAceLibItemExceptionAlsoRefundsAndFailsTyped() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        banknotes.throwNextMint = new com.smile.acelib.item.ItemException(
                "MINT_FAILED", "simulated AceLib identity/PDC outage");

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "an AceLib item failure must fail the withdraw closed");
        assertEquals(EconomyError.INVALID_AMOUNT, result.error());
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api, times(1)).deposit(playerId, "dollar", amount);
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void mintThrowingWithFailedRefundStillFailsTypedExactlyOnceWithoutRetry() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount))
                .thenReturn(EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing"));
        banknotes.throwNextMint = new RuntimeException("simulated item factory outage");

        EconomyResult<CommandModels.WithdrawReceipt> result =
                withdrawals().withdraw(playerId, "dollar", amount).join();

        assertFalse(result.isSuccess(), "a failed refund must still fail the withdraw closed");
        assertEquals(EconomyError.INVALID_AMOUNT, result.error());
        // the compensation is attempted exactly once: never retried, never double-refunded
        verify(api, times(1)).deposit(playerId, "dollar", amount);
    }

    @Test
    void programmingErrorThroughMintIsNotSwallowedIntoTypedFailure() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        // A VM-level Error is not a banknote-mint failure shape: it must propagate as an
        // exceptional future, never be remapped into a typed business failure.
        banknotes.throwNextMint = new OutOfMemoryError("simulated broken runtime");

        java.util.concurrent.CompletionException propagated =
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> withdrawals().withdraw(playerId, "dollar", amount).join());

        assertInstanceOf(OutOfMemoryError.class, propagated.getCause());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    // ---------------- accepted but never runs (Folia retired the region task) ----------------

    @Test
    void acceptedButNeverRunsProbeCompletesBoundedWithoutDeduction() throws Exception {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        folia.captureDispatchAt = 1; // probe accepted, then the player leaves: callback never runs
        Amount amount = Amount.of(100L, 2);

        ScheduledExecutorService watchdogs = realWatchdogs();
        try {
            ProductionAdapters.Withdrawals withdrawals =
                    new ProductionAdapters.Withdrawals(api, display, banknotes, Runnable::run, folia,
                            watchdogs, Duration.ofMillis(150));
            CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                    withdrawals.withdraw(playerId, "dollar", amount);

            // The bounded wait must complete the reply; before the fix this hangs forever.
            EconomyResult<CommandModels.WithdrawReceipt> result = future.get(5, TimeUnit.SECONDS);

            assertFalse(result.isSuccess(), "a probe that never ran must refuse the withdraw");
            assertEquals(EconomyError.TRANSACTION_CANCELLED, result.error());
            verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
            assertTrue(banknotes.minted().isEmpty(), "nothing may be minted when the probe never ran");
        } finally {
            watchdogs.shutdownNow();
        }
    }

    @Test
    void acceptedButNeverRunsDeliveryRefundsBoundedAndFailsClosed() throws Exception {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        folia.captureDispatchAt = 2; // probe runs; the delivery dispatch is accepted and retired

        ScheduledExecutorService watchdogs = realWatchdogs();
        try {
            ProductionAdapters.Withdrawals withdrawals =
                    new ProductionAdapters.Withdrawals(api, display, banknotes, Runnable::run, folia,
                            watchdogs, Duration.ofMillis(150));
            CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                    withdrawals.withdraw(playerId, "dollar", amount);

            // The deduction already committed; the bounded wait must refund and fail the reply.
            EconomyResult<CommandModels.WithdrawReceipt> result = future.get(5, TimeUnit.SECONDS);

            assertFalse(result.isSuccess(), "a delivery that never ran must fail closed");
            assertEquals(EconomyError.TRANSACTION_CANCELLED, result.error());
            verify(inventory, never()).addItem(any(ItemStack.class));
            verify(api, times(1)).deposit(playerId, "dollar", amount);
        } finally {
            watchdogs.shutdownNow();
        }
    }

    @Test
    void probeWatchdogRefusesWithoutDeductionAndIgnoresLateCallback() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        folia.captureDispatchAt = 1;
        Amount amount = Amount.of(100L, 2);

        CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                withdrawals().withdraw(playerId, "dollar", amount);
        assertFalse(future.isDone(), "an accepted-but-retired probe must not complete on its own");
        assertEquals(1, timeouts.armed.size(), "the bounded wait must be armed when the probe dispatch is accepted");

        timeouts.armed.get(0).run();
        EconomyResult<CommandModels.WithdrawReceipt> result = future.join();

        assertFalse(result.isSuccess(), "the bounded wait must refuse the withdraw");
        assertEquals(EconomyError.TRANSACTION_CANCELLED, result.error());
        verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
        assertTrue(banknotes.minted().isEmpty());

        // The retired task finally runs late: it must not resurrect the refused withdraw.
        folia.captured.get(0).accept(folia.player);
        assertEquals(result, future.join(), "the refused reply must be unchanged");
        verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void deliveryWatchdogRefundsOnceAndFailsClosed() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        folia.captureDispatchAt = 2;

        CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                withdrawals().withdraw(playerId, "dollar", amount);
        assertFalse(future.isDone(), "an accepted-but-retired delivery must not complete on its own");
        verify(api).withdraw(playerId, "dollar", amount); // the deduction already committed
        assertEquals(2, timeouts.armed.size(), "probe and delivery each arm one bounded wait");

        timeouts.armed.get(1).run();
        EconomyResult<CommandModels.WithdrawReceipt> result = future.join();

        assertFalse(result.isSuccess(), "the bounded wait must fail the delivery closed");
        assertEquals(EconomyError.TRANSACTION_CANCELLED, result.error());
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api, times(1)).deposit(playerId, "dollar", amount);
    }

    @Test
    void lateDeliveryCallbackAfterTimeoutDeliversNothingAndDoesNotDoubleRefund() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);
        folia.captureDispatchAt = 2;

        CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                withdrawals().withdraw(playerId, "dollar", amount);
        timeouts.armed.get(1).run(); // the bounded wait fires first: refund + typed failure
        EconomyResult<CommandModels.WithdrawReceipt> settled = future.join();
        assertFalse(settled.isSuccess());

        // The retired delivery task finally runs after the refund: the per-operation fence
        // must keep it from placing the note or compensating a second time.
        folia.captured.get(0).accept(folia.player);

        assertEquals(settled, future.join(), "the failed reply must be unchanged");
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api, times(1)).deposit(playerId, "dollar", amount);
    }

    @Test
    void watchdogAfterSuccessfulDeliveryDoesNotRefund() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        withdrawSucceeds(playerId, amount);

        CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> future =
                withdrawals().withdraw(playerId, "dollar", amount);
        EconomyResult<CommandModels.WithdrawReceipt> result = future.join();
        assertTrue(result.isSuccess(), "precondition: the normal delivery succeeds");
        verify(inventory).addItem(any(ItemStack.class));

        // Late watchdogs must not undo a delivery the region callback already settled.
        timeouts.armed.forEach(Runnable::run);
        assertTrue(future.join() == result, "the successful reply must be unchanged");
        verify(api, never()).deposit(Mockito.any(), Mockito.any(), Mockito.any());
    }

    // ---------------- divergent display registry / unissuable amount ----------------

    @Test
    void unknownCurrencyRefusedBeforeAnyDeduction() {
        // Divergent-state guard: even if the economy api somehow accepts a currency the
        // display registry does not contain, the withdraw must refuse with a typed failure
        // BEFORE the deduction commits — never charge, then throw, with no refund.
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "token", amount)).thenReturn(EconomyResult.success(amount));

        EconomyResult<CommandModels.WithdrawReceipt> result = assertDoesNotThrow(
                () -> withdrawals().withdraw(playerId, "token", amount).join());

        assertFalse(result.isSuccess(), "a currency absent from the display registry must refuse");
        assertEquals(EconomyError.CURRENCY_NOT_FOUND, result.error());
        verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
        verify(api, never()).deposit(Mockito.any(), Mockito.any(), Mockito.any());
        assertTrue(banknotes.minted().isEmpty(), "nothing may be minted when refusing");
    }

    @Test
    void unissuableAmountRefusedBeforeAnyDeduction() {
        // An amount that cannot back a whole-number banknote claim must be refused before
        // the deduction; charging first and failing the claim construction would leave a
        // deducted account with neither a note nor a refund.
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0);
        Amount amount = Amount.of(new java.math.BigDecimal("100.50"), 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));

        EconomyResult<CommandModels.WithdrawReceipt> result = assertDoesNotThrow(
                () -> withdrawals().withdraw(playerId, "dollar", amount).join());

        assertFalse(result.isSuccess(), "a non-integral banknote amount must refuse");
        assertEquals(EconomyError.INVALID_AMOUNT, result.error());
        verify(api, never()).withdraw(Mockito.any(), Mockito.any(), Mockito.any());
        verify(api, never()).deposit(Mockito.any(), Mockito.any(), Mockito.any());
        assertTrue(banknotes.minted().isEmpty(), "nothing may be minted when refusing");
    }

    // ---------------- helpers ----------------
}
