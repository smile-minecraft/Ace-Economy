package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiSession;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * True-timing publish-vs-rollback interleave: the candidate open passes the
 * post-check, then the rollback snapshot runs before the three bookkeeping
 * puts land. The candidate must not survive under the restored resolver.
 *
 * <p>The sessions map is wrapped (via reflection only to install the hook)
 * so the open thread parks on its first {@code put} — proving the post-check
 * already passed — while the test thread runs the real rollback. No state is
 * forged; the order is controlled by latches across real threads.
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

    /** Sessions map that parks the next put so the rollback lands in between. */
    private static final class ParkingSessions extends ConcurrentHashMap<UUID, GuiSession> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        ParkingSessions(Map<UUID, GuiSession> seed) {
            super(seed);
        }

        @Override
        public GuiSession put(UUID key, GuiSession value) {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS),
                        "rollback must run while the open is parked on publish");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return super.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, GuiSession> sessionsOf(V2BankGuiSession session) throws Exception {
        Field f = V2BankGuiSession.class.getDeclaredField("sessions");
        f.setAccessible(true);
        return (Map<UUID, GuiSession>) f.get(session);
    }

    @Test
    void candidatePublishedAfterRollbackSnapshotMustNotSurvive() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
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

        ParkingSessions parking = new ParkingSessions(sessionsOf(session));
        Field f = V2BankGuiSession.class.getDeclaredField("sessions");
        f.setAccessible(true);
        f.set(session, parking);

        ExecutorService opener = Executors.newSingleThreadExecutor();
        try {
            Future<V2BankGuiSession.OpenOutcome> opening = opener.submit(
                    () -> session.open(player, "Bank", 27, PROTECTED, failedGeneration));
            assertTrue(parking.entered.await(10, TimeUnit.SECONDS),
                    "open must reach publish (post-check passed) before rollback");

            // Real rollback window: restore the old resolver, then clean the
            // failed candidate. The snapshot inside sees no entry for id.
            session.replaceLayout(restored);
            session.dropSessionsAfterFailedSwap(keepThrough, failedGeneration);

            parking.release.countDown();
            V2BankGuiSession.OpenOutcome outcome = opening.get(10, TimeUnit.SECONDS);

            assertFalse(outcome.success(),
                    "candidate published after the rollback snapshot must not survive "
                            + "under the restored resolver");
            assertEquals("stale-layout", outcome.errorCode(),
                    "the losing open must report stale-layout so the caller retries");

            // The just-created GuiService entry must not stay usable.
            assertFalse(gui.getActiveSession(id).isSuccess(),
                    "the candidate GuiService entry must be closed, not left behind");

            // A retry from the fresh generation converges on the restored layout.
            V2BankGuiSession.OpenOutcome retry =
                    session.open(player, "Bank", 27, PROTECTED, session.layoutGeneration());
            assertTrue(retry.success(), "retry on the restored generation must succeed");
        } finally {
            opener.shutdownNow();
        }
    }
}
