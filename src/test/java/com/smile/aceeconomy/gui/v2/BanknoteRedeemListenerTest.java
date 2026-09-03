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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
        return new BanknoteRedeemListener(useCase, banknotes, folia, messages,
                java.util.logging.Logger.getLogger("BanknoteRedeemListenerTest"));
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
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(immediate()).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));

        assertEquals(1, useCase.depositCalls, "deposit must run exactly once through the GUI use case");
        assertSame(snapshot, useCase.lastDepositItem, "deposit must use the click-time snapshot, not the live item");
        assertEquals(playerId, useCase.lastDepositPlayer);
        verify(held).setAmount(2);
        verify(inv).setItemInMainHand(held);
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
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener(folia).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR, false));
        assertTrue(folia.playerCalled(), "dispatch must go through the Folia player context");
        assertEquals(0, useCase.depositCalls);

        folia.flush();
        assertEquals(1, useCase.depositCalls);
        verify(held).setAmount(1);
        verify(inv).setItemInMainHand(held);
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
