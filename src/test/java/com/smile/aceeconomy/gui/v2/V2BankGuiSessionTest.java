package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.ports.WithdrawResult;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link V2BankGuiSession} against the deterministic {@link FakeGuiService} and
 * {@link RecordingFoliaContext}. These lock the v2 bank GUI behaviour without a live server:
 *
 * <ul>
 *   <li>open → withdraw dispatches the inventory mutation through the Folia context (never a raw
 *       Bukkit call);</li>
 *   <li>a click carrying a stale generation is rejected before the business layer runs;</li>
 *   <li>open / close / reopen works, and close with a stale generation is rejected;</li>
 *   <li>an offline (unavailable) GUI module fails open deterministically;</li>
 *   <li>a full inventory and a use-case inventory-full outcome both surface as inventoryFull;</li>
 *   <li>async refresh rejects a stale generation.</li>
 * </ul>
 */
class V2BankGuiSessionTest {

    private static final Set<Integer> PROTECTED = Set.of();

    private Player mockPlayer(boolean inventoryHasSpace) {
        Player p = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        UUID uuid = UUID.randomUUID();
        Mockito.when(p.getUniqueId()).thenReturn(uuid);
        Mockito.when(p.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(inventoryHasSpace ? 0 : -1);
        Mockito.when(inv.addItem(Mockito.any(ItemStack.class))).thenReturn(new HashMap<Integer, ItemStack>());
        return p;
    }

    private Function<Integer, BankGuiAction> withdrawResolver(long amount) {
        return slot -> BankGuiAction.withdraw(amount);
    }

    @Test
    void openThenWithdrawDispatchesThroughFolia() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(open.success());
        long gen = open.session().generation();

        V2BankGuiSession.ClickOutcome click = session.handleClick(player.getUniqueId(), gen, 0);
        assertTrue(click.isSuccess(), "withdraw click should succeed");
        assertTrue(folia.playerCalled(), "player inventory mutation must go through the Folia context");
        assertEquals(100L, useCase.lastAmount);
    }

    @Test
    void staleGenerationClickRejected() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        V2BankGuiSession.ClickOutcome click = session.handleClick(player.getUniqueId(), gen + 1, 0);
        assertFalse(click.isSuccess());
        assertEquals("stale-generation", click.reason());
        assertEquals(0, useCase.withdrawCalls, "business layer must not run on a stale click");
    }

    @Test
    void fullInventoryYieldsInventoryFullOutcome() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(false);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        V2BankGuiSession.ClickOutcome click = session.handleClick(player.getUniqueId(), gen, 0);
        assertTrue(click.isInventoryFull(), "a full inventory must surface as inventoryFull");
    }

    @Test
    void useCaseInventoryFullPropagates() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        useCase.mode = StubBankGuiUseCase.Mode.INVENTORY_FULL;
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        V2BankGuiSession.ClickOutcome click = session.handleClick(player.getUniqueId(), gen, 0);
        assertTrue(click.isInventoryFull(), "use-case inventory-full must propagate");
    }

    @Test
    void openCloseReopen() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome first = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(first.success());
        long gen = first.session().generation();

        V2BankGuiSession.CloseOutcome close = session.close(player.getUniqueId(), gen);
        assertTrue(close.isClosed());

        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, PROTECTED);
        assertTrue(second.success(), "reopen must succeed after a clean close");
    }

    @Test
    void closeWithStaleGenerationRejected() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        V2BankGuiSession.CloseOutcome close = session.close(player.getUniqueId(), gen + 1);
        assertFalse(close.isClosed());
        assertEquals("stale-generation", close.errorCode());
    }

    @Test
    void offlineGuiOpenFails() {
        FakeGuiService gui = FakeGuiService.unavailable("gui module down");
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        assertFalse(open.success());
        assertEquals("gui-unavailable", open.errorCode());
    }

    @Test
    void refreshStaleGenerationRejected() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer(true);
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, PROTECTED);
        long gen = open.session().generation();

        V2BankGuiSession.RefreshOutcome ok = session.refresh(player.getUniqueId(), gen, 0);
        assertTrue(ok.isSuccess(), "refresh with the active generation must succeed");

        V2BankGuiSession.RefreshOutcome stale = session.refresh(player.getUniqueId(), gen + 1, 0);
        assertFalse(stale.isSuccess());
        assertEquals("stale-generation", stale.errorCode());
    }

    @Test
    void clickWithoutOpenRejected() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(gui, folia, useCase, withdrawResolver(100));

        V2BankGuiSession.ClickOutcome click = session.handleClick(UUID.randomUUID(), 1L, 0);
        assertFalse(click.isSuccess());
        assertEquals("no-session", click.reason());
    }
}
