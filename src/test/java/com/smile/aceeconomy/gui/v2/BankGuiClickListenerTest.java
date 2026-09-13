package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiSession;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Consumer listener contract for the Java bank GUI: bank top clicks are always cancelled and
 * only action slots reach {@link V2BankGuiSession} (even when AceLib cancelled first),
 * unconfigured top slots and bank drags are swallowed, plain bottom clicks pass through, and
 * closing the view drops the session.
 */
class BankGuiClickListenerTest {

    private static final long GENERATION = 7L;
    private static final String TITLE = "Bank";
    private static final int SIZE = 27;

    private UUID uuid;
    private Player player;
    private PlayerInventory playerInv;
    private Inventory top;
    private InventoryView view;
    private V2BankGuiSession session;
    private GuiSession aceSession;
    private BankGuiClickListener listener;

    @BeforeEach
    void setUp() {
        uuid = UUID.randomUUID();
        player = Mockito.mock(Player.class);
        playerInv = Mockito.mock(PlayerInventory.class);
        top = Mockito.mock(Inventory.class);
        view = Mockito.mock(InventoryView.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.getInventory()).thenReturn(playerInv);
        Mockito.when(top.getSize()).thenReturn(SIZE);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn(TITLE);

        session = Mockito.mock(V2BankGuiSession.class);
        aceSession = new GuiSession(uuid, GENERATION, "v2-bank", TITLE, SIZE, Set.of());
        Mockito.when(session.activeSession(uuid)).thenReturn(Optional.of(aceSession));
        // The mocked view's top inventory is treated as the bound bank view; the
        // foreign-identity negative path is covered by BankGuiForeignViewTest.
        Mockito.when(session.isBoundView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenReturn(true);
        Mockito.when(session.handleClickAsync(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.anyInt(),
                        Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(V2BankGuiSession.ClickOutcome.allowed()));
        listener = new BankGuiClickListener(session);
    }

    private InventoryClickEvent click(int rawSlot, boolean shift) {
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(rawSlot);
        Mockito.when(event.isShiftClick()).thenReturn(shift);
        return event;
    }

    @Test
    void actionSlotClickIsCancelledAndDispatched() {
        Mockito.when(session.actionForSlot(4)).thenReturn(BankGuiAction.deposit());
        ItemStack held = Mockito.mock(ItemStack.class);
        Mockito.when(playerInv.getItemInMainHand()).thenReturn(held);
        InventoryClickEvent event = click(4, false);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verify(session).handleClickAsync(uuid, GENERATION, 4, held);
    }

    @Test
    void actionSlotClickDispatchedEvenWhenAlreadyCancelled() {
        Mockito.when(session.actionForSlot(11))
                .thenReturn(BankGuiAction.withdraw(100L));
        InventoryClickEvent event = click(11, false);
        // AceLib's own listener runs first and cancels; the consumer dispatch
        // must still happen (ignoreCancelled = false).
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verify(session).handleClickAsync(uuid, GENERATION, 11);
    }

    @Test
    void unconfiguredTopSlotCancelledWithoutDispatch() {
        Mockito.when(session.actionForSlot(0)).thenReturn(BankGuiAction.none());
        InventoryClickEvent event = click(0, false);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void bottomInventoryClickPassesThrough() {
        InventoryClickEvent event = click(SIZE + 5, false);

        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void shiftClickFromBottomIsCancelledSoItemsCannotShiftMoveIn() {
        InventoryClickEvent event = click(SIZE + 5, true);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void outsideClickIsIgnored() {
        InventoryClickEvent event = click(-999, false);

        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void nonBankViewIsIgnored() {
        Mockito.when(view.getTitle()).thenReturn("Some other shop");
        InventoryClickEvent event = click(4, false);

        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
    }

    @Test
    void playerWithoutSessionIsIgnored() {
        Mockito.when(session.activeSession(uuid)).thenReturn(Optional.empty());
        InventoryClickEvent event = click(4, false);

        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
    }

    @Test
    void dragTouchingBankTopIsCancelled() {
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(4, SIZE + 1));

        listener.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void bottomOnlyDragPassesThrough() {
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(SIZE + 1, SIZE + 2));

        listener.onDrag(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
    }

    @Test
    void cancelSetterFailureNeverDispatches() {
        // If the Bukkit cancellation setter itself throws, the event may still
        // be uncancelled, so no bank business may dispatch from it.
        Mockito.when(session.actionForSlot(4)).thenReturn(BankGuiAction.deposit());
        Mockito.when(session.actionForSlot(11)).thenReturn(BankGuiAction.withdraw(100L));
        Mockito.when(session.actionForSlot(15)).thenReturn(BankGuiAction.close());
        ItemStack held = Mockito.mock(ItemStack.class);
        Mockito.when(playerInv.getItemInMainHand()).thenReturn(held);

        for (int slot : new int[]{4, 11, 15}) {
            InventoryClickEvent event = click(slot, false);
            Mockito.doThrow(new RuntimeException("cancel boom"))
                    .when(event).setCancelled(true);

            listener.onClick(event);

            verify(session, never()).handleClickAsync(
                    Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
            verify(session, never()).handleClickAsync(
                    Mockito.any(), Mockito.anyLong(), Mockito.anyInt());
        }
    }

    @Test
    void closeDropsTheBankSession() {
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(session.close(uuid, GENERATION))
                .thenReturn(V2BankGuiSession.CloseOutcome.closed());

        listener.onClose(event);

        verify(session).close(uuid, GENERATION);
        verify(session).noteViewClosed(uuid, GENERATION);
    }

    @Test
    void closeOnNonBankViewIsIgnored() {
        Mockito.when(view.getTitle()).thenReturn("Some other shop");
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);

        listener.onClose(event);

        verify(session, never()).close(Mockito.any(), Mockito.anyLong());
        verify(session, never()).noteViewClosed(Mockito.any(), Mockito.anyLong());
    }
}
