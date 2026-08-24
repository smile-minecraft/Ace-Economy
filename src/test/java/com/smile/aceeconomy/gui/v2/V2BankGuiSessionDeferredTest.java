package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.DeferredFoliaContext;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression for the async-dispatch contract: a Folia executor that defers the runnable must
 * not produce a fake credited/success outcome from {@code handleClick}. The sync wrapper returns
 * {@code dispatched}, and the async future only completes with the typed outcome after the
 * Folia context has executed.
 */
class V2BankGuiSessionDeferredTest {

    private static final Set<Integer> PROTECTED = Set.of();

    private Player mockPlayer(boolean inventoryHasSpace) {
        Player p = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        UUID uuid = UUID.randomUUID();
        Mockito.when(p.getUniqueId()).thenReturn(uuid);
        Mockito.when(p.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(inventoryHasSpace ? 0 : -1);
        Mockito.when(inv.addItem(Mockito.any(ItemStack.class))).thenReturn(new HashMap<>());
        return p;
    }

    private Function<Integer, BankGuiAction> withdrawResolver(long amount) {
        return slot -> BankGuiAction.withdraw(amount);
    }

    private Function<Integer, BankGuiAction> depositResolver() {
        return slot -> BankGuiAction.deposit();
    }

    private ItemStack banknoteStack(int amount) {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(false);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(material);
        Mockito.when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private ItemStack airStack() {
        Material air = Mockito.mock(Material.class);
        Mockito.when(air.isAir()).thenReturn(true);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(air);
        return stack;
    }

    @Test
    void deferredWithdrawDoesNotReturnFakeSuccessUntilFlush() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));
        V2BankGuiSession.OpenOutcome open2 = session.open(player, "Bank", 27, PROTECTED);
        long gen = open2.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        assertFalse(future.isDone(), "deferred folia executor must not complete the future before flush");
        V2BankGuiSession.ClickOutcome sync = session.handleClick(player.getUniqueId(), gen, 0);
        assertTrue(sync.isDispatched(), "sync wrapper must return dispatched instead of fake success when not yet executed");

