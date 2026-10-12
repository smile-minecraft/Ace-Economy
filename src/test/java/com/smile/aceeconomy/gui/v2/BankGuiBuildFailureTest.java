package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision: buttons are fully built during assembly, and any single failure
 * refuses the open with an explicit error before the scope call. Assembly
 * never hands an incomplete view to the scope; once handed over, a per-slot
 * upstream copy or placement failure only skips that slot.
 */
class BankGuiBuildFailureTest {

    private static V2BankGuiSession session(GuiScope scope, BankGuiRenderer renderer,
                                            RecordingFoliaContext folia,
                                            ConfigLangAdapter messages) {
        return new V2BankGuiSession(scope, folia, new StubBankGuiUseCase(),
                BankGuiTestFixtures.defaultLayout(), messages, null, renderer);
    }

    @Test
    void unresolvableMaterialRefusesOpenBeforeScopeCall() {
        GuiScope scope = BankGuiTestFixtures.scope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        BankGuiRenderer broken = new BankGuiRenderer(
                material -> Mockito.mock(ItemStack.class), name -> null);
        V2BankGuiSession session = session(scope, broken, folia, messages);
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);

        assertFalse(open.success(), "an unbuildable button must refuse the open");
        assertEquals("view.build-failed", open.errorCode());
        assertTrue(open.detail() != null && !open.detail().isBlank(),
                "the refusal must name the failed button for the server log");
        Mockito.verify(scope, Mockito.never())
                .openView(Mockito.any(UUID.class), Mockito.any(GuiView.class));
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty(),
                "a refused open must track nothing");
        assertTrue(folia.playerCalled(), "the refusal must reach the player on the region thread");
        Mockito.verify(messages).renderMessage(Mockito.eq("general.transaction-failed"),
                Mockito.eq(Map.of()));
    }

    @Test
    void throwingItemFactoryRefusesOpenBeforeScopeCall() {
        GuiScope scope = BankGuiTestFixtures.scope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        BankGuiRenderer broken = new BankGuiRenderer(
                material -> {
                    throw new IllegalStateException("probe-boom");
                },
                name -> BankGuiTestFixtures.solidMaterial());
        V2BankGuiSession session = session(scope, broken, folia, messages);
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);

        assertFalse(open.success());
        assertEquals("view.build-failed", open.errorCode());
        Mockito.verify(scope, Mockito.never())
                .openView(Mockito.any(UUID.class), Mockito.any(GuiView.class));
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
        assertTrue(folia.playerCalled());
    }

    @Test
    void messageRenderFailureRefusesOpenBeforeScopeCall() {
        GuiScope scope = BankGuiTestFixtures.scope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn("Bank");
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new IllegalStateException("probe-boom"));
        V2BankGuiSession session = session(scope, BankGuiTestFixtures.renderer(), folia, messages);
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);

        assertFalse(open.success());
        assertEquals("view.build-failed", open.errorCode());
        Mockito.verify(scope, Mockito.never())
                .openView(Mockito.any(UUID.class), Mockito.any(GuiView.class));
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
    }

    @Test
    void titleRenderFailureRefusesOpen() {
        GuiScope scope = BankGuiTestFixtures.scope();
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new IllegalStateException("probe-boom"));
        V2BankGuiSession session = session(scope, BankGuiTestFixtures.renderer(),
                new RecordingFoliaContext(), messages);
        Player player = BankGuiTestFixtures.player();

        V2BankGuiSession.OpenOutcome open = session.open(player);

        assertFalse(open.success());
        Mockito.verify(scope, Mockito.never())
                .openView(Mockito.any(UUID.class), Mockito.any(GuiView.class));
        assertTrue(session.activeSession(player.getUniqueId()).isEmpty());
    }

    @Test
    void healthyLayoutStillOpensAfterRefusal() {
        GuiScope scope = BankGuiTestFixtures.scope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        V2BankGuiSession refused = new V2BankGuiSession(scope, folia,
                new StubBankGuiUseCase(), layout, messages, null,
                new BankGuiRenderer(material -> Mockito.mock(ItemStack.class), name -> null));
        Player player = BankGuiTestFixtures.player();
        assertFalse(refused.open(player).success());

        V2BankGuiSession healthy = new V2BankGuiSession(scope, folia,
                new StubBankGuiUseCase(), layout, messages,
                null, BankGuiTestFixtures.renderer());
        assertTrue(healthy.open(player).success(),
                "a refusal must leave no state that blocks a later healthy open");
    }
}
