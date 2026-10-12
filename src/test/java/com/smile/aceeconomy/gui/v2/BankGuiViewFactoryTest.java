package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiButtonClick;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declarative bank view must arrive complete: one button per configured
 * slot, every icon built, nothing allowed by default. Any single build
 * failure refuses the whole view before the scope call, so assembly never
 * hands an incomplete view to the upstream scope.
 */
class BankGuiViewFactoryTest {

    private static BankGuiViewFactory.ClickHandlers handlers() {
        return (action, slot) -> click -> { };
    }

    @Test
    void defaultLayoutBuildsOneButtonPerActionSlot() throws Exception {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        GuiView view = BankGuiViewFactory.build("Bank", layout,
                BankGuiTestFixtures.messages(), BankGuiTestFixtures.renderer(), handlers());

        assertEquals("Bank", view.title());
        assertEquals(27, view.size());
        Set<Integer> actionSlots = new HashSet<>(layout.actionSlots());
        assertEquals(actionSlots, view.buttons().keySet(),
                "every configured action slot must carry exactly one button");
        assertEquals(actionSlots, view.buttonIcons().keySet(),
                "every configured action slot must carry a built icon");
        assertTrue(view.allowedSlots().isEmpty(),
                "nothing is allowed by default; upstream rejects every non-button click");
        assertEquals(view.buttons().size(), view.handlers().size(),
                "every button id must resolve to exactly one handler");
        Set<String> ids = new HashSet<>();
        for (var button : view.buttons().values()) {
            assertTrue(ids.add(button.id()), "button ids must be unique within the view: " + button);
            assertTrue(view.handlers().containsKey(button.id()),
                    "button " + button.id() + " must have a handler");
        }
    }

    @Test
    void withdrawLoreRendersConfiguredAmount() throws Exception {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> Component.text("msg"));
        GuiView view = BankGuiViewFactory.build("Bank", layout,
                messages, BankGuiTestFixtures.renderer(), handlers());

        assertTrue(view.buttons().containsKey(11), "withdraw slot 11 must be a button");
        assertTrue(view.buttons().containsKey(13), "withdraw slot 13 must be a button");
        Mockito.verify(messages).renderMessage(Mockito.eq("gui.bank-withdraw-name"),
                Mockito.eq(Map.of("amount", "100")));
        Mockito.verify(messages).renderMessage(Mockito.eq("gui.bank-withdraw-name"),
                Mockito.eq(Map.of("amount", "500")));
    }

    @Test
    void unresolvableMaterialRefusesTheWholeView() {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        BankGuiRenderer broken = new BankGuiRenderer(
                material -> Mockito.mock(ItemStack.class), name -> null);

        BankGuiViewFactory.ViewBuildException failure = assertThrows(
                BankGuiViewFactory.ViewBuildException.class,
                () -> BankGuiViewFactory.build("Bank", layout,
                        BankGuiTestFixtures.messages(), broken, handlers()));
        assertTrue(!failure.failures().isEmpty(), "at least one slot must be reported");
        for (var slotFailure : failure.failures()) {
            assertTrue(layout.actionSlots().contains(slotFailure.slot()),
                    "reported slot must be a configured action slot: " + slotFailure);
        }
    }

    @Test
    void throwingItemFactoryRefusesTheWholeView() {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        BankGuiRenderer broken = new BankGuiRenderer(
                material -> {
                    throw new IllegalStateException("probe-boom");
                },
                name -> BankGuiTestFixtures.solidMaterial());

        assertThrows(BankGuiViewFactory.ViewBuildException.class,
                () -> BankGuiViewFactory.build("Bank", layout,
                        BankGuiTestFixtures.messages(), broken, handlers()));
    }

    @Test
    void messageRenderFailureRefusesTheWholeView() {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new IllegalStateException("probe-boom"));

        assertThrows(BankGuiViewFactory.ViewBuildException.class,
                () -> BankGuiViewFactory.build("Bank", layout,
                        messages, BankGuiTestFixtures.renderer(), handlers()));
    }

    @Test
    void missingHandlerRefusesTheWholeView() {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();

        assertThrows(BankGuiViewFactory.ViewBuildException.class,
                () -> BankGuiViewFactory.build("Bank", layout,
                        BankGuiTestFixtures.messages(), BankGuiTestFixtures.renderer(),
                        (action, slot) -> null));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void handlerReceivesClicksPerSlot() throws Exception {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        Map<Integer, Consumer<GuiButtonClick>> bound = new java.util.HashMap<>();
        GuiView view = BankGuiViewFactory.build("Bank", layout,
                BankGuiTestFixtures.messages(), BankGuiTestFixtures.renderer(),
                (action, slot) -> {
                    Consumer<GuiButtonClick> handler = (Consumer<GuiButtonClick>) Mockito.mock(Consumer.class);
                    bound.put(slot, handler);
                    return handler;
                });

        assertEquals(layout.actionSlots().size(), bound.size(),
                "one handler must be bound per configured slot, got: " + bound.keySet());
        for (int slot : layout.actionSlots()) {
            String id = view.buttons().get(slot).id();
            assertTrue(view.handlers().get(id) == bound.get(slot),
                    "slot " + slot + " must dispatch to its own handler");
        }
    }

    @Test
    void metaFailureRefusesTheWholeView() throws Exception {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        Material material = BankGuiTestFixtures.solidMaterial();
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getItemMeta()).thenThrow(new IllegalStateException("probe-boom"));
        BankGuiRenderer renderer = new BankGuiRenderer(m -> stack, name -> material);

        // A button whose decoration cannot be written is not silently
        // degraded: the whole view is refused at assembly instead of handing
        // an incomplete view to the upstream scope.
        BankGuiViewFactory.ViewBuildException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        BankGuiViewFactory.ViewBuildException.class,
                        () -> BankGuiViewFactory.build("Bank", layout,
                                BankGuiTestFixtures.messages(), renderer, handlers()));
        assertTrue(!failure.failures().isEmpty());
    }

    @Test
    void itemMetaIsAppliedWhenAvailable() throws Exception {
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        ItemMeta meta = Mockito.mock(ItemMeta.class);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getItemMeta()).thenReturn(meta);
        BankGuiRenderer renderer = new BankGuiRenderer(
                m -> stack, name -> BankGuiTestFixtures.solidMaterial());

        BankGuiViewFactory.build("Bank", layout,
                BankGuiTestFixtures.messages(), renderer, handlers());
        Mockito.verify(meta, Mockito.atLeastOnce()).displayName(Mockito.any(Component.class));
        Mockito.verify(stack, Mockito.atLeastOnce()).setItemMeta(meta);
    }

    @Test
    void emptyPlanStillBuildsAnEmptyView() throws Exception {
        Map<String, BankGuiLayout.SlotConfig> actions = new java.util.LinkedHashMap<>();
        actions.put("idle", new BankGuiLayout.SlotConfig(
                "idle", 0, BankGuiLayout.ActionType.NONE, 0L, null,
                "", "", List.of()));
        BankGuiLayout layout = BankGuiLayout.of(true, "gui.bank-title", 9, actions);

        GuiView view = BankGuiViewFactory.build("Bank", layout,
                BankGuiTestFixtures.messages(), BankGuiTestFixtures.renderer(), handlers());
        assertTrue(view.buttons().isEmpty(), "NONE entries render nothing and bind nothing");
        assertTrue(view.allowedSlots().isEmpty());
    }
}
