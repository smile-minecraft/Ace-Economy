package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiButtonClick;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reload invalidation contract for {@link V2BankGuiSession}: invalidation
 * must drop every tracked view (so no pre-reload click can act under
 * post-reload rules), while a reload itself reopens tracked views with the
 * new layout instead of dropping them.
 */
class V2BankGuiSessionInvalidationTest {

    @Test
    void invalidateAllClosesTrackedViewsAndRejectsOldClicks() {
        GuiScope scope = BankGuiTestFixtures.scope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(scope, folia, useCase,
                BankGuiTestFixtures.defaultLayout(), BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());

        Player first = BankGuiTestFixtures.player();
        Player second = BankGuiTestFixtures.player();
        V2BankGuiSession.OpenOutcome open1 = session.open(first);
        V2BankGuiSession.OpenOutcome open2 = session.open(second);
        assertTrue(open1.success());
        assertTrue(open2.success());
        long staleGen = open1.session().generation();

        int closed = session.invalidateAll();

        assertEquals(2, closed);
        assertTrue(session.activeSession(first.getUniqueId()).isEmpty());
        assertTrue(session.activeSession(second.getUniqueId()).isEmpty());
        assertTrue(scope.viewOf(first.getUniqueId()).isEmpty(),
                "the underlying scope view must be closed");
        assertTrue(scope.viewOf(second.getUniqueId()).isEmpty(),
                "the underlying scope view must be closed");

        // The old generation is dead: it no longer counts as current, so a
        // replayed click replies cancelled without touching the ledger.
        assertTrue(!session.isClickStillCurrent(first.getUniqueId(), staleGen),
                "pre-reload generation must not act after invalidation");
        assertEquals(0, useCase.withdrawCalls + useCase.depositCalls);
    }

    @Test
    void reopenAfterInvalidateGetsFreshSession() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), BankGuiTestFixtures.defaultLayout(),
                BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());

        Player player = BankGuiTestFixtures.player();
        V2BankGuiSession.OpenOutcome before = session.open(player);
        assertTrue(before.success());
        session.invalidateAll();

        V2BankGuiSession.OpenOutcome after = session.open(player);
        assertTrue(after.success());
        assertTrue(before.session().generation() != after.session().generation());
        assertTrue(session.activeSession(player.getUniqueId()).isPresent());
    }

    @Test
    void reloadReopensInsteadOfInvalidating() {
        GuiScope scope = BankGuiTestFixtures.scope();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                useCase, BankGuiTestFixtures.defaultLayout(), BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());

        Player player = BankGuiTestFixtures.player();
        V2BankGuiSession.OpenOutcome open = session.open(player);
        assertTrue(open.success());

        session.applyLayout(BankGuiTestFixtures.defaultLayout());

        assertTrue(session.activeSession(player.getUniqueId()).isPresent(),
                "a reload must reopen, not drop, the tracked view");
        GuiView view = scope.viewOf(player.getUniqueId()).orElseThrow();
        long generation = session.activeSession(player.getUniqueId()).orElseThrow().generation();
        String buttonId = view.buttons().get(11).id();
        view.handlers().get(buttonId).accept(new GuiButtonClick(
                player.getUniqueId(), generation, 11, buttonId, Optional.empty()));
        assertEquals(100L, useCase.lastAmount,
                "the reopened view must act under the current layout");
        Mockito.verify(scope, Mockito.never()).close(Mockito.eq(player.getUniqueId()));
    }

    @Test
    void repeatedInvalidationIsStable() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), BankGuiTestFixtures.defaultLayout(),
                BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());

        assertEquals(1, session.invalidateAll());
        assertEquals(0, session.invalidateAll());
        assertEquals(0, session.invalidateAll());
        assertTrue(session.activeSession(UUID.randomUUID()).isEmpty());
    }
}
