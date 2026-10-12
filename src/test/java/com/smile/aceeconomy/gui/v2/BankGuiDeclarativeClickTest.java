package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiButtonClick;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Button clicks on the declarative bank view must behave exactly like the
 * previous command-style path: withdraw moves money and hands over the
 * note, deposit credits the click-time snapshot and consumes one item,
 * close drops the view, and every failure replies on the region thread
 * while successes stay silent.
 *
 * <p>Stale clicks never reach the ledger: the scope only dispatches live
 * generations upstream, and the handler re-checks liveness first, so a
 * rejected click is a reply, never a transaction.
 */
class BankGuiDeclarativeClickTest {

    private static final class Opened {
        V2BankGuiSession session;
        GuiScope scope;
        RecordingFoliaContext folia;
        ConfigLangAdapter messages;
        StubBankGuiUseCase useCase;
        Player player;
        UUID uuid;
        long generation;
        GuiView view;
    }

    private static Opened open() {
        return open(new StubBankGuiUseCase());
    }

    private static Opened open(StubBankGuiUseCase useCase) {
        Opened opened = new Opened();
        opened.scope = BankGuiTestFixtures.scope();
        opened.folia = new RecordingFoliaContext();
        opened.messages = BankGuiTestFixtures.messages();
        opened.useCase = useCase;
        opened.session = new V2BankGuiSession(opened.scope, opened.folia, useCase,
                BankGuiTestFixtures.defaultLayout(), opened.messages, null,
                BankGuiTestFixtures.renderer());
        opened.player = BankGuiTestFixtures.player();
        opened.uuid = opened.player.getUniqueId();
        V2BankGuiSession.OpenOutcome outcome = opened.session.open(opened.player);
        assertTrue(outcome.success(), "setup open must succeed: " + outcome.errorCode());
        opened.generation = outcome.session().generation();
        ArgumentCaptor<GuiView> views = ArgumentCaptor.forClass(GuiView.class);
        Mockito.verify(opened.scope).openView(Mockito.eq(opened.uuid), views.capture());
        opened.view = views.getValue();
        Mockito.clearInvocations(opened.messages);
        return opened;
    }

    private static void click(Opened opened, int slot) {
        click(opened, slot, opened.generation);
    }

    private static void click(Opened opened, int slot, long generation) {
        String buttonId = opened.view.buttons().get(slot).id();
        Consumer<GuiButtonClick> handler = opened.view.handlers().get(buttonId);
        assertTrue(handler != null, "slot " + slot + " must have a handler");
        handler.accept(new GuiButtonClick(opened.uuid, generation, slot, buttonId, Optional.empty()));
    }

    private static void expectReply(Opened opened, String key) {
        Mockito.verify(opened.messages).renderMessage(Mockito.eq(key), Mockito.anyMap());
        assertTrue(opened.folia.playerCalled(),
                "the " + key + " reply must go through the region thread");
    }

    @Test
    void withdrawMovesMoneyAndHandsOverNote() {
        Opened opened = open();

        click(opened, 11);

        assertEquals(1, opened.useCase.withdrawCalls);
        assertEquals(100L, opened.useCase.lastAmount);
        assertEquals(opened.uuid, opened.useCase.lastPlayer);
        Mockito.verify(opened.player.getInventory()).addItem(Mockito.any(ItemStack.class));
        Mockito.verify(opened.messages, Mockito.never())
                .renderMessage(Mockito.anyString(), Mockito.anyMap());
    }

    @Test
    void withdrawUsesPerSlotAmount() {
        Opened opened = open();

        click(opened, 13);

        assertEquals(500L, opened.useCase.lastAmount);
    }

    @Test
    void repeatedClicksDispatchExactlyOnceEach() {
        Opened opened = open();

        click(opened, 11);
        click(opened, 11);

        assertEquals(2, opened.useCase.withdrawCalls,
                "each click dispatches exactly once; conservation itself is owned by the use case");
        Mockito.verify(opened.player.getInventory(), Mockito.times(2))
                .addItem(Mockito.any(ItemStack.class));
    }

    @Test
    void fullInventoryNeverReachesLedger() {
        Opened opened = open();
        Mockito.when(opened.player.getInventory().firstEmpty()).thenReturn(-1);

        click(opened, 11);

        assertEquals(0, opened.useCase.withdrawCalls);
        expectReply(opened, "general.inventory-full");
    }

    @Test
    void useCaseInventoryFullRepliesInventoryFull() {
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.mode = StubBankGuiUseCase.Mode.INVENTORY_FULL;
        Opened opened = open(useCase);

        click(opened, 11);

        expectReply(opened, "general.inventory-full");
        Mockito.verify(opened.player.getInventory(), Mockito.never())
                .addItem(Mockito.any(ItemStack.class));
    }

    @Test
    void rejectedWithdrawRepliesFailedAndKeepsHandsOffInventory() {
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.mode = StubBankGuiUseCase.Mode.REJECTED;
        Opened opened = open(useCase);

        click(opened, 11);

        expectReply(opened, "general.transaction-failed");
        Mockito.verify(opened.player.getInventory(), Mockito.never())
                .addItem(Mockito.any(ItemStack.class));
    }

