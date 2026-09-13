package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiSession;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Guard-lookup fail-closed regression: when any ownership query
 * ({@code isBoundView}, {@code isFailedView}, {@code isPendingView},
 * {@code hasExactGuard}/{@code hasPendingIntent}) or any slot/top read throws,
 * the listener must never dispatch and must cancel the dangerous event instead
 * of falling through to default handling. Query-success paths (bound dispatch,
 * plain bottom pass-through, foreign pass-through) are unchanged.
 */
class BankGuiGuardLookupFailClosedTest {

    private static final long GENERATION = 7L;
    private static final String TITLE = "Bank";
    private static final int SIZE = 27;

    private UUID uuid;
    private Player player;
    private Inventory top;
    private InventoryView view;
    private V2BankGuiSession session;
    private BankGuiClickListener listener;

    @BeforeEach
    void setUp() {
        uuid = UUID.randomUUID();
        player = Mockito.mock(Player.class);
        PlayerInventory playerInv = Mockito.mock(PlayerInventory.class);
        top = Mockito.mock(Inventory.class);
        view = Mockito.mock(InventoryView.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.getInventory()).thenReturn(playerInv);
        Mockito.when(top.getSize()).thenReturn(SIZE);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn(TITLE);

        session = Mockito.mock(V2BankGuiSession.class);
        Mockito.when(session.activeSession(uuid))
                .thenReturn(Optional.of(new GuiSession(uuid, GENERATION, "v2-bank", TITLE, SIZE, Set.of())));
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

    private InventoryClickEvent clickOn(Inventory topInventory, int rawSlot, boolean shift) {
        InventoryView eventView = Mockito.mock(InventoryView.class);
        Mockito.when(eventView.getTopInventory()).thenReturn(topInventory);
        Mockito.when(eventView.getTitle()).thenReturn(TITLE);
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(eventView);
        Mockito.when(event.getRawSlot()).thenReturn(rawSlot);
        Mockito.when(event.isShiftClick()).thenReturn(shift);
        return event;
    }

    private InventoryDragEvent drag(Set<Integer> rawSlots) {
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenReturn(rawSlots);
        return event;
    }

    private void bound(boolean bound) {
        Mockito.when(session.isBoundView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenReturn(bound);
    }

    private void verifyZeroDispatch() {
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.any());
        verify(session, never()).handleClickAsync(
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void boundLookupErrorCancelsTopClickWithoutDispatch() {
        Mockito.when(session.isBoundView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenThrow(new RuntimeException("bound lookup boom"));
        InventoryClickEvent event = click(4, false);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verifyZeroDispatch();
    }

    @Test
    void boundLookupErrorCancelsTopTouchingDrag() {
        Mockito.when(session.isBoundView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenThrow(new RuntimeException("bound lookup boom"));
        InventoryDragEvent event = drag(Set.of(4, SIZE + 1));

        listener.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void boundLookupErrorCancelsBottomShiftClick() {
        Mockito.when(session.isBoundView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenThrow(new RuntimeException("bound lookup boom"));
        InventoryClickEvent event = click(SIZE + 5, true);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verifyZeroDispatch();
    }

    @Test
    void failedLookupErrorCancelsDangerousClicksWithoutDispatch() {
        bound(false);
        Mockito.when(session.isFailedView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenThrow(new RuntimeException("failed lookup boom"));

        InventoryClickEvent topClick = click(4, false);
        listener.onClick(topClick);
        verify(topClick).setCancelled(true);

        InventoryClickEvent shiftClick = click(SIZE + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick).setCancelled(true);

        verifyZeroDispatch();
    }

    @Test
    void pendingLookupErrorCancelsDangerousClicksWithoutDispatch() {
        bound(false);
        Mockito.when(session.isPendingView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenThrow(new RuntimeException("pending lookup boom"));

        InventoryClickEvent topClick = click(4, false);
        listener.onClick(topClick);
        verify(topClick).setCancelled(true);

        InventoryClickEvent shiftClick = click(SIZE + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick).setCancelled(true);

        verifyZeroDispatch();
    }

    @Test
    void intentLookupErrorCancelsDangerousClicksWithoutDispatch() {
        bound(false);
        Mockito.when(session.hasPendingIntent(Mockito.eq(uuid), Mockito.eq(GENERATION)))
                .thenThrow(new RuntimeException("intent lookup boom"));

        InventoryClickEvent topClick = click(4, false);
        listener.onClick(topClick);
        verify(topClick).setCancelled(true);

        InventoryClickEvent shiftClick = click(SIZE + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick).setCancelled(true);

        InventoryDragEvent dragEvent = drag(Set.of(4));
        listener.onDrag(dragEvent);
        verify(dragEvent).setCancelled(true);

        verifyZeroDispatch();
    }

    @Test
    void exactGuardLookupErrorCancelsDangerousClicksWithoutDispatch() {
        bound(false);
        Mockito.when(session.hasExactGuard(Mockito.eq(uuid), Mockito.eq(GENERATION)))
                .thenThrow(new RuntimeException("exact-guard lookup boom"));

        InventoryClickEvent topClick = click(4, false);
        listener.onClick(topClick);
        verify(topClick).setCancelled(true);

        verifyZeroDispatch();
    }

    @Test
    void rawSlotReadErrorCancelsClickWithoutDispatch() {
        bound(true);
        InventoryClickEvent event = click(4, false);
        Mockito.when(event.getRawSlot()).thenThrow(new RuntimeException("slot read boom"));

        listener.onClick(event);

        verify(event).setCancelled(true);
        verifyZeroDispatch();
    }

    @Test
    void shiftReadErrorCancelsBottomClickWithoutDispatch() {
        bound(true);
        InventoryClickEvent event = click(SIZE + 5, false);
        Mockito.when(event.isShiftClick()).thenThrow(new RuntimeException("shift read boom"));

        listener.onClick(event);

        verify(event).setCancelled(true);
        verifyZeroDispatch();
    }

    @Test
    void rawSlotsReadErrorCancelsDrag() {
        bound(true);
        InventoryDragEvent event = drag(Set.of(SIZE + 1));
        Mockito.when(event.getRawSlots()).thenThrow(new RuntimeException("slots read boom"));

        listener.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void topSizeReadErrorCancelsClickWithoutDispatch() {
        bound(false);
        Mockito.when(session.isFailedView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenReturn(true);
        Mockito.when(top.getSize()).thenThrow(new RuntimeException("size read boom"));
        InventoryClickEvent event = click(4, false);

        listener.onClick(event);

        verify(event).setCancelled(true);
        verifyZeroDispatch();
    }

    @Test
    void closeLookupErrorNeverThrowsAndNeverCloses() {
        Mockito.when(session.isBoundView(
                        Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenThrow(new RuntimeException("bound lookup boom"));
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);

        listener.onClose(event);

        verify(session, never()).close(Mockito.any(), Mockito.anyLong());
    }

    @Test
    void plainBottomClickStillPassesWhenLookupsSucceed() {
        bound(false);
        InventoryClickEvent event = click(SIZE + 1, false);

        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        verifyZeroDispatch();
    }

    @Test
    void foreignSameTitleSizeStillPassesWhenLookupsSucceed() {
        bound(false);
        Inventory foreign = Mockito.mock(Inventory.class);
        Mockito.when(foreign.getSize()).thenReturn(SIZE);
        InventoryClickEvent event = clickOn(foreign, 4, false);

        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        verifyZeroDispatch();
    }
}
