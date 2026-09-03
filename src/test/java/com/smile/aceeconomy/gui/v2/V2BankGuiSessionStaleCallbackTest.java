package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.DeferredFoliaContext;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stale-callback contract for {@link V2BankGuiSession}: the pre-dispatch generation check is
 * not enough on Folia, because the region callback may run after a reload has already
 * invalidated the session. The callback must re-validate the generation on execution and
 * discard the action when it no longer matches, instead of running the old operation.
 */
class V2BankGuiSessionStaleCallbackTest {

    private static final Set<Integer> PROTECTED = Set.of();

    private Player mockPlayer() {
        Player p = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(p.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(0);
        Mockito.when(inv.addItem(Mockito.any(ItemStack.class))).thenReturn(new HashMap<Integer, ItemStack>());
        return p;
    }

    private ItemStack banknoteStack() {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(false);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(material);
        Mockito.when(stack.getAmount()).thenReturn(1);
        return stack;
    }

    @Test
    void withdrawQueuedBeforeInvalidateIsDiscardedOnFlush() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, slot -> BankGuiAction.withdraw(100));
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(open.success());
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0);
        assertFalse(future.isDone(), "deferred dispatch must not complete before flush");

        session.invalidateAll();
        folia.flush();

        V2BankGuiSession.ClickOutcome outcome = future.get(5, TimeUnit.SECONDS);
        assertTrue(outcome.isRejected(),
                "callback invalidated before execution must be discarded, got success-like outcome");
        assertEquals(0, useCase.withdrawCalls,
                "discarded callback must never reach the business layer");
    }

    @Test
    void depositQueuedBeforeInvalidateIsDiscardedOnFlush() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer();
        PlayerInventory inv = player.getInventory();
        ItemStack held = banknoteStack();
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, slot -> BankGuiAction.deposit());
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(open.success());
        long gen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(player.getUniqueId(), gen, 0);
        assertFalse(future.isDone());

        session.invalidateAll();
        folia.flush();

        V2BankGuiSession.ClickOutcome outcome = future.get(5, TimeUnit.SECONDS);
        assertTrue(outcome.isRejected(),
                "callback invalidated before execution must be discarded, got success-like outcome");
        assertEquals(0, useCase.depositCalls,
                "discarded callback must never reach the business layer");
    }

    @Test
    void oldCallbackDiscardedAfterInvalidateAndReopenWhileNewGenerationWorks() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, slot -> BankGuiAction.withdraw(100));
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(open.success());
        long staleGen = open.session().generation();

        CompletableFuture<V2BankGuiSession.ClickOutcome> stale =
                session.handleClickAsync(player.getUniqueId(), staleGen, 0);
        assertFalse(stale.isDone());

        session.invalidateAll();
        V2BankGuiSession.OpenOutcome reopened = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(reopened.success());
        long freshGen = reopened.session().generation();

        folia.flush();
        V2BankGuiSession.ClickOutcome staleOutcome = stale.get(5, TimeUnit.SECONDS);
        assertTrue(staleOutcome.isRejected(),
                "old generation callback must be discarded even after a fresh session exists");
        assertEquals(0, useCase.withdrawCalls,
                "discarded callback must never reach the business layer");

        CompletableFuture<V2BankGuiSession.ClickOutcome> fresh =
                session.handleClickAsync(player.getUniqueId(), freshGen, 0);
        folia.flush();
        assertTrue(fresh.get(5, TimeUnit.SECONDS).isSuccess(),
                "fresh generation must still act normally");
        assertEquals(1, useCase.withdrawCalls);
    }
}
