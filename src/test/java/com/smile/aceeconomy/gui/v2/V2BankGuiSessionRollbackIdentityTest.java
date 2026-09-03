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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollback must not trust a layout tag that no longer belongs to the current session.
 *
 * <p>An open publishes its bookkeeping in three puts (sessions, tag, players). A rollback
 * that reads the tag between the sessions put and the tag put of a same-UUID candidate
 * still sees the old tag ({@code <= keepThrough}) while {@code sessions} already holds
 * the new session — trusting the tag alone lets the candidate survive under the restored
 * resolver. The tag has to be bound to the session it was written for.
 *
 * <p>The same three-put window leaves a second hazard: a {@code players.put} that lands
 * after the rollback removed the session leaves a players entry without a session.
 * The open repairs its own lost race, and rollback sweeps entries that no longer back
 * any session.
 */
class V2BankGuiSessionRollbackIdentityTest {

    private static final Set<Integer> PROTECTED = Set.of();

    private Player mockPlayer() {
        return mockPlayer(UUID.randomUUID());
    }

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

    private V2BankGuiSession newSession(FakeGuiService gui) {
        return new V2BankGuiSession(gui, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), slot -> BankGuiAction.withdraw(100));
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, V2BankGuiSession.SessionTag> tagsOf(V2BankGuiSession session)
            throws Exception {
        Field f = V2BankGuiSession.class.getDeclaredField("sessionLayoutGenerations");
        f.setAccessible(true);
        return (Map<UUID, V2BankGuiSession.SessionTag>) f.get(session);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, GuiSession> sessionsOf(V2BankGuiSession session) throws Exception {
        Field f = V2BankGuiSession.class.getDeclaredField("sessions");
        f.setAccessible(true);
        return (Map<UUID, GuiSession>) f.get(session);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Player> playersOf(V2BankGuiSession session) throws Exception {
        Field f = V2BankGuiSession.class.getDeclaredField("players");
        f.setAccessible(true);
        return (Map<UUID, Player>) f.get(session);
    }

    @Test
    void staleTagWithSwappedSessionsMustStillDropNewSession() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = newSession(gui);
        Function<Integer, BankGuiAction> resolver = slot -> BankGuiAction.withdraw(100);

        // Same player reopens on the failed candidate: same UUID, new session.
        Player player = mockPlayer();
        UUID id = player.getUniqueId();
        V2BankGuiSession.OpenOutcome oldOpen =
                session.open(player, "Bank", 27, PROTECTED);
        assertTrue(oldOpen.success());

        long keepThrough = session.layoutGeneration();
        session.replaceLayout(resolver);
        long failedGeneration = session.layoutGeneration();

        V2BankGuiSession.OpenOutcome candidate =
                session.open(player, "Bank", 27, PROTECTED, failedGeneration);
        assertTrue(candidate.success());

        // Mimic the interleave: the candidate's sessions put landed, but the tag
        // put is not visible yet, so the tag still belongs to the pre-swap session.
        Map<UUID, V2BankGuiSession.SessionTag> tags = tagsOf(session);
        tags.put(id, new V2BankGuiSession.SessionTag(keepThrough, oldOpen.session()));

        int dropped = session.dropSessionsAfterFailedSwap(keepThrough, failedGeneration);

        assertEquals(1, dropped,
                "candidate session behind a stale tag must be dropped, not skipped");
        assertFalse(gui.getActiveSession(id).isSuccess(),
                "the GuiService entry for the candidate must be closed");
        assertFalse(tags.containsKey(id), "the stale tag must not survive");
        assertFalse(playersOf(session).containsKey(id),
                "the candidate players entry must not survive");
        V2BankGuiSession.ClickOutcome click = session.handleClick(
                id, candidate.session().generation(), 0);
        assertTrue(click.isRejected(),
                "the candidate generation must no longer act, got " + click.reason());
    }

    @Test
    void rollbackSweepsPlayersOrphanWithoutSession() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = newSession(gui);
        Function<Integer, BankGuiAction> resolver = slot -> BankGuiAction.withdraw(100);

        long keepThrough = session.layoutGeneration();
        session.replaceLayout(resolver);
        long failedGeneration = session.layoutGeneration();

        // A players.put that lands after the rollback removed the session leaves
        // an entry no session backs. Rollback must sweep it.
        Player orphan = mockPlayer();
        playersOf(session).put(orphan.getUniqueId(), orphan);

        int dropped = session.dropSessionsAfterFailedSwap(keepThrough, failedGeneration);

        assertEquals(0, dropped, "no session exists, nothing to close");
        assertFalse(playersOf(session).containsKey(orphan.getUniqueId()),
                "players entry without a session must not survive rollback");
    }

    @Test
    void successfulOpenPublishesAllThreeBookkeepingEntries() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = newSession(gui);

        Player player = mockPlayer();
        UUID id = player.getUniqueId();
        V2BankGuiSession.OpenOutcome open =
                session.open(player, "Bank", 27, PROTECTED);
        assertTrue(open.success());

        assertTrue(sessionsOf(session).containsKey(id), "sessions must track the open");
        V2BankGuiSession.SessionTag tag = tagsOf(session).get(id);
        assertTrue(tag != null && tag.owner() == open.session(),
                "the tag must belong to the opened session");
        assertTrue(playersOf(session).containsKey(id), "players must track the open");
    }

    @Test
    void lostRaceGuardDropsOrphanTagAndPlayers() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = newSession(gui);

        // Orphan leftovers of an open whose session a rollback already removed:
        // the tag and players puts landed after the removal.
        UUID id = UUID.randomUUID();
        GuiSession owned = new GuiSession(id, 99L, "v2-bank", "Bank", 27, Set.of());
        Player player = mockPlayer(id);
        tagsOf(session).put(id, new V2BankGuiSession.SessionTag(7L, owned));
        playersOf(session).put(id, player);

        assertTrue(session.dropOrphanAfterLostRace(id, owned),
                "orphan leftovers of the lost race must be cleaned");

        assertFalse(tagsOf(session).containsKey(id), "orphan tag must be gone");
        assertFalse(playersOf(session).containsKey(id), "orphan players entry must be gone");
    }

    @Test
    void lostRaceGuardKeepsNewerRetrySession() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = newSession(gui);

        // A newer retry already rebuilt while our stale puts were in flight:
        // sessions holds the retry, the tag still points at our session.
        UUID id = UUID.randomUUID();
        GuiSession ours = new GuiSession(id, 98L, "v2-bank", "Bank", 27, Set.of());
        GuiSession retry = new GuiSession(id, 99L, "v2-bank", "Bank", 27, Set.of());
        Player player = mockPlayer(id);
        sessionsOf(session).put(id, retry);
        tagsOf(session).put(id, new V2BankGuiSession.SessionTag(7L, ours));
        playersOf(session).put(id, player);

        assertFalse(session.dropOrphanAfterLostRace(id, ours),
                "a newer retry session must be kept");

        assertTrue(sessionsOf(session).get(id) == retry, "retry session must stay");
        assertTrue(tagsOf(session).containsKey(id), "retry bookkeeping must stay");
        assertTrue(playersOf(session).containsKey(id), "retry players entry must stay");
    }
}
