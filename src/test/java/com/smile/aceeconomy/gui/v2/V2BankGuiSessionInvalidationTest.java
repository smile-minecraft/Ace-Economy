package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reload invalidation contract for {@link V2BankGuiSession}: a reload must be able
 * to drop every open session (so no pre-reload generation can act under post-reload
 * rules) and to hot-swap the layout resolver for sessions opened afterwards.
 */
class V2BankGuiSessionInvalidationTest {

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

    @Test
    void invalidateAllClosesOpenSessionsAndRejectsOldGenerations() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Function<Integer, BankGuiAction> resolver = slot -> BankGuiAction.withdraw(100);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, resolver);

        Player first = mockPlayer();
        Player second = mockPlayer();
        V2BankGuiSession.OpenOutcome open1 = session.open(first, "Bank", 27, PROTECTED);
        V2BankGuiSession.OpenOutcome open2 = session.open(second, "Bank", 27, PROTECTED);
        assertTrue(open1.success());
        assertTrue(open2.success());
        long staleGen = open1.session().generation();

        int closed = session.invalidateAll();

        assertEquals(2, closed);
        V2BankGuiSession.ClickOutcome click =
                session.handleClick(first.getUniqueId(), staleGen, 0);
        assertTrue(click.isRejected(),
                "pre-reload generation must not act after invalidation, got " + click.reason());
        assertFalse(gui.getActiveSession(first.getUniqueId()).isSuccess(),
                "underlying GUI session must be closed");
        assertFalse(gui.getActiveSession(second.getUniqueId()).isSuccess(),
                "underlying GUI session must be closed");
    }

    @Test
    void reopenAfterInvalidateGetsFreshSession() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, slot -> BankGuiAction.none());

        Player player = mockPlayer();
        V2BankGuiSession.OpenOutcome before = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(before.success());
        session.invalidateAll();

        V2BankGuiSession.OpenOutcome after = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(after.success());
        assertNotEquals(before.session().generation(), after.session().generation());
        V2BankGuiSession.ClickOutcome click = session.handleClick(
                player.getUniqueId(), after.session().generation(), 0);
        assertTrue(click.isAllowed(), "fresh generation must act normally");
    }

    @Test
    void replaceLayoutSwapsResolverForNewSessions() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, slot -> BankGuiAction.none());

        Player player = mockPlayer();
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(open.success());
        // Slot resolves to none: sync click is allowed without business action.
        assertTrue(session.handleClick(player.getUniqueId(), open.session().generation(), 0).isAllowed());

        session.replaceLayout(slot -> BankGuiAction.close());

        V2BankGuiSession.OpenOutcome reopened = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(reopened.success());
        V2BankGuiSession.ClickOutcome close = session.handleClick(
                player.getUniqueId(), reopened.session().generation(), 5);
        assertTrue(close.isAllowed());
        assertFalse(gui.getActiveSession(player.getUniqueId()).isSuccess(),
                "close action from the replaced layout must close the session");
    }
}
