package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiSession;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared offline seams for the declarative bank GUI tests. The scope stub
 * below is a faithful miniature of the upstream contract: every open and
 * replace mints a fresh generation, {@code viewOf} resolves only while a
 * view is tracked, and close drops it. Stale generations therefore read as
 * stale exactly like production, without a live server.
 *
 * <p>Public so bootstrap lifecycle tests can drive the production stop and
 * reload seams with the same miniature.
 */
public final class BankGuiTestFixtures {

    private BankGuiTestFixtures() {
    }

    /** Operator default layout: deposit on 4, withdraw 100/500 on 11/13, close on 15, size 27. */
    public static BankGuiLayout defaultLayout() {
        return BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
    }

    public static ConfigLangAdapter messages() {
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> "Bank");
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> Component.text("msg:" + invocation.getArgument(0)));
        return messages;
    }

    public static Material solidMaterial() {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(false);
        return material;
    }

    /** Renderer that builds mock icons without Bukkit registries. */
    public static BankGuiRenderer renderer() {
        return new BankGuiRenderer(material -> Mockito.mock(ItemStack.class),
                name -> solidMaterial());
    }

    public static Player player() {
        Player player = Mockito.mock(Player.class);
        PlayerInventory inventory = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(player.getInventory()).thenReturn(inventory);
        Mockito.when(inventory.firstEmpty()).thenReturn(0);
        Mockito.when(inventory.addItem(Mockito.any(ItemStack.class)))
                .thenReturn(new HashMap<>());
        return player;
    }

    /** Main-hand banknote whose click-time clone is wired: {@code held.clone()} yields an
     * independent snapshot that {@code held} reports as similar. */
    public static ItemStack heldBanknote(int amount) {
        Material heldType = solidMaterial();
        Material snapshotType = solidMaterial();
        ItemStack held = Mockito.mock(ItemStack.class);
        ItemStack snapshot = Mockito.mock(ItemStack.class);
        Mockito.when(held.getType()).thenReturn(heldType);
        Mockito.when(held.getAmount()).thenReturn(amount);
        Mockito.when(snapshot.getType()).thenReturn(snapshotType);
        Mockito.when(snapshot.getAmount()).thenReturn(amount);
        Mockito.when(held.clone()).thenReturn(snapshot);
        Mockito.when(held.isSimilar(snapshot)).thenReturn(true);
        return held;
    }

    /** A mock scope that mints generations and tracks views like the real one. */
    public static GuiScope scope() {
        return scope(new AtomicLong(100L));
    }

    public static GuiScope scope(AtomicLong generations) {
        GuiScope scope = Mockito.mock(GuiScope.class);
        Map<UUID, GuiView> views = new ConcurrentHashMap<>();
        Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
        Mockito.when(scope.openView(Mockito.any(UUID.class), Mockito.any(GuiView.class)))
                .thenAnswer(invocation -> {
                    UUID uuid = invocation.getArgument(0);
                    GuiView view = invocation.getArgument(1);
                    GuiSession session = new GuiSession(uuid, generations.getAndIncrement(),
                            "bank-test", view.title(), view.size(), Set.of());
                    views.put(uuid, view);
                    sessions.put(uuid, session);
                    return GuiResult.success(session);
                });
        Mockito.when(scope.replaceView(Mockito.any(UUID.class), Mockito.any(GuiView.class)))
                .thenAnswer(invocation -> {
                    UUID uuid = invocation.getArgument(0);
                    GuiView view = invocation.getArgument(1);
                    if (!sessions.containsKey(uuid)) {
                        return GuiResult.rejected("no-session", "no active session");
                    }
                    GuiSession session = new GuiSession(uuid, generations.getAndIncrement(),
                            "bank-test", view.title(), view.size(), Set.of());
                    views.put(uuid, view);
                    sessions.put(uuid, session);
                    return GuiResult.success(session);
                });
        Mockito.when(scope.viewOf(Mockito.any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(views.get(invocation.getArgument(0))));
        Mockito.when(scope.close(Mockito.any(UUID.class))).thenAnswer(invocation -> {
            UUID uuid = invocation.getArgument(0);
            GuiSession removed = sessions.remove(uuid);
            views.remove(uuid);
            if (removed == null) {
                return GuiResult.rejected("no-session", "no active session");
            }
            return GuiResult.success(removed);
        });
        return scope;
    }
}
