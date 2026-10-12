package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Action-slot wiring regression: consumer action slots must reach the
 * business layer, so the declarative view registers them as buttons and
 * allows nothing else. Upstream then routes those clicks to the button
 * handlers and rejects everything else — no slot is ever registered as an
 * upstream-protected slot that would reject the click before business logic
 * runs, and no consumer listener exists to misroute it.
 */
class BankProtectedSlotRegressionTest {

    @Test
    void actionSlotsAreButtonsAndNothingIsAllowed() {
        BankGuiLayout layout = BankGuiConfigParser.parse(null, Set.of("dollar"));
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), layout, BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());

        ArgumentCaptor<GuiView> views = ArgumentCaptor.forClass(GuiView.class);
        Mockito.verify(scope).openView(Mockito.eq(player.getUniqueId()), views.capture());
        GuiView view = views.getValue();
        assertEquals(layout.actionSlots(), view.buttons().keySet(),
                "every action slot must be a button, or its click can never dispatch");
        assertTrue(view.allowedSlots().isEmpty(),
                "no slot may be allowed past the buttons; upstream rejects the rest");
    }

    @Test
    void everyActionSlotDispatchesToBusiness() {
        BankGuiLayout layout = BankGuiConfigParser.parse(null, Set.of("dollar"));
        GuiScope scope = BankGuiTestFixtures.scope();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                useCase, layout, BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();
        UUID uuid = player.getUniqueId();
        long generation = session.open(player).session().generation();
        GuiView view = scope.viewOf(uuid).orElseThrow();

        for (int slot : layout.actionSlots()) {
            BankGuiLayout.ActionType type = layout.actionForSlot(slot).orElseThrow().type();
            if (type == BankGuiLayout.ActionType.WITHDRAW) {
                String buttonId = view.buttons().get(slot).id();
                view.handlers().get(buttonId).accept(new com.smile.acelib.gui.GuiButtonClick(
                        uuid, generation, slot, buttonId, java.util.Optional.empty()));
            }
        }

        long withdraws = layout.actionSlots().stream()
                .map(slot -> layout.actionForSlot(slot).orElseThrow())
                .filter(slot -> slot.type() == BankGuiLayout.ActionType.WITHDRAW)
                .count();
        assertEquals((int) withdraws, useCase.withdrawCalls,
                "every withdraw slot must reach the business layer");
    }
}