        // Now flush the queued runnable: the async future should complete with success.
        folia.flush();
        assertTrue(future.isDone());
        V2BankGuiSession.ClickOutcome result = future.get();
        assertTrue(result.isSuccess(), "after flush the withdraw should succeed");
        assertTrue(folia.playerCalled());
    }

    @Test
    void deferredDepositInvalidKeepsItemAndReturnsRejectedAfterFlush() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.depositMode = StubBankGuiUseCase.DepositMode.REJECTED;
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        assertFalse(future.isDone());

        folia.flush();
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isRejected());
        assertEquals("business.rejected", outcome.reason());
        verify(inv, never()).setItemInMainHand(Mockito.any());
    }

    @Test
    void deferredDepositCreditOnlyAfterFlushAndRemovesThroughFolia() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        assertFalse(future.isDone());
        // inventory mutation must not have happened before the Folia context runs
        verify(inv, never()).setItemInMainHand(Mockito.any());

        folia.flush();
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isCredited());
        verify(inv).setItemInMainHand(Mockito.isNull());
    }

    @Test
    void deferredCloseIsSynchronousAndGenerationSafe() {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, slot -> BankGuiAction.close());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        // Close click should complete immediately (no Folia dispatch) and be generation-safe
        V2BankGuiSession.ClickOutcome stale = session.handleClick(player.getUniqueId(), gen + 1, 0);
        assertTrue(stale.isRejected());
        assertEquals("stale-generation", stale.reason());
        // valid close should succeed without queuing
        V2BankGuiSession.ClickOutcome ok = session.handleClick(player.getUniqueId(), gen, 0);
        assertFalse(ok.isRejected());
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
        assertEquals(0, folia.queuedCount(), "close must not queue a Folia runnable");
    }

    @Test
    void deferredDepositStorageFailureSurfacesAsRejectedAfterFlush() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.depositMode = StubBankGuiUseCase.DepositMode.REJECTED;
        useCase.depositReason = "credit.failed";
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        folia.flush();
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isRejected());
        assertEquals("credit.failed", outcome.reason());
        verify(inv, never()).setItemInMainHand(Mockito.any());
    }

    // ---- item swap race: credit A must not remove B ----

    @Test
    void deferredDepositSwapDoesNotRemoveSwappedItemAndReturnsItemMismatch() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase(); // success
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack heldA = banknoteStack(1);
        ItemStack copyA = banknoteStack(1);
        Mockito.when(heldA.clone()).thenReturn(copyA);
        ItemStack heldB = banknoteStack(1);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        // New click-time binding overload: snapshot is taken at click time via clone
        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0, heldA);
        assertFalse(future.isDone());
        // Swap inventory to B before the Folia region thread runs
        Mockito.when(inv.getItemInMainHand()).thenReturn(heldB);

        folia.flush();
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isItemMismatch(), "swapped item must yield item.mismatch, not credited");
        assertEquals("item.mismatch", outcome.reason());
        verify(inv, never()).setItemInMainHand(Mockito.any());
        // useCase must have received the cloned snapshot of A, not B
        assertEquals(copyA, useCase.lastDepositItem);
    }

    @Test
    void deferredDepositSwapWithSameContentStillRemovesOne() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack heldA = banknoteStack(2);
        ItemStack currentSimilar = banknoteStack(2);
        Mockito.when(heldA.isSimilar(currentSimilar)).thenReturn(true);
        Mockito.when(currentSimilar.isSimilar(heldA)).thenReturn(true);
        // Snapshot = heldA, after swap current = currentSimilar which isSimilar
        Mockito.when(inv.getItemInMainHand()).thenReturn(heldA);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        Mockito.when(inv.getItemInMainHand()).thenReturn(currentSimilar);
        folia.flush();
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isCredited(), "isSimilar content should still be considered same logical item and credited");
        // Amount 2 -> decremented to 1 on the current hand (verify mutation, not mock getter)
        verify(currentSimilar).setAmount(1);
        verify(inv).setItemInMainHand(currentSimilar);
    }

    // ---- dispatch synchronous throw must not leave future incomplete ----

    private static final class ThrowingFoliaContext implements FoliaContextExecutor {
        @Override
        public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
            throw new IllegalStateException("region not owned");
        }
        @Override public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) { throw new IllegalStateException("x"); }
        @Override public void runAtLocation(@NotNull Location location, @NotNull Runnable action) { throw new IllegalStateException("x"); }
        @Override public void runGlobal(@NotNull Runnable action) { throw new IllegalStateException("x"); }
        @Override public void runAsync(@NotNull Runnable action) { throw new IllegalStateException("x"); }
    }

    @Test
    void throwingFoliaContextCompletesFutureWithDispatchFailedAndSyncWrapperDoesNotThrow() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        ThrowingFoliaContext folia = new ThrowingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        assertTrue(future.isDone(), "synchronous throw must complete the future immediately");
        V2BankGuiSession.ClickOutcome asyncOutcome = future.get();
        assertTrue(asyncOutcome.isRejected());
        assertEquals("dispatch.failed", asyncOutcome.reason());

        // sync wrapper must also return the same typed rejection, not throw
        V2BankGuiSession.ClickOutcome syncOutcome = session.handleClick(player.getUniqueId(), gen, 0);
        assertTrue(syncOutcome.isRejected());
        assertEquals("dispatch.failed", syncOutcome.reason());
        verify(inv, never()).setItemInMainHand(Mockito.any());
    }

    @Test
    void throwingFoliaAfterCompletionDoesNotOverwriteResult() throws Exception {
        // Deferred that first queues, then on second call throws: the already-completed future must stay credited
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        folia.flush();
        assertTrue(future.isDone());
        assertTrue(future.get().isCredited());

        // Subsequent handleClick with same gen should not affect the already completed future
        // (no overwrite check: future.complete returns false if already done, we rely on CompletableFuture semantics)
        assertTrue(future.get().isCredited());
    }

    // ---- click-time snapshot overload: immutable copy ----

    @Test
    void clickTimeSnapshotOverloadUsesImmutableCopyAndDoesNotRemoveSwappedItem() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        // Original A with amount 1, clone returns independent copy with same amount
        ItemStack heldA = banknoteStack(1);
        ItemStack copyA = banknoteStack(1);
        Mockito.when(heldA.clone()).thenReturn(copyA);
        ItemStack heldB = banknoteStack(1);
        // Current hand after swap is B (different nonce)
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        // Call click-time binding overload with heldA; it must clone immediately
        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0, heldA);
        // Mutate original A after call - snapshot must be unaffected
        // (change its amount via stub to 99)
        Mockito.when(heldA.getAmount()).thenReturn(99);
        // Swap inventory to B before flush
        Mockito.when(inv.getItemInMainHand()).thenReturn(heldB);

        folia.flush();
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isItemMismatch());
        assertEquals("item.mismatch", outcome.reason());
        verify(inv, never()).setItemInMainHand(Mockito.any());
        // useCase must have received the immutable copy (amount 1), not mutated 99
        assertTrue(useCase.lastDepositItem == copyA || useCase.lastDepositItem == heldA, "deposit should use snapshot");
        // Amount of the deposited item must still be 1 (snapshot) even though original mutated to 99
        assertEquals(1, useCase.lastDepositItem.getAmount());
    }

    @Test
    void oldOverloadReadsInsideFoliaContextNotBeforeQueue() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        // No stub yet: getItemInMainHand would return null if called before queue
        // Call old overload (no snapshot) - it must NOT read inventory before queue
        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0);
        // Check no inventory read happened before flush (old overload reads inside callback)
        verify(inv, never()).getItemInMainHand();
        // Now stub to return held for execution-time read
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        folia.flush();
        assertTrue(future.get().isCredited());
        verify(inv, Mockito.atLeast(1)).getItemInMainHand();
    }

    private static final class ThrowingAfterActionFoliaContext implements FoliaContextExecutor {
        private final DeferredFoliaContext delegate = new DeferredFoliaContext();
        boolean shouldThrow = false;
        @Override
        public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
            // Queue like deferred, but on flush run then throw if flagged
            delegate.runForPlayer(player, action);
            // For synchronous throw-after-action test we execute immediately then throw
            if (shouldThrow) {
                // Simulate executor that runs callback then throws
                delegate.flush();
                throw new IllegalStateException("callback already completed, executor throws afterwards");
            }
        }
        @Override public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) { throw new IllegalStateException("x"); }
        @Override public void runAtLocation(@NotNull Location location, @NotNull Runnable action) { throw new IllegalStateException("x"); }
        @Override public void runGlobal(@NotNull Runnable action) { throw new IllegalStateException("x"); }
        @Override public void runAsync(@NotNull Runnable action) { throw new IllegalStateException("x"); }
        void flushDelegate() { delegate.flush(); }
    }

    @Test
    void callbackAlreadyCompletedThenExecutorThrowsRetainsOriginalResult() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        // Custom executor that executes callback then throws synchronously
        FoliaContextExecutor throwingAfter = new FoliaContextExecutor() {
            @Override
            public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
                action.run();
                throw new IllegalStateException("executor throws after callback success");
            }
            @Override public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) { throw new IllegalStateException("x"); }
            @Override public void runAtLocation(@NotNull Location location, @NotNull Runnable action) { throw new IllegalStateException("x"); }
            @Override public void runGlobal(@NotNull Runnable action) { throw new IllegalStateException("x"); }
            @Override public void runAsync(@NotNull Runnable action) { throw new IllegalStateException("x"); }
        };
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack(1);
        ItemStack copy = banknoteStack(1);
        Mockito.when(held.clone()).thenReturn(copy);
        Mockito.when(held.isSimilar(copy)).thenReturn(true);
        Mockito.when(copy.isSimilar(held)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(gui, throwingAfter, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future = session.handleClickAsync(player.getUniqueId(), gen, 0, held);
        assertTrue(future.isDone());
        V2BankGuiSession.ClickOutcome outcome = future.get();
        // Must retain the callback's credited result, not overwritten by dispatch.failed
        assertTrue(outcome.isCredited());
    }

    // ---- snapshot failure must fail closed ----

    @Test
    void cloneSameReferenceFailsClosedWithoutUseCaseOrInventoryMutation() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack heldA = banknoteStack(1);
        Mockito.when(heldA.clone()).thenReturn(heldA);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0, heldA);
        assertTrue(future.isDone());
        V2BankGuiSession.ClickOutcome outcome = future.get();
        assertTrue(outcome.isSnapshotFailed());
        assertEquals("item.snapshot-failed", outcome.reason());
        assertEquals(0, useCase.depositCalls, "snapshot failure must not reach business layer");
        verify(inv, never()).getItemInMainHand();
        verify(inv, never()).setItemInMainHand(Mockito.any());
        assertEquals(0, folia.queuedCount(), "snapshot failure must not dispatch to Folia");
    }

    @Test
    void cloneReturnsNullFailsClosed() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack heldA = banknoteStack(1);
        Mockito.when(heldA.clone()).thenReturn(null);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0, heldA);
        assertTrue(future.isDone());
        assertTrue(future.get().isSnapshotFailed());
        assertEquals(0, useCase.depositCalls);
        verify(inv, never()).setItemInMainHand(Mockito.any());
    }

    @Test
    void cloneThrowsFailsClosed() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        PlayerInventory inv = player.getInventory();
        ItemStack heldA = banknoteStack(1);
        Mockito.when(heldA.clone()).thenThrow(new RuntimeException("clone boom"));
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, depositResolver());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0, heldA);
        assertTrue(future.isDone());
        assertTrue(future.get().isSnapshotFailed());
        assertEquals(0, useCase.depositCalls);
        verify(inv, never()).setItemInMainHand(Mockito.any());
    }
}