    @Test
    void depositCreditsClickTimeSnapshotAndConsumesOne() {
        Opened opened = open();
        PlayerInventory inventory = opened.player.getInventory();
        ItemStack held = BankGuiTestFixtures.heldBanknote(2);
        Mockito.when(inventory.getItemInMainHand()).thenReturn(held);

        click(opened, 4);

        assertEquals(1, opened.useCase.depositCalls);
        assertTrue(opened.useCase.lastDepositItem != held,
                "the credited item must be the click-time clone, never the live reference");
        Mockito.verify(held).setAmount(1);
        Mockito.verify(inventory).setItemInMainHand(held);
        Mockito.verify(opened.messages, Mockito.never())
                .renderMessage(Mockito.anyString(), Mockito.anyMap());
    }

    @Test
    void depositWithEmptyHandRepliesFailed() {
        Opened opened = open();
        Mockito.when(opened.player.getInventory().getItemInMainHand()).thenReturn(null);

        click(opened, 4);

        assertEquals(0, opened.useCase.depositCalls);
        expectReply(opened, "general.transaction-failed");
    }

    @Test
    void rejectedDepositKeepsItemInHand() {
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.depositMode = StubBankGuiUseCase.DepositMode.REJECTED;
        Opened opened = open(useCase);
        PlayerInventory inventory = opened.player.getInventory();
        ItemStack held = BankGuiTestFixtures.heldBanknote(1);
        Mockito.when(inventory.getItemInMainHand()).thenReturn(held);

        click(opened, 4);

        assertEquals(1, opened.useCase.depositCalls);
        Mockito.verify(inventory, Mockito.never()).setItemInMainHand(Mockito.any());
        expectReply(opened, "general.transaction-failed");
    }

    @Test
    void swappedHandAfterCreditRepliesMismatchAndKeepsItem() {
        Opened opened = open();
        PlayerInventory inventory = opened.player.getInventory();
        ItemStack held = BankGuiTestFixtures.heldBanknote(1);
        ItemStack swapped = Mockito.mock(ItemStack.class);
        org.bukkit.Material swappedType = BankGuiTestFixtures.solidMaterial();
        Mockito.when(swapped.getType()).thenReturn(swappedType);
        Mockito.when(swapped.getAmount()).thenReturn(1);
        Mockito.when(inventory.getItemInMainHand()).thenReturn(held, swapped);

        click(opened, 4);

        assertEquals(1, opened.useCase.depositCalls, "the click-time note is still credited once");
        Mockito.verify(inventory, Mockito.never()).setItemInMainHand(Mockito.any());
        expectReply(opened, "banknote.redeem-mismatch");
    }

    @Test
    void unclonableHeldItemStaysSilent() {
        Opened opened = open();
        PlayerInventory inventory = opened.player.getInventory();
        ItemStack held = Mockito.mock(ItemStack.class);
        org.bukkit.Material heldType = BankGuiTestFixtures.solidMaterial();
        Mockito.when(held.getType()).thenReturn(heldType);
        Mockito.when(held.getAmount()).thenReturn(1);
        Mockito.when(held.clone()).thenReturn(null);
        Mockito.when(inventory.getItemInMainHand()).thenReturn(held);

        click(opened, 4);

        assertEquals(0, opened.useCase.depositCalls,
                "an unsnapshotable item must never reach the ledger");
        Mockito.verify(opened.messages, Mockito.never())
                .renderMessage(Mockito.anyString(), Mockito.anyMap());
    }

    @Test
    void singleNoteIsClearedFromHand() {
        Opened opened = open();
        PlayerInventory inventory = opened.player.getInventory();
        ItemStack held = BankGuiTestFixtures.heldBanknote(1);
        Mockito.when(inventory.getItemInMainHand()).thenReturn(held);

        click(opened, 4);

        Mockito.verify(inventory).setItemInMainHand(null);
    }

    @Test
    void closeDropsViewThroughScopeAndStaysSilent() {
        Opened opened = open();

        click(opened, 15);

        Mockito.verify(opened.scope).close(opened.uuid);
        assertTrue(opened.session.activeSession(opened.uuid).isEmpty());
        Mockito.verify(opened.messages, Mockito.never())
                .renderMessage(Mockito.anyString(), Mockito.anyMap());
        assertEquals(0, opened.useCase.withdrawCalls + opened.useCase.depositCalls);
    }

    @Test
    void staleClickRepliesCancelledWithoutTouchingLedger() {
        Opened opened = open();

        click(opened, 11, opened.generation + 1);

        assertEquals(0, opened.useCase.withdrawCalls,
                "a stale click must never reach the business layer");
        expectReply(opened, "general.transaction-cancelled");
    }

    @Test
    void staleDepositClickRepliesCancelledWithoutTouchingLedger() {
        Opened opened = open();
        PlayerInventory inventory = opened.player.getInventory();
        ItemStack staleHeld = BankGuiTestFixtures.heldBanknote(1);
        Mockito.when(inventory.getItemInMainHand())
                .thenReturn(staleHeld);

        click(opened, 4, opened.generation + 1);

        assertEquals(0, opened.useCase.depositCalls);
        expectReply(opened, "general.transaction-cancelled");
    }

    @Test
    void clickAfterCloseIsStale() {
        Opened opened = open();
        click(opened, 15);
        Mockito.clearInvocations(opened.messages);

        // Tracking is gone with the close, so the reply resolves the player
        // through the server lookup, exactly like production.
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(opened.uuid)).thenReturn(opened.player);
            click(opened, 11);
        }

        assertEquals(0, opened.useCase.withdrawCalls);
        expectReply(opened, "general.transaction-cancelled");
    }
}
