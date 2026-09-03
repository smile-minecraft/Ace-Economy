package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiAsyncRequest;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * True-timing publish-vs-rollback interleave: the candidate open passes the
 * entry check and finishes {@code openInventory}, then parks before acquiring
 * its publish fence while the rollback snapshot runs. The candidate must not
 * survive under the restored resolver.
 *
 * <p>Parking before fence acquisition proves the rollback was not blocked by
 * the open: the rollback runs to completion while the opener is parked, sees
 * an empty snapshot, and the opener then self-cleans through the post-publish
 * generation fence and reports stale-layout for a retry.
 */
class V2BankGuiSessionPublishRollbackRaceTest {

    private static final Set<Integer> PROTECTED = Set.of();

    private Player mockPlayer(UUID id) {
        Player p = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(p.getUniqueId()).thenReturn(id);
        Mockito.when(p.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(0);
        Mockito.when(inv.addItem(Mockito.any(ItemStack.class)))
                .thenReturn(new HashMap<Integer, ItemStack>());
        return p;
    }

    /** GuiService wrapper that parks after openInventory, before the open takes its fence. */
    private static final class ParkingGuiService implements GuiService {
        private final FakeGuiService delegate;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        ParkingGuiService(FakeGuiService delegate) {
            this.delegate = delegate;
        }

        @Override
        public GuiResult openInventory(GuiArgument argument) {
            GuiResult result = delegate.openInventory(argument);
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS),
                        "rollback must run while the open is parked before fence acquisition");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return result;
        }

        @Override
        public GuiResult closeInventory(UUID playerUuid, long generation) {
            return delegate.closeInventory(playerUuid, generation);
        }

        @Override
        public GuiResult getActiveSession(UUID playerUuid) {
            return delegate.getActiveSession(playerUuid);
        }

        @Override
        public GuiResult validateClick(UUID playerUuid, long generation, int slot) {
            return delegate.validateClick(playerUuid, generation, slot);
        }

        @Override
        public GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration, int pageIndex) {
            return delegate.beginAsyncUpdate(playerUuid, sessionGeneration, pageIndex);
        }

        @Override
        public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request, GuiPage<T> page,
                                              Runnable renderer) {
            return delegate.applyAsyncUpdate(request, page, renderer);
        }

        @Override
        public String getModuleStatus() {
            return delegate.getModuleStatus();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }

    @Test
    void candidatePublishedAfterRollbackSnapshotMustNotSurvive() throws Exception {
        FakeGuiService fake = FakeGuiService.available();
        ParkingGuiService gui = new ParkingGuiService(fake);
        V2BankGuiSession session = new V2BankGuiSession(gui, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), slot -> BankGuiAction.withdraw(100));
        Function<Integer, BankGuiAction> candidate = slot -> BankGuiAction.withdraw(100);
        Function<Integer, BankGuiAction> restored = slot -> BankGuiAction.withdraw(200);

        long keepThrough = session.layoutGeneration();
        session.replaceLayout(candidate);
        long failedGeneration = session.layoutGeneration();

        // Fresh player: no entry yet, so the rollback snapshot cannot see it.
        UUID id = UUID.randomUUID();
        Player player = mockPlayer(id);

        ExecutorService opener = Executors.newSingleThreadExecutor();
        try {
            Future<V2BankGuiSession.OpenOutcome> opening = opener.submit(
                    () -> session.open(player, "Bank", 27, PROTECTED, failedGeneration));
            assertTrue(gui.entered.await(10, TimeUnit.SECONDS),
                    "open must finish openInventory (entry check passed) before rollback");
            assertFalse(opening.isDone(),
                    "open must still be parked before fence acquisition while rollback runs");

            // Real rollback window: restore the old resolver, then clean the
            // failed candidate. The snapshot inside sees no local entry for id
            // because the opener has not taken its fence yet.
            session.replaceLayout(restored);
            int dropped = session.dropSessionsAfterFailedSwap(keepThrough, failedGeneration);
            assertEquals(0, dropped,
                    "rollback snapshot must miss the parked candidate, proving it ran "
                            + "in the pre-publish window instead of being blocked by the open");
            assertFalse(opening.isDone(),
                    "rollback must complete while the open is still parked before its fence");

            gui.release.countDown();
            V2BankGuiSession.OpenOutcome outcome = opening.get(10, TimeUnit.SECONDS);

            assertFalse(outcome.success(),
                    "candidate published after the rollback snapshot must not survive "
                            + "under the restored resolver");
            assertEquals("stale-layout", outcome.errorCode(),
                    "the losing open must report stale-layout so the caller retries");

            // The just-created GuiService entry must not stay usable.
            assertFalse(fake.getActiveSession(id).isSuccess(),
                    "the candidate GuiService entry must be closed, not left behind");

            // A retry from the fresh generation converges on the restored layout.
            V2BankGuiSession.OpenOutcome retry =
                    session.open(player, "Bank", 27, PROTECTED, session.layoutGeneration());
            assertTrue(retry.success(), "retry on the restored generation must succeed");
        } finally {
            opener.shutdownNow();
        }
    }

    @Test
    void publishFenceStaysBoundedAcrossManyKeys() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(gui, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), slot -> BankGuiAction.withdraw(100));

        Field stripesField = V2BankGuiSession.class.getDeclaredField("keyGuards");
        stripesField.setAccessible(true);
        Object[] stripes = (Object[]) stripesField.get(session);
        assertEquals(V2BankGuiSession.GUARD_STRIPES, stripes.length,
                "publish fence must stay at a fixed stripe count under UUID churn");

        Method guardFor = V2BankGuiSession.class.getDeclaredMethod("guardFor", UUID.class);
        guardFor.setAccessible(true);
        UUID fixed = UUID.randomUUID();
        assertSame(guardFor.invoke(session, fixed), guardFor.invoke(session, fixed),
                "same player must always map to the same stripe");

        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(guardFor.invoke(session, UUID.randomUUID()));
        }
        assertTrue(seen.size() <= V2BankGuiSession.GUARD_STRIPES,
                "distinct players must share a bounded set of stripes, never grow per UUID");
    }
}
