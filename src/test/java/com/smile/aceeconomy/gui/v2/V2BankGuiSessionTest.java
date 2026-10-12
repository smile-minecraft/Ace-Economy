package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link V2BankGuiSession} against the stub scope and
 * {@link RecordingFoliaContext}. These lock the declarative bank GUI without
 * a live server:
 *
 * <ul>
 *   <li>open assembles one complete view and opens it through
 *       {@code GuiScope#openView} — the only upstream seam this class touches;</li>
 *   <li>a scope failure refuses the open and tells the player instead of
 *       tracking a dead entry;</li>
 *   <li>close and invalidation drop tracking exactly once and stay silent
 *       when the scope call fails;</li>
 *   <li>a disabled layout refuses the open before any upstream call.</li>
 * </ul>
 */
class V2BankGuiSessionTest {

    private V2BankGuiSession session(GuiScope scope) {
        return new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), BankGuiTestFixtures.defaultLayout(),
                BankGuiTestFixtures.messages(), null, BankGuiTestFixtures.renderer());
    }

    private static GuiView openedView(GuiScope scope, UUID uuid) {
        ArgumentCaptor<GuiView> views = ArgumentCaptor.forClass(GuiView.class);
        Mockito.verify(scope).openView(Mockito.eq(uuid), views.capture());
        return views.getValue();
    }

    @Test
    void openBuildsCompleteViewThroughScope() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);
        assertTrue(open.success(), "open must succeed, got: " + open.errorCode());
        assertTrue(open.session() != null);

        GuiView view = openedView(scope, player.getUniqueId());
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        assertEquals("Bank", view.title());
        assertEquals(layout.size(), view.size());
        assertEquals(layout.actionSlots(), view.buttons().keySet(),
                "every configured action slot must be a button");
        assertEquals(layout.actionSlots(), view.buttonIcons().keySet(),
                "every configured action slot must carry a built icon");
        assertTrue(view.allowedSlots().isEmpty(),
                "nothing is allowed by default; upstream rejects every non-button click");
        assertTrue(session.activeSession(player.getUniqueId()).isPresent(),
                "a successful open must be tracked");
        Mockito.verify(scope, Mockito.never()).replaceView(Mockito.any(), Mockito.any());
    }

    @Test
    void openFailureRefusesAndTellsWithoutTracking() {
        GuiScope scope = Mockito.mock(GuiScope.class);
        Mockito.when(scope.openView(Mockito.any(UUID.class), Mockito.any(GuiView.class)))
                .thenReturn(GuiResult.failed("open.failed", "probe-boom"));
        RecordingFoliaContext folia = new RecordingFoliaContext();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        V2BankGuiSession session = new V2BankGuiSession(scope, folia,
                new StubBankGuiUseCase(), BankGuiTestFixtures.defaultLayout(), messages,
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);
        assertFalse(open.success(), "a scope failure must refuse the open");
        assertEquals("open.failed", open.errorCode());
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty(),
                "a refused open must track nothing");
        assertTrue(folia.playerCalled(), "the refusal must reach the player on the region thread");
        Mockito.verify(messages).renderMessage(Mockito.eq("general.transaction-failed"),
                Mockito.eq(Map.of()));
    }

    @Test
    void scopeThrowingOnOpenRefusesWithoutTracking() {
        GuiScope scope = Mockito.mock(GuiScope.class);
        Mockito.when(scope.openView(Mockito.any(UUID.class), Mockito.any(GuiView.class)))
                .thenThrow(new IllegalStateException("probe-boom"));
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);
        assertFalse(open.success());
        assertEquals("open.failed", open.errorCode());
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
    }

    @Test
    void disabledLayoutRefusesBeforeAnyScopeCall() {
        GuiScope scope = BankGuiTestFixtures.scope();
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), layout, BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        session.applyLayout(disabled(layout));
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);
        assertFalse(open.success());
        assertEquals("bank.disabled", open.errorCode());
        Mockito.verify(scope, Mockito.never())
                .openView(Mockito.any(UUID.class), Mockito.any(GuiView.class));
    }

    @Test
    void applyingDisabledLayoutClosesTrackedViews() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());

        assertEquals(0, session.applyLayout(disabled(BankGuiTestFixtures.defaultLayout())));
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty(),
                "a disabled layout must close tracked views instead of reopening them");
        Mockito.verify(scope).close(player.getUniqueId());
    }

    private static BankGuiLayout disabled(BankGuiLayout layout) {
        return BankGuiLayout.of(false, layout.titleKey(), layout.size(), layout.actions());
    }

    @Test
    void closeDropsTracking() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());

        V2BankGuiSession.CloseOutcome close = session.close(player.getUniqueId());
        assertTrue(close.isClosed(), "close must succeed, got: " + close.errorCode());
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
        Mockito.verify(scope).close(player.getUniqueId());
    }

    @Test
    void closeStillDropsTrackingWhenScopeThrows() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());
        UUID uuid = player.getUniqueId();
        Mockito.doThrow(new IllegalStateException("probe-boom"))
                .when(scope).close(uuid);

        V2BankGuiSession.CloseOutcome close = session.close(uuid);
        assertFalse(close.isClosed());
        assertTrue(session.activeSession(uuid).isEmpty(),
                "tracking must be dropped even when the scope call fails");
    }

    @Test
    void invalidateAllDropsEveryTrackedViewOnce() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player first = BankGuiTestFixtures.player();
        Player second = BankGuiTestFixtures.player();
        assertTrue(session.open(first).success());
        assertTrue(session.open(second).success());

        assertEquals(2, session.invalidateAll(), "both tracked views must be dropped");
        assertTrue(session.activeSession(first.getUniqueId()).isEmpty());
        assertTrue(session.activeSession(second.getUniqueId()).isEmpty());
        assertEquals(0, session.invalidateAll(), "a repeated invalidation must drop nothing");
    }

    @Test
    void invalidateAllSurvivesScopeFailures() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());
        Mockito.doThrow(new IllegalStateException("probe-boom"))
                .when(scope).close(Mockito.any(UUID.class));

        assertEquals(1, session.invalidateAll(),
                "tracking must be cleared even when the scope call throws");
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
        assertEquals(0, session.invalidateAll());
    }

    @Test
    void reopenAfterInvalidateGetsFreshSession() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player player = BankGuiTestFixtures.player();
        long firstGeneration = session.open(player).session().generation();

        assertEquals(1, session.invalidateAll());
        V2BankGuiSession.OpenOutcome reopened = session.open(player);
        assertTrue(reopened.success(), "bank must still open after an invalidation");
        assertTrue(reopened.session().generation() != firstGeneration,
                "the reopen must mint a fresh generation");
    }

    @Test
    void unknownPlayerHasNoSession() {
        assertTrue(session(BankGuiTestFixtures.scope())
                .activeSession(UUID.randomUUID()).isEmpty());
    }

    @Test
    void sessionNeverTouchesTheSharedServiceDirectly() {
        for (Field field : V2BankGuiSession.class.getDeclaredFields()) {
            assertFalse(GuiService.class.isAssignableFrom(field.getType()),
                    "the session must hold no shared-service reference, found: " + field);
        }
        for (Constructor<?> constructor : V2BankGuiSession.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(GuiService.class.isAssignableFrom(parameter),
                        "the session must take no shared-service collaborator, found: " + parameter);
            }
        }
    }

    @Test
    void trackedViewsAreIndependentPerPlayer() {
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = session(scope);
        Player first = BankGuiTestFixtures.player();
        Player second = BankGuiTestFixtures.player();
        assertTrue(session.open(first).success());
        assertTrue(session.open(second).success());

        Optional<?> firstSession = session.activeSession(first.getUniqueId());
        Optional<?> secondSession = session.activeSession(second.getUniqueId());
        assertTrue(firstSession.isPresent() && secondSession.isPresent());

        session.close(first.getUniqueId());
        assertTrue(session.activeSession(first.getUniqueId()).isEmpty());
        assertTrue(session.activeSession(second.getUniqueId()).isPresent(),
                "closing one player must never disturb the other");
        Mockito.verify(scope, Mockito.never()).close(second.getUniqueId());
    }

    @Test
    void nullSeamDropsNothing() {
        V2BankGuiSession session = session(BankGuiTestFixtures.scope());
        assertEquals(0, session.invalidateAll(),
                "an empty session must drop nothing instead of throwing");
        V2BankGuiSession.CloseOutcome close = session.close(UUID.randomUUID());
        assertFalse(close.isClosed());
    }
}
