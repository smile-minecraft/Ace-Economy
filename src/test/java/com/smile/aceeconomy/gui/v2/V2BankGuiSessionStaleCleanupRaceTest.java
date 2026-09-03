package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stale-cleanup race: when a {@code replaceLayout} lands while an open is still
 * inside {@code openInventory}, the open's post-check must drop only its own
 * stale session. A retry that already rebuilt a fresh session on the new
 * generation must survive the stale {@code finally} — otherwise the next click
 * falls through to {@code no-player}.
 */
class V2BankGuiSessionStaleCleanupRaceTest {

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

    @Test
    void staleFinallyMustNotDeleteRetrySession() throws Exception {
        FakeGuiService real = FakeGuiService.available();
        FakeGuiService gui = Mockito.spy(real);
        // Block the first openInventory AFTER the session is created, so the
        // stale open holds its own GuiService session while the reload and the
        // retry both land before its post-check runs.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
            GuiResult result = (GuiResult) invocation.callRealMethod();
            if (first.getAndSet(false)) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS),
                        "retry must land while the stale open is in flight");
            }
            return result;
        }).when(gui).openInventory(Mockito.any());

        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Function<Integer, BankGuiAction> resolver = slot -> BankGuiAction.withdraw(100);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, resolver);
        Player player = mockPlayer();
        UUID id = player.getUniqueId();
        long boundGeneration = session.layoutGeneration();

        ExecutorService opener = Executors.newSingleThreadExecutor();
        try {
            Future<V2BankGuiSession.OpenOutcome> staleOpen = opener.submit(
                    () -> session.open(player, "Bank", 27, PROTECTED, boundGeneration));
            assertTrue(entered.await(10, TimeUnit.SECONDS),
                    "stale open must reach openInventory first");

            // Reload swaps the generation while the stale open is parked.
            session.replaceLayout(resolver);

            // The retry binds the new generation and converges while the stale
            // open is still parked inside openInventory.
            V2BankGuiSession.OpenOutcome retry =
                    session.open(player, "Bank", 27, PROTECTED, session.layoutGeneration());
            assertTrue(retry.success(), "retry on the fresh generation must succeed");

            release.countDown();
            V2BankGuiSession.OpenOutcome stale = staleOpen.get(10, TimeUnit.SECONDS);
            assertFalse(stale.success(), "the parked open must not build a stale session");
            assertEquals("stale-layout", stale.errorCode());

            // The retry session must still be fully usable: its GuiService entry
            // survived (the stale close missed on generation) and the local
            // bookkeeping must not have been wiped by the stale finally.
            GuiResult active = gui.getActiveSession(id);
            assertTrue(active.isSuccess() && active.session() != null);
            assertEquals(retry.session().generation(), active.session().generation(),
                    "the GuiService entry must be the retry session, not the stale one");
            V2BankGuiSession.ClickOutcome click = session.handleClick(
                    id, retry.session().generation(), 0);
            assertTrue(click.isSuccess(),
                    "click on the retry session must work, got " + click.reason());
            assertEquals(100L, useCase.lastAmount);
        } finally {
            opener.shutdownNow();
        }
    }
}
