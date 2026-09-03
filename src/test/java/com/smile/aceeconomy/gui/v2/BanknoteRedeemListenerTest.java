package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.item.ItemIdentity;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.DeferredFoliaContext;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Red-first contract for right-click banknote redemption: a valid v2 banknote in either hand is
 * credited through the same atomic {@link BankGuiUseCase} path as the bank GUI, exactly one item
 * is consumed, and every rejection keeps the physical item.
 */
class BanknoteRedeemListenerTest {

    private StubBankGuiUseCase useCase;
    private FakeBanknoteFactory banknotes;
    private ConfigLangAdapter messages;
    private Player player;
    private PlayerInventory inv;
    private UUID playerId;
    private BanknoteClaim claim;
    private ItemStack air;

    @BeforeEach
    void setUp() {
        useCase = new StubBankGuiUseCase();
        banknotes = new FakeBanknoteFactory();
        messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(anyString(), anyMap()))
                .thenAnswer(invocation -> Component.text("msg:" + invocation.getArgument(0)));
        playerId = UUID.randomUUID();
        player = Mockito.mock(Player.class);
        inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        Mockito.when(player.getInventory()).thenReturn(inv);
        claim = new BanknoteClaim(
                new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, 100L, UUID.randomUUID(), UUID.randomUUID(), "dollar");
        banknotes.decodeResult = Optional.of(claim);
        air = stack(true, 0);
    }

    private BanknoteRedeemListener listener(FoliaContextExecutor folia) {
        return listener(folia, java.util.logging.Logger.getLogger("BanknoteRedeemListenerTest"));
    }

    private BanknoteRedeemListener listener(FoliaContextExecutor folia, Logger audit) {
        return new BanknoteRedeemListener(useCase, banknotes, folia, messages, audit);
    }

    private FoliaContextExecutor immediate() {
        return new FoliaContextExecutor() {
            @Override
            public void runForPlayer(@NotNull Player p, @NotNull Runnable action) {
                action.run();
            }

            @Override
            public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
            }

            @Override
            public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
            }

            @Override
            public void runGlobal(@NotNull Runnable action) {
            }

            @Override
            public void runAsync(@NotNull Runnable action) {
            }
        };
    }

    private ItemStack stack(boolean air, int amount) {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(air);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(material);
        Mockito.when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private PlayerInteractEvent interact(EquipmentSlot hand, Action action, boolean cancelled) {
        PlayerInteractEvent event = Mockito.mock(PlayerInteractEvent.class);
        Mockito.when(event.getHand()).thenReturn(hand);
        Mockito.when(event.getAction()).thenReturn(action);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        Mockito.when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    @DisplayName("right-click on a valid banknote credits once and consumes exactly one item")
    void rightClickValidBanknoteCreditsAndConsumesOne() {
        ItemStack held = stack(false, 3);
        ItemStack snapshot = stack(false, 3);
        ItemStack updated = stack(false, 3);
        Mockito.when(held.clone()).thenReturn(snapshot, updated);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "deposit must run exactly once through the GUI use case");
        assertSame(snapshot, useCase.lastDepositItem, "deposit must use the click-time snapshot, not the live item");
        assertEquals(playerId, useCase.lastDepositPlayer);
        verify(updated).setAmount(2);
        verify(inv).setItemInMainHand(updated);
        verify(held, never()).setAmount(any(int.class));
        assertEquals(3, held.getAmount(), "the live stack must stay untouched; only the clone is written back");
        verify(messages).renderMessage(eq("banknote.redeem-success"), anyMap());
    }

    @Test
    @DisplayName("a single banknote stack is cleared from the hand, not decremented below zero")
    void singleItemClearedFromHand() {
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_BLOCK, false));

        assertEquals(1, useCase.depositCalls);
        verify(inv).setItemInMainHand((ItemStack) null);
        verify(held, never()).setAmount(any(int.class));
    }

    @Test
    @DisplayName("failed credit keeps the item untouched")
    void failedCreditKeepsItem() {
        useCase.depositMode = StubBankGuiUseCase.DepositMode.REJECTED;
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls);
        verify(held, never()).setAmount(any(int.class));
        verify(inv, never()).setItemInMainHand(any());
        verify(messages).renderMessage(eq("banknote.redeem-failed"), anyMap());
    }

    @Test
    @DisplayName("replay keeps the item and never double-credits")
    void replayKeepsItem() {
        useCase.depositMode = StubBankGuiUseCase.DepositMode.REJECTED;
        useCase.depositReason = "replay.detected";
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        BanknoteRedeemListener target = listener(immediate());
        target.onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));
        target.onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(2, useCase.depositCalls, "both attempts reach the atomic path; the store owns replay");
        verify(inv, never()).setItemInMainHand(any());
        verify(held, never()).setAmount(any(int.class));
    }

    @Test
    @DisplayName("swapped item after click-time snapshot is not removed")
    void swappedItemAfterSnapshotIsNotRemoved() {
        DeferredFoliaContext folia = new DeferredFoliaContext();
        ItemStack heldAtClick = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        ItemStack swapped = stack(false, 1);
        Mockito.when(heldAtClick.clone()).thenReturn(snapshot);
        Mockito.when(swapped.isSimilar(snapshot)).thenReturn(false);
        Mockito.when(inv.getItemInMainHand()).thenReturn(heldAtClick, swapped);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(folia).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));
        assertEquals(0, useCase.depositCalls, "nothing may touch Bukkit state before the region context runs");
        folia.flush();

        assertEquals(1, useCase.depositCalls);
        verify(inv, never()).setItemInMainHand(any());
        verify(swapped, never()).setAmount(any(int.class));
        verify(messages).renderMessage(eq("banknote.redeem-retained"), anyMap());
    }

    @Test
    @DisplayName("non-banknote items never reach the use case and the event is untouched")
    void nonBanknoteIgnored() {
        banknotes.decodeResult = Optional.empty();
        ItemStack held = stack(false, 1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);
        PlayerInteractEvent event = interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false);

        listener(immediate()).onInteract(event);

        assertEquals(0, useCase.depositCalls);
        verify(event, never()).setCancelled(any(boolean.class));
        verify(messages, never()).renderMessage(anyString(), anyMap());
    }

    @Test
    @DisplayName("cancelled events are not processed")
    void cancelledEventIgnored() {
        ItemStack held = stack(false, 1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, true));

        assertEquals(0, useCase.depositCalls);
        verify(messages, never()).renderMessage(anyString(), anyMap());
    }

    @Test
    @DisplayName("off-hand event pass is ignored so one click redeems at most once")
    void offHandEventIgnored() {
        ItemStack held = stack(false, 1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);

        listener(immediate()).onInteract(interact(EquipmentSlot.OFF_HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(0, useCase.depositCalls);
    }

    @Test
    @DisplayName("left-click never redeems")
    void leftClickIgnored() {
        ItemStack held = stack(false, 1);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        PlayerInteractEvent event = interact(EquipmentSlot.HAND, Action.LEFT_CLICK_AIR, false);

        listener(immediate()).onInteract(event);

        assertEquals(0, useCase.depositCalls);
        verify(event, never()).setCancelled(any(boolean.class));
    }

    @Test
    @DisplayName("all inventory work happens inside the player region context")
    void allInventoryWorkRunsInPlayerRegionContext() {
        DeferredFoliaContext folia = new DeferredFoliaContext();
        ItemStack held = stack(false, 2);
        ItemStack snapshot = stack(false, 2);
        ItemStack updated = stack(false, 2);
        Mockito.when(held.clone()).thenReturn(snapshot, updated);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(folia).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));
        assertTrue(folia.playerCalled(), "dispatch must go through the Folia player context");
        assertEquals(0, useCase.depositCalls);

        folia.flush();
        assertEquals(1, useCase.depositCalls);
        verify(updated).setAmount(1);
        verify(inv).setItemInMainHand(updated);
        verify(held, never()).setAmount(any(int.class));
    }

    @Test
    @DisplayName("banknote in the off hand is redeemed from the off-hand slot")
    void offHandBanknoteRedeemed() {
        ItemStack mainItem = stack(false, 1);
        ItemStack offItem = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(offItem.clone()).thenReturn(snapshot);
        Mockito.when(offItem.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(mainItem);
        Mockito.when(inv.getItemInOffHand()).thenReturn(offItem);
        banknotes.failOn.add(mainItem);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls);
        assertSame(snapshot, useCase.lastDepositItem);
        verify(inv).setItemInOffHand((ItemStack) null);
        verify(inv, never()).setItemInMainHand(any());
    }

    @Test
    @DisplayName("unclonable item never reaches the use case and stays in hand")
    void unclonableItemKeepsItem() {
        ItemStack held = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(null);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(0, useCase.depositCalls);
        verify(inv, never()).setItemInMainHand(any());
    }

    @Test
    @DisplayName("decrement failure after a committed credit retains the note with audit, not a silent duplicate")
    void decrementFailureAfterCreditRetainsNote() {
        ItemStack held = stack(false, 2);
        ItemStack snapshot = stack(false, 2);
        ItemStack updated = stack(false, 2);
        Mockito.when(held.clone()).thenReturn(snapshot, updated);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);
        Mockito.doThrow(new IllegalStateException("injected inventory outage"))
                .when(updated).setAmount(any(int.class));
        RecordingHandler auditLog = installAuditLog();

        listener(immediate(), auditLog.logger)
                .onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "the credit already committed before the mutation failed");
        verify(held, never()).setAmount(any(int.class));
        verify(inv, never()).setItemInMainHand(any());
        assertEquals(2, held.getAmount(), "the live stack keeps both notes for manual review");
        verify(messages).renderMessage(eq("banknote.redeem-retained"), anyMap());
        verify(messages, never()).renderMessage(eq("banknote.redeem-success"), anyMap());
        assertTrue(auditLog.hasSevere(),
                "a retained credit must leave an audit record for manual review, never fail silently");
        assertTrue(auditLog.hasRetainedAuditFor(claim.nonce().toString(), playerId.toString(),
                        Long.toString(useCase.depositValue)),
                "the audit record must carry the nonce, player and credited value so an admin can reissue manually");
    }

    @Test
    @DisplayName("slot-clear failure after a committed credit leaves an audit trail for manual reissue")
    void slotClearFailureAfterCreditLeavesAuditForManualReissue() {
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);
        Mockito.doThrow(new IllegalStateException("injected slot outage"))
                .when(inv).setItemInMainHand(any());
        RecordingHandler auditLog = installAuditLog();

        listener(immediate(), auditLog.logger)
                .onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "the credit already committed before the mutation failed");
        verify(held, never()).setAmount(any(int.class));
        verify(inv).setItemInMainHand((ItemStack) null);
        // This only proves the listener never mutated the live item in place: a failed Bukkit
        // slot clear may still leave the slot in an unknown state, so the contract here is the
        // audit trail below, not the item.
        assertEquals(1, held.getAmount(), "the listener leaves the live item reference untouched");
        verify(messages).renderMessage(eq("banknote.redeem-retained"), anyMap());
        verify(messages, never()).renderMessage(eq("banknote.redeem-success"), anyMap());
        assertTrue(auditLog.hasSevere(),
                "a retained credit must leave an audit record for manual review, never fail silently");
        assertTrue(auditLog.hasRetainedAuditFor(claim.nonce().toString(), playerId.toString(),
                        Long.toString(useCase.depositValue)),
                "the audit record must carry the nonce, player and credited value so an admin can reissue manually");
    }

    @Test
    @DisplayName("slot-write failure after a successful decrement keeps the full stack, never a half-removed note")
    void slotWriteFailureAfterDecrementKeepsFullStack() {
        ItemStack held = stack(false, 2);
        ItemStack snapshot = stack(false, 2);
        ItemStack updated = stack(false, 2);
        Mockito.when(held.clone()).thenReturn(snapshot, updated);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);
        Mockito.doThrow(new IllegalStateException("injected slot outage"))
                .when(inv).setItemInMainHand(any());
        RecordingHandler auditLog = installAuditLog();

        listener(immediate(), auditLog.logger)
                .onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "the credit already committed before the slot write failed");
        verify(updated).setAmount(1);
        verify(inv).setItemInMainHand(updated);
        verify(held, never()).setAmount(any(int.class));
        assertEquals(2, held.getAmount(),
                "the live stack is never mutated in place, so a failed slot write keeps both notes");
        verify(messages).renderMessage(eq("banknote.redeem-retained"), anyMap());
        verify(messages, never()).renderMessage(eq("banknote.redeem-success"), anyMap());
        assertTrue(auditLog.hasSevere(),
                "a retained credit must leave an audit record for manual review, never fail silently");
        assertTrue(auditLog.hasRetainedAuditFor(claim.nonce().toString(), playerId.toString(),
                        Long.toString(useCase.depositValue)),
                "the audit record must carry the nonce, player and credited value so an admin can reissue manually");
    }

    @Test
    @DisplayName("hand-read failure after a committed credit retains the note with audit")
    void handReadFailureAfterCreditRetainsNote() {
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(inv.getItemInMainHand())
                .thenReturn(held)
                .thenThrow(new IllegalStateException("injected hand-read outage"));
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);
        RecordingHandler auditLog = installAuditLog();

        listener(immediate(), auditLog.logger)
                .onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "the credit already committed before the hand read failed");
        verify(held, never()).setAmount(any(int.class));
        verify(inv, never()).setItemInMainHand(any());
        verify(messages).renderMessage(eq("banknote.redeem-retained"), anyMap());
        verify(messages, never()).renderMessage(eq("banknote.redeem-success"), anyMap());
        assertTrue(auditLog.hasSevere(),
                "a retained credit must leave an audit record for manual review, never fail silently");
        assertTrue(auditLog.hasRetainedAuditFor(claim.nonce().toString(), playerId.toString(),
                        Long.toString(useCase.depositValue)),
                "the audit record must carry the nonce, player and credited value so an admin can reissue manually");
    }

    @Test
    @DisplayName("single-note clear failure observed through fake-inventory state leaves an audit trail")
    void singleNoteClearFailureObservedThroughFakeInventory() {
        // Credited value deliberately differs from the note face value (100) so the audit
        // assertion below can tell the two apart.
        useCase.depositValue = 777777187L;
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        FakeHandInventory hands = new FakeHandInventory(held, air);
        hands.failWrites = true;
        Mockito.when(player.getInventory()).thenReturn(hands.view);
        RecordingHandler auditLog = installAuditLog();

        listener(immediate(), auditLog.logger)
                .onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "the credit already committed before the slot write failed");
        assertEquals(1, hands.writeAttempts, "exactly one slot write was attempted");
        assertSame(held, hands.view.getItemInMainHand(),
                "the endorsement still reports the original note: the listener never mutates the live stack");
        // A failed single-note clear may leave a real Bukkit slot in an unknown state, so the
        // contract is the audit trail, not the item: it must carry the actually-credited value.
        assertTrue(auditLog.hasRetainedAuditFor(claim.nonce().toString(), playerId.toString(), "777777187"),
                "the audit record must carry the credited value, not the note face value");
        verify(messages).renderMessage(eq("banknote.redeem-retained"),
                argThat(params -> params != null && "777777187".equals(params.get("amount"))));
        verify(messages, never()).renderMessage(eq("banknote.redeem-success"), anyMap());
    }

    @Test
    @DisplayName("multi-note write failure observed through fake-inventory state keeps the full stack")
    void multiNoteWriteFailureObservedThroughFakeInventory() {
        useCase.depositValue = 777777187L;
        ItemStack held = stack(false, 2);
        ItemStack snapshot = stack(false, 2);
        ItemStack updated = stack(false, 2);
        Mockito.when(held.clone()).thenReturn(snapshot, updated);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        FakeHandInventory hands = new FakeHandInventory(held, air);
        hands.failWrites = true;
        Mockito.when(player.getInventory()).thenReturn(hands.view);
        RecordingHandler auditLog = installAuditLog();

        listener(immediate(), auditLog.logger)
                .onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "the credit already committed before the slot write failed");
        assertEquals(1, hands.writeAttempts, "removal is a single slot write after the clone decrement");
        verify(updated).setAmount(1);
        assertSame(held, hands.view.getItemInMainHand(),
                "clone-then-write never touches the live stack, so the endorsement still holds both notes");
        assertEquals(2, hands.view.getItemInMainHand().getAmount(),
                "a failed slot write keeps the full stack for manual review");
        assertTrue(auditLog.hasRetainedAuditFor(claim.nonce().toString(), playerId.toString(), "777777187"),
                "the audit record must carry the credited value, not the note face value");
        verify(messages).renderMessage(eq("banknote.redeem-retained"),
                argThat(params -> params != null && "777777187".equals(params.get("amount"))));
        verify(messages, never()).renderMessage(eq("banknote.redeem-success"), anyMap());
    }

    /**
     * Stateful fake inventory endorsement: it holds real slot state behind a {@link PlayerInventory}
     * view and its writes can be flipped to throw on demand, so tests observe the slot state before
     * and after a failure instead of only verifying mock calls.
     */
    private static final class FakeHandInventory implements InvocationHandler {
        final PlayerInventory view;
        private ItemStack mainHand;
        private ItemStack offHand;
        boolean failWrites;
        int writeAttempts;

        FakeHandInventory(ItemStack mainHand, ItemStack offHand) {
            this.mainHand = mainHand;
            this.offHand = offHand;
            this.view = (PlayerInventory) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {PlayerInventory.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getItemInMainHand":
                    return mainHand;
                case "getItemInOffHand":
                    return offHand;
                case "setItemInMainHand":
                    writeAttempts++;
                    if (failWrites) {
                        throw new IllegalStateException("injected slot outage");
                    }
                    mainHand = (ItemStack) args[0];
                    return null;
                case "setItemInOffHand":
                    writeAttempts++;
                    if (failWrites) {
                        throw new IllegalStateException("injected slot outage");
                    }
                    offHand = (ItemStack) args[0];
                    return null;
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class || type == long.class || type == short.class || type == byte.class) {
                        return 0;
                    }
                    if (type == float.class || type == double.class) {
                        return 0.0;
                    }
                    return null;
            }
        }
    }

    private RecordingHandler installAuditLog() {
        Logger logger = Logger.getLogger("BanknoteRedeemRetainedTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        RecordingHandler handler = new RecordingHandler(logger);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return handler;
    }

    /** Captures audit records so tests can prove a retained credit is never silent. */
    private static final class RecordingHandler extends Handler {
        final Logger logger;
        final List<LogRecord> records = new ArrayList<>();

        RecordingHandler(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean hasSevere() {
            return records.stream().anyMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue());
        }

        boolean hasRetainedAuditFor(String nonce, String playerId, String creditedValue) {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                    .map(LogRecord::getMessage)
                    .anyMatch(message -> message != null
                            && message.contains(nonce)
                            && message.contains(playerId)
                            && message.contains("value=" + creditedValue));
        }
    }

    /** Deterministic factory fake: decode succeeds only for programmed items. */
    static final class FakeBanknoteFactory implements BanknoteFactory {
        Optional<BanknoteClaim> decodeResult = Optional.empty();
        final java.util.Set<ItemStack> failOn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        @Override
        public @NotNull Optional<ItemStack> mint(@NotNull BanknoteClaim claim) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<BanknoteClaim> decode(@NotNull ItemStack stack) {
            if (failOn.contains(stack)) {
                return Optional.empty();
            }
            return decodeResult;
        }
    }
}
