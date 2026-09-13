package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protected-slot wiring regression: consumer action slots must reach the business layer, so the
 * production open must keep them out of the AceLib protected set. Registering an action slot as
 * AceLib-protected makes {@code validateClick} reject the click before any business logic runs
 * (production {@code ACELIB-GUI-010}; the offline fake reports {@code slot-protected}).
 */
class BankProtectedSlotRegressionTest {

    private Player mockPlayer() {
        Player player = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(player.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(0);
        Mockito.when(inv.addItem(Mockito.any(ItemStack.class))).thenReturn(new HashMap<>());
        return player;
    }

    @Test
    void actionClickSucceedsWhenAceLibProtectedSetIsEmpty() {
        BankGuiLayout layout = BankGuiConfigParser.parse(null, Set.of("dollar"));
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout));
        Player player = mockPlayer();

        // Production wiring: action slots are guarded by the consumer listener,
        // never by AceLib, so the AceLib protected set stays empty.
        V2BankGuiSession.OpenOutcome open =
                session.open(player, "Bank", layout.size(), Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        int withdrawSlot = layout.actions().values().stream()
                .filter(slot -> slot.type() == BankGuiLayout.ActionType.WITHDRAW)
                .mapToInt(BankGuiLayout.SlotConfig::slot)
                .findFirst()
                .orElseThrow();
        V2BankGuiSession.ClickOutcome click =
                session.handleClick(player.getUniqueId(), gen, withdrawSlot);
        assertTrue(click.isSuccess(),
                "an action click must reach the business layer, got " + click.reason());
    }

    @Test
    void actionClickRejectedWhenActionSlotsPassedAsAceLibProtected() {
        BankGuiLayout layout = BankGuiConfigParser.parse(null, Set.of("dollar"));
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout));
        Player player = mockPlayer();

        // The old (buggy) wiring: action slots registered as AceLib-protected.
        V2BankGuiSession.OpenOutcome open =
                session.open(player, "Bank", layout.size(), layout.actionSlots());
        assertTrue(open.success());
        long gen = open.session().generation();

        int withdrawSlot = layout.actions().values().stream()
                .filter(slot -> slot.type() == BankGuiLayout.ActionType.WITHDRAW)
                .mapToInt(BankGuiLayout.SlotConfig::slot)
                .findFirst()
                .orElseThrow();
        V2BankGuiSession.ClickOutcome click =
                session.handleClick(player.getUniqueId(), gen, withdrawSlot);
        assertTrue(click.isRejected());
        assertEquals("slot-protected", click.reason(),
                "an AceLib-protected action slot must be rejected before the business layer");
        assertEquals(0, useCase.withdrawCalls);
    }
}
