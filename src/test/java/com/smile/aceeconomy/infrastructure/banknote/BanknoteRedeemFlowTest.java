package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.gui.v2.BanknoteRedeemListener;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import com.smile.acelib.item.ItemIdentity;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

/**
 * The right-click redeem entry must use the shared banknote pipeline: the
 * listener delegates to the flow instead of calling the use case inline, and
 * every reply runs on the region thread.
 */
class BanknoteRedeemFlowTest {

    static final class BothSeamFolia implements FoliaContextExecutor {
        int uuidDispatches;
        int playerDispatches;

        @Override
        public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
            playerDispatches++;
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
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            uuidDispatches++;
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return false;
            }
            action.accept(player);
            return true;
        }
    }

    static final class FakeBanknotes implements BanknoteFactory {
        Optional<BanknoteClaim> decodeResult = Optional.empty();

        @Override
        public @NotNull Optional<ItemStack> mint(@NotNull BanknoteClaim claim) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<BanknoteClaim> decode(@NotNull ItemStack stack) {
            return decodeResult;
        }
    }

    private MockedStatic<Bukkit> bukkit;
    private final Map<UUID, Player> online = new HashMap<>();

    private StubBankGuiUseCase useCase;
    private FakeBanknotes banknotes;
    private ConfigLangAdapter messages;
    private BothSeamFolia folia;
    private Player player;
    private PlayerInventory inv;
    private UUID playerId;
    private BanknoteClaim claim;
    private ItemStack air;

    @BeforeEach
    void setUp() {
        bukkit = Mockito.mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class)))
                .thenAnswer(invocation -> online.get(invocation.getArgument(0)));

        useCase = new StubBankGuiUseCase();
        banknotes = new FakeBanknotes();
        messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(anyString(), anyMap()))
                .thenAnswer(invocation -> Component.text("msg:" + invocation.getArgument(0)));
        folia = new BothSeamFolia();

        playerId = UUID.randomUUID();
        player = Mockito.mock(Player.class);
        inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        Mockito.when(player.isOnline()).thenReturn(true);
        Mockito.when(player.getInventory()).thenReturn(inv);
        online.put(playerId, player);

        claim = new BanknoteClaim(
                new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, 100L, UUID.randomUUID(), UUID.randomUUID(), "dollar");
        banknotes.decodeResult = Optional.of(claim);
        air = stack(true, 0);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private ItemStack stack(boolean isAir, int amount) {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(isAir);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(material);
        Mockito.when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private PlayerInteractEvent interact(EquipmentSlot hand, Action action) {
        PlayerInteractEvent event = Mockito.mock(PlayerInteractEvent.class);
        Mockito.when(event.getHand()).thenReturn(hand);
        Mockito.when(event.getAction()).thenReturn(action);
        Mockito.when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private BanknoteRedeemListener listenerWithFlow(BanknoteFlow flow) {
        return new BanknoteRedeemListener(useCase, banknotes, folia, messages,
                Logger.getLogger("BanknoteRedeemFlowTest"), flow);
    }

    private static void awaitMessage(ConfigLangAdapter messages, String key) {
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

    @Test
    void sharedFlowCreditsWithoutInlineUseCaseCall() {
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        BanknoteFlow flow = Mockito.mock(BanknoteFlow.class);
        Mockito.when(flow.redeem(any(), any(), any(), any())).thenAnswer(invocation -> {
            ItemStack snap = invocation.getArgument(1);
            assertEquals(snapshot, snap);
            return CompletableFuture.completedFuture(
                    BanknoteFlow.RedeemOutcome.credited(100L, "dollar"));
        });

        listenerWithFlow(flow).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));

        assertEquals(0, useCase.depositCalls,
                "the listener must delegate to the shared flow, never call the use case inline");
        awaitMessage(messages, "banknote.redeem-success");
    }

    @Test
    void sharedFlowMismatchKeepsItemWithMismatchReply() {
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        BanknoteFlow flow = Mockito.mock(BanknoteFlow.class);
        Mockito.when(flow.redeem(any(), any(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(BanknoteFlow.RedeemOutcome.itemMismatch()));

        listenerWithFlow(flow).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));

        awaitMessage(messages, "banknote.redeem-mismatch");
        Mockito.verify(inv, never()).setItemInMainHand(any());
    }

    @Test
    void sharedFlowReplayRepliesReplay() {
        ItemStack held = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(inv.getItemInMainHand()).thenReturn(held);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        BanknoteFlow flow = Mockito.mock(BanknoteFlow.class);
        Mockito.when(flow.redeem(any(), any(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        BanknoteFlow.RedeemOutcome.rejected("replay.detected")));

        listenerWithFlow(flow).onInteract(interact(EquipmentSlot.HAND, Action.RIGHT_CLICK_AIR));

        awaitMessage(messages, "banknote.redeem-replay");
        Mockito.verify(inv, never()).setItemInMainHand(any());
    }
}
