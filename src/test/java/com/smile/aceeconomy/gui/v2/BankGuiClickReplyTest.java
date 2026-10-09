package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiSession;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * The click listener must consume the async click outcome instead of
 * discarding it: every failure and pending state replies on the player's
 * region thread, while successes stay silent. Replies never run on the IO
 * thread that completes the click future.
 */
class BankGuiClickReplyTest {

    private static final long GENERATION = 7L;
    private static final int SIZE = 27;

    static final class RecordingFolia implements FoliaContextExecutor {
        int playerDispatches;
        Thread dispatchThread;

        @Override
        public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
            playerDispatches++;
            dispatchThread = Thread.currentThread();
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

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull java.util.function.Consumer<Player> action) {
            throw new UnsupportedOperationException();
        }
    }

    private UUID uuid;
    private Player player;
    private PlayerInventory playerInv;
    private Inventory top;
    private InventoryView view;
    private V2BankGuiSession session;
    private RecordingFolia folia;
    private ConfigLangAdapter messages;
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
        Mockito.when(view.getTitle()).thenReturn("Bank");

        session = Mockito.mock(V2BankGuiSession.class);
        GuiSession aceSession = new GuiSession(uuid, GENERATION, "v2-bank", "Bank", SIZE, Set.of());
        Mockito.when(session.activeSession(uuid)).thenReturn(Optional.of(aceSession));
        Mockito.when(session.isBoundView(Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.any()))
                .thenReturn(true);

        folia = new RecordingFolia();
        messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(anyString(), anyMap()))
                .thenAnswer(invocation -> Component.text("msg:" + invocation.getArgument(0)));
        listener = new BankGuiClickListener(session, folia, messages);
    }

    private InventoryClickEvent click(int rawSlot) {
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(rawSlot);
        Mockito.when(event.isShiftClick()).thenReturn(false);
        return event;
    }

    private void awaitKey(String key) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                Mockito.verify(messages, Mockito.timeout(50).times(1))
                        .renderMessage(eq(key), anyMap());
                return;
            } catch (Throwable t) {
                // Retry until the async reply arrives.
            }
        }
        Mockito.verify(messages).renderMessage(eq(key), anyMap());
    }

    private void dispatchWith(V2BankGuiSession.ClickOutcome outcome) {
        Mockito.when(session.actionForSlot(11)).thenReturn(BankGuiAction.withdraw(100L));
        Mockito.when(session.handleClickAsync(Mockito.eq(uuid), Mockito.eq(GENERATION), Mockito.anyInt()))
                .thenReturn(CompletableFuture.completedFuture(outcome));
        listener.onClick(click(11));
    }

    @Test
    void inventoryFullRepliesOnTheRegionThread() {
        Thread caller = Thread.currentThread();
        dispatchWith(V2BankGuiSession.ClickOutcome.inventoryFull());

        awaitKey("general.inventory-full");
        assertEquals(1, folia.playerDispatches, "the reply must go through the region thread");
    }

    @Test
    void insufficientFundsRepliesInsufficient() {
        dispatchWith(V2BankGuiSession.ClickOutcome.rejected("insufficient.funds"));

        awaitKey("economy.insufficient-funds");
        assertEquals(1, folia.playerDispatches);
    }

    @Test
    void replayRepliesReplay() {
        dispatchWith(V2BankGuiSession.ClickOutcome.rejected("replay.detected"));

        awaitKey("banknote.redeem-replay");
    }

    @Test
    void swappedItemRepliesMismatch() {
        dispatchWith(V2BankGuiSession.ClickOutcome.itemMismatch());

        awaitKey("banknote.redeem-mismatch");
    }

    @Test
    void retainedCreditRepliesRetained() {
        dispatchWith(V2BankGuiSession.ClickOutcome.creditRetained(100L));

        awaitKey("banknote.redeem-retained");
    }

    @Test
    void pendingWithdrawRepliesPending() {
        dispatchWith(V2BankGuiSession.ClickOutcome.pending("withdraw.indeterminate"));

        awaitKey("banknote.withdraw-pending");
    }

    @Test
    void successStaysSilent() {
        ItemStack note = Mockito.mock(ItemStack.class);
        dispatchWith(V2BankGuiSession.ClickOutcome.success(note));

        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Mockito.verify(messages, Mockito.never()).renderMessage(anyString(), anyMap());
        assertEquals(0, folia.playerDispatches, "successes stay silent with no region reply");
    }
}
