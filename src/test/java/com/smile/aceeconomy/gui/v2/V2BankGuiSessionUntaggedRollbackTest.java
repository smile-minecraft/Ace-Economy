package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollback must treat an untagged local entry as suspect.
 *
 * <p>An open records its bookkeeping in three puts; a rollback that lands
 * between the sessions put and the generation-tag put sees a session entry
 * without a tag. That entry cannot be proven old, so the rollback drops it
 * instead of leaving a candidate-layout session behind under the restored
 * resolver. Entries with a tag at or below the pre-swap generation stay.
 */
class V2BankGuiSessionUntaggedRollbackTest {

    private static final Set<Integer> PROTECTED = Set.of();

    private Player mockPlayer() {
        Player p = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(p.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(0);
        Mockito.when(inv.addItem(Mockito.any(ItemStack.class)))
                .thenReturn(new HashMap<Integer, ItemStack>());
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Long> tagsOf(V2BankGuiSession session) throws Exception {
        Field f = V2BankGuiSession.class.getDeclaredField("sessionLayoutGenerations");
        f.setAccessible(true);
        return (Map<UUID, Long>) f.get(session);
    }

    @Test
    void untaggedEntryBetweenBookkeepingPutsIsDroppedByRollback() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Function<Integer, BankGuiAction> resolver = slot -> BankGuiAction.withdraw(100);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, resolver);

        Player oldPlayer = mockPlayer();
        V2BankGuiSession.OpenOutcome oldOpen =
                session.open(oldPlayer, "Bank", 27, PROTECTED);
        assertTrue(oldOpen.success());

        long keepThrough = session.layoutGeneration();
        session.replaceLayout(resolver);
        long failedGeneration = session.layoutGeneration();

        Player gapPlayer = mockPlayer();
        V2BankGuiSession.OpenOutcome gapOpen =
                session.open(gapPlayer, "Bank", 27, PROTECTED, failedGeneration);
        assertTrue(gapOpen.success());

        // Mimic the interleave: sessions.put landed, the tag put has not yet.
        Map<UUID, Long> tags = tagsOf(session);
        assertTrue(tags.remove(gapPlayer.getUniqueId()) != null
                || !tags.containsKey(gapPlayer.getUniqueId()));

        int dropped = session.dropSessionsAfterFailedSwap(keepThrough, failedGeneration);

        assertEquals(1, dropped, "untagged gap entry must be dropped, not skipped");
        assertFalse(gui.getActiveSession(gapPlayer.getUniqueId()).isSuccess(),
                "the GuiService entry for the gap session must be closed");
        V2BankGuiSession.ClickOutcome gapClick = session.handleClick(
                gapPlayer.getUniqueId(), gapOpen.session().generation(), 0);
        assertTrue(gapClick.isRejected(),
                "the gap generation must no longer act, got " + gapClick.reason());

        V2BankGuiSession.ClickOutcome oldClick = session.handleClick(
                oldPlayer.getUniqueId(), oldOpen.session().generation(), 0);
        assertTrue(oldClick.isSuccess(),
                "pre-swap session must keep working, got " + oldClick.reason());
    }
}
