package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow;

import net.kyori.adventure.text.Component;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every click failure and pending state must reply with the cause it names,
 * on the player region thread; successes stay silent. The mapping below is
 * the whole contract: a swapped hand reports the completed credit, an
 * uncertain withdraw never promises a delivery, and a stale click reports a
 * cancellation.
 */
class BankGuiClickReplyTest {

    private static String keyOf(V2BankGuiSession.ClickOutcome outcome) {
        V2BankGuiSession.Reply reply = V2BankGuiSession.replyFor(outcome, null);
        assertNotNull(reply, "failure and pending outcomes must reply, never stay silent");
        return reply.key();
    }

    @Test
    void inventoryFullMapsToInventoryFull() {
        assertEquals("general.inventory-full",
                keyOf(V2BankGuiSession.ClickOutcome.inventoryFull()));
    }

    @Test
    void itemMismatchMapsToMismatch() {
        assertEquals("banknote.redeem-mismatch",
                keyOf(V2BankGuiSession.ClickOutcome.itemMismatch()));
    }

    @Test
    void retainedCreditMapsToRetainedWithAmount() {
        V2BankGuiSession.Reply reply =
                V2BankGuiSession.replyFor(V2BankGuiSession.ClickOutcome.creditRetained(100L), null);
        assertNotNull(reply);
        assertEquals("banknote.redeem-retained", reply.key());
        assertEquals(Map.of("amount", "100"), reply.vars());
    }

    @Test
    void retainedCreditWithoutValueMapsToFailed() {
        assertEquals("banknote.redeem-failed",
                keyOf(V2BankGuiSession.ClickOutcome.creditRetained()));
    }

    @Test
    void creditIndeterminateMapsToRedeemPending() {
        assertEquals("banknote.redeem-pending", keyOf(V2BankGuiSession.ClickOutcome.pending(
                BanknoteFlow.PENDING_CREDIT_INDETERMINATE)));
    }

    @Test
    void flowShutdownMapsToCancelled() {
        assertEquals("general.transaction-cancelled", keyOf(
                V2BankGuiSession.ClickOutcome.pending(BanknoteFlow.PENDING_SHUTDOWN)));
    }

    @Test
    void uncertainWithdrawMapsToWithdrawPending() {
        for (String reason : new String[] {
                BanknoteFlow.PENDING_WITHDRAW_INDETERMINATE,
                BanknoteFlow.PENDING_DELIVERY_UNCERTAIN}) {
            assertEquals("banknote.withdraw-pending",
                    keyOf(V2BankGuiSession.ClickOutcome.pending(reason)),
                    "uncertain " + reason + " must never promise a delivery");
        }
    }

    @Test
    void insufficientFundsMapsToInsufficient() {
        assertEquals("economy.insufficient-funds",
                keyOf(V2BankGuiSession.ClickOutcome.rejected("insufficient.funds")));
    }

    @Test
    void replayMapsToReplay() {
        assertEquals("banknote.redeem-replay",
                keyOf(V2BankGuiSession.ClickOutcome.rejected("replay.detected")));
    }

    @Test
    void uncertainDeliveryMapsToDeliveryUncertain() {
        assertEquals("banknote.delivery-uncertain",
                keyOf(V2BankGuiSession.ClickOutcome.rejected("delivery.uncertain")));
    }

    @Test
    void staleAndCancelledMapToCancelled() {
        for (String reason : new String[] {"stale.generation", "transaction.cancelled"}) {
            assertEquals("general.transaction-cancelled",
                    keyOf(V2BankGuiSession.ClickOutcome.rejected(reason)));
        }
    }

    @Test
    void unknownRejectionMapsToFailed() {
        assertEquals("general.transaction-failed",
                keyOf(V2BankGuiSession.ClickOutcome.rejected("business.rejected")));
    }

    @Test
    void futureFailureMapsToFailed() {
        V2BankGuiSession.Reply reply = V2BankGuiSession.replyFor(
                V2BankGuiSession.ClickOutcome.inventoryFull(), new RuntimeException("probe-boom"));
        assertNotNull(reply);
        assertEquals("general.transaction-failed", reply.key());
    }

    @Test
    void successesAndAllowancesStaySilent() {
        ItemStack note = Mockito.mock(ItemStack.class);
        assertNull(V2BankGuiSession.replyFor(V2BankGuiSession.ClickOutcome.success(note), null));
        assertNull(V2BankGuiSession.replyFor(V2BankGuiSession.ClickOutcome.credited(), null));
        assertNull(V2BankGuiSession.replyFor(V2BankGuiSession.ClickOutcome.allowed(), null));
        assertNull(V2BankGuiSession.replyFor(
                V2BankGuiSession.ClickOutcome.snapshotFailed(), null));
    }

    @Test
    void failureReplyArrivesOnTheRegionThread() {
        com.smile.acelib.gui.GuiScope scope = BankGuiTestFixtures.scope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn("Bank");
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> Component.text("msg:" + invocation.getArgument(0)));
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.mode = StubBankGuiUseCase.Mode.INVENTORY_FULL;
        V2BankGuiSession session = new V2BankGuiSession(scope, folia, useCase,
                BankGuiTestFixtures.defaultLayout(), messages,
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();
        assertTrue(session.open(player).success());
        Mockito.clearInvocations(messages);

        com.smile.acelib.gui.GuiView view = scope.viewOf(player.getUniqueId()).orElseThrow();
        String buttonId = view.buttons().get(11).id();
        Thread caller = Thread.currentThread();
        view.handlers().get(buttonId).accept(new com.smile.acelib.gui.GuiButtonClick(
                player.getUniqueId(), session.activeSession(player.getUniqueId()).orElseThrow()
                        .generation(),
                11, buttonId, java.util.Optional.empty()));

        Mockito.verify(messages).renderMessage(Mockito.eq("general.inventory-full"),
                Mockito.anyMap());
        assertTrue(folia.playerCalled(), "the reply must go through the region thread");
        assertTrue(folia.playerRunnables.size() == 1
                        && folia.calls.size() == 1,
                "exactly one region reply per click, on " + caller.getName());
    }
}
