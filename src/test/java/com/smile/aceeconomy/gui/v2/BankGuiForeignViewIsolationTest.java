package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiScopes;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Foreign inventory isolation and guard lookup fail-closed regression test.
 *
 * <p>Replaces and unifies the protections previously tested by the deleted
 * {@code BankGuiForeignViewTest} and {@code BankGuiGuardLookupFailClosedTest}:
 * <ul>
 *   <li>Same-title, same-size foreign inventories from other plugins (not linked
 *       through {@code GuiInventoryLink}) are untouched: clicks, drags and closes
 *       are never cancelled and never reach the bank business layer, and closing
 *       a foreign window never drops the player's bank session.</li>
 *   <li>When an inventory is linked but ownership or session validation fails
 *       (unknown session, unauthenticated player, or rejected dispatch), the listener
 *       fails closed: the dangerous event is cancelled ({@code setCancelled(true)})
 *       and zero business operations run.</li>
 *   <li>Upstream gap (AceLib 1.5.0 {@code GuiServiceImpl#handleClick}/
 *       {@code #handleDrag}): a {@code getRawSlot()}/{@code getRawSlots()} read
 *       that itself throws escapes before any {@code setCancelled} call, so the
 *       event is neither cancelled nor dispatched. The two
 *       {@code ...PropagatesWithoutCancel...} tests below lock that exact
 *       behaviour instead of claiming a fail-closed cancel that does not happen.
 *       No consumer-side seam exists for this path: clicks arrive through the
 *       shared upstream listener, which this plugin must not touch.</li>
 * </ul>
 */
class BankGuiForeignViewIsolationTest {

    private static final String TITLE = "Bank";
    private static final int SIZE = 27;

    private SafeScheduler scheduler;
    private GuiService guiService;
    private Listener listener;
    private Method onClickMethod;
    private Method onDragMethod;
    private Method onCloseMethod;

    private Method linkMethod;
    private Method clearLinkMethod;

    private StubBankGuiUseCase useCase;
    private V2BankGuiSession session;
    private Player player;
    private UUID uuid;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = Mockito.mock(SafeScheduler.class);
        guiService = GuiService.forProduction(scheduler);
        listener = guiService.getListener();

        onClickMethod = listener.getClass().getDeclaredMethod("onClick", InventoryClickEvent.class);
        onClickMethod.setAccessible(true);
        onDragMethod = listener.getClass().getDeclaredMethod("onDrag", InventoryDragEvent.class);
        onDragMethod.setAccessible(true);
        onCloseMethod = listener.getClass().getDeclaredMethod("onClose", InventoryCloseEvent.class);
        onCloseMethod.setAccessible(true);

        Class<?> linkClass = Class.forName("com.smile.acelib.gui.GuiInventoryLink");
        linkMethod = linkClass.getDeclaredMethod("link", Inventory.class, long.class);
        linkMethod.setAccessible(true);
        clearLinkMethod = linkClass.getDeclaredMethod("clear");
        clearLinkMethod.setAccessible(true);

        useCase = new StubBankGuiUseCase();
        GuiScope scope = BankGuiTestFixtures.scope();
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        session = new V2BankGuiSession(scope, new RecordingFoliaContext(), useCase, layout,
                BankGuiTestFixtures.messages(), null, BankGuiTestFixtures.renderer());

        player = BankGuiTestFixtures.player();
        uuid = player.getUniqueId();
        assertTrue(session.open(player).success(), "bank setup open must succeed");
    }

    @AfterEach
    void tearDown() throws Exception {
        clearLinkMethod.invoke(null);
    }

    private Inventory mockInventory(int size) {
        Inventory inv = Mockito.mock(Inventory.class);
        Mockito.when(inv.getSize()).thenReturn(size);
        return inv;
    }

    private InventoryView mockView(Inventory top, String title) {
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn(title);
        return view;
    }

    @Test
    void foreignTopClickIsNotCancelledAndNeverDispatched() throws Exception {
        Inventory foreignTop = mockInventory(SIZE);
        InventoryView foreignView = mockView(foreignTop, TITLE);

        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(foreignView);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);

        onClickMethod.invoke(listener, event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        assertEquals(0, useCase.withdrawCalls,
                "a click on another plugin's inventory must never reach the business layer");
        assertEquals(0, useCase.depositCalls);
        assertTrue(session.activeSession(uuid).isPresent(),
                "foreign click must not disturb the tracked bank session");
    }

    @Test
    void foreignDragIsNotCancelled() throws Exception {
        Inventory foreignTop = mockInventory(SIZE);
        InventoryView foreignView = mockView(foreignTop, TITLE);

        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(foreignView);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(11, SIZE + 1));

        onDragMethod.invoke(listener, event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
    }

    @Test
    void foreignCloseDoesNotDropTheBankSession() throws Exception {
        Inventory foreignTop = mockInventory(SIZE);
        InventoryView foreignView = mockView(foreignTop, TITLE);

        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(foreignView);

        onCloseMethod.invoke(listener, event);

        assertTrue(session.activeSession(uuid).isPresent(),
                "closing another plugin's GUI must never drop the bank session");
    }

    @Test
    void linkedViewWithoutSessionCancelsClickFailClosed() throws Exception {
        Inventory linkedTop = mockInventory(SIZE);
        linkMethod.invoke(null, linkedTop, 777L);
        InventoryView view = mockView(linkedTop, TITLE);

        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);

        onClickMethod.invoke(listener, event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls,
                "unauthenticated / unknown session click must never dispatch to business logic");
    }

    @Test
    void linkedViewWithoutSessionCancelsTopDragFailClosed() throws Exception {
        Inventory linkedTop = mockInventory(SIZE);
        linkMethod.invoke(null, linkedTop, 777L);
        InventoryView view = mockView(linkedTop, TITLE);

        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(11));

        onDragMethod.invoke(listener, event);

        verify(event).setCancelled(true);
    }

    @Test
    void clickWithThrowingRawSlotPropagatesWithoutCancelAndNeverDispatches() throws Exception {
        Inventory linkedTop = mockInventory(SIZE);
        linkMethod.invoke(null, linkedTop, 777L);
        InventoryView view = mockView(linkedTop, TITLE);

        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenThrow(new RuntimeException("raw slot read boom"));

        // Upstream gap: GuiServiceImpl.handleClick reads getRawSlot() before any
        // setCancelled call with no exception guard, so the read failure escapes
        // to the caller (Bukkit logs it) instead of cancelling fail-closed.
        assertThrows(Exception.class, () -> onClickMethod.invoke(listener, event));
        verify(event, never()).setCancelled(Mockito.anyBoolean());
        assertEquals(0, useCase.withdrawCalls, "throwing slot read must never dispatch to business logic");
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void dragWithThrowingRawSlotsPropagatesWithoutCancel() throws Exception {
        Inventory linkedTop = mockInventory(SIZE);
        linkMethod.invoke(null, linkedTop, 777L);
        InventoryView view = mockView(linkedTop, TITLE);

        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenThrow(new RuntimeException("raw slots read boom"));

        // Same upstream gap on the drag path: handleDrag reads getRawSlots()
        // before any setCancelled call with no exception guard.
        assertThrows(Exception.class, () -> onDragMethod.invoke(listener, event));
        verify(event, never()).setCancelled(Mockito.anyBoolean());
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void buttonHandlerExceptionDuringDispatchCancelsClickAndPreventsMoneyMovement() throws Exception {
        // Build view with a handler that throws when clicked
        Consumer<com.smile.acelib.gui.GuiButtonClick> throwingHandler = click -> {
            throw new RuntimeException("button handler boom");
        };

        com.smile.acelib.gui.GuiView failingView = BankGuiViewFactory.build(
                TITLE,
                BankGuiTestFixtures.defaultLayout(),
                BankGuiTestFixtures.messages(),
                BankGuiTestFixtures.renderer(),
                (action, slot) -> action.type() == BankGuiAction.Type.WITHDRAW
                        ? throwingHandler
                        : click -> { }
        );

        org.bukkit.plugin.java.JavaPlugin mockPlugin = Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class);
        Mockito.when(mockPlugin.getName()).thenReturn("isolation-handler-test");
        Player failingPlayer = BankGuiTestFixtures.player();
        UUID failingUuid = failingPlayer.getUniqueId();
        com.smile.acelib.scheduler.ScheduledTask scheduledTask = Mockito.mock(com.smile.acelib.scheduler.ScheduledTask.class);
        Mockito.when(scheduledTask.isCancelled()).thenReturn(false);
        Mockito.when(scheduler.runForPlayer(Mockito.any(Player.class), Mockito.any(Runnable.class)))
                .thenAnswer(inv -> {
                    Runnable r = inv.getArgument(1);
                    // We don't execute r immediately because r actually calls player.openInventory which needs bukkit/inventory mocks
                    return scheduledTask;
                });
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(failingUuid)).thenReturn(failingPlayer);
            GuiScope realScope = GuiScopes.create(mockPlugin, guiService);
            try {
                GuiResult openResult = realScope.openView(failingUuid, failingView);
                assertTrue(openResult.isSuccess(), "scope openView must succeed: " + openResult.detail());

                long generation = openResult.session().generation();
                Inventory topInv = mockInventory(SIZE);
                linkMethod.invoke(null, topInv, generation);
                InventoryView view = mockView(topInv, TITLE);

                InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
                Mockito.when(event.getWhoClicked()).thenReturn(failingPlayer);
                Mockito.when(event.getView()).thenReturn(view);
                Mockito.when(event.getRawSlot()).thenReturn(11);
                Mockito.when(event.isShiftClick()).thenReturn(false);

                onClickMethod.invoke(listener, event);

                verify(event).setCancelled(true);
                assertEquals(0, useCase.withdrawCalls, "handler exception must never move money");
                assertEquals(0, useCase.depositCalls);
            } finally {
                GuiScopes.close(mockPlugin);
            }
        }
    }
}
