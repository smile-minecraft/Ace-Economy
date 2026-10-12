package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiButtonClick;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Assembles one bank {@link GuiView} from the operator-owned
 * {@link BankGuiLayout}. Every configured button is fully built up front:
 * display text is rendered and the button item is constructed before the
 * view is returned. A single failure here refuses the whole view, so a view
 * that failed assembly never reaches the upstream scope.
 *
 * <p>This guarantee ends at assembly. When the view opens, the upstream
 * scope copies each button item onto the player inventory. If the copy or
 * the placement fails for one slot, the upstream scope skips only that slot
 * and the view still opens without that icon. Such a failure never runs a
 * click handler.
 *
 * <p>The returned view denies everything by default: no slot is registered
 * as an allowed slot, so the upstream scope rejects every click that does
 * not land on a declared button. Button items are handed to the builder by
 * reference; the upstream scope copies them onto the player region thread
 * when the view opens, so one declaration serves every player independently.
 */
public final class BankGuiViewFactory {

    private BankGuiViewFactory() {
    }

    /**
     * One button that could not be built. {@code reason} names the cause
     * (message rendering or item construction) together with the offending
     * slot, so the refusal can be diagnosed from the server log.
     */
    public record SlotFailure(int slot, @NotNull String reason) {
        public SlotFailure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The view cannot be assembled: at least one configured button failed
     * to build. The caller must refuse the open and tell the player with an
     * explicit error instead of showing a broken interface.
     */
    public static final class ViewBuildException extends Exception {
        private final List<SlotFailure> failures;

        public ViewBuildException(@NotNull List<SlotFailure> failures) {
            super("bank view build failed: " + failures);
            this.failures = List.copyOf(failures);
        }

        /** Every button that failed, in layout order. Never empty. */
        public @NotNull List<SlotFailure> failures() {
            return failures;
        }
    }

    /**
     * Supplies the click handler for one configured action. Called once per
     * planned slot during assembly, on the assembling thread; the returned
     * consumer itself runs later on the player region thread when the
     * upstream scope dispatches the click.
     */
    public interface ClickHandlers {
        @NotNull Consumer<GuiButtonClick> forAction(@NotNull BankGuiAction action, int slot);
    }

    /**
     * Build the view for {@code title} from {@code layout}.
     *
     * @param title already-rendered inventory title; never blank
     * @param layout validated operator layout; supplies size and buttons
     * @param messages renders button display text through the safe pipeline
     * @param renderer constructs button items; a {@code null} item or a
     *     thrown exception fails that slot
     * @param handlers click handler per configured action; never null
     * @return a complete view with one button per configured slot
     * @throws ViewBuildException when any button fails; the view is unusable
     */
    public static @NotNull GuiView build(@NotNull String title,
                                         @NotNull BankGuiLayout layout,
                                         @NotNull ConfigLangAdapter messages,
                                         @NotNull BankGuiRenderer renderer,
                                         @NotNull ClickHandlers handlers)
            throws ViewBuildException {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(handlers, "handlers");

        Map<Integer, BankGuiLayout.SlotConfig> planned = renderer.plan(layout);
        List<BankGuiLayout.SlotConfig> ordered = new ArrayList<>(layout.actions().values().stream()
                .filter(slot -> planned.containsKey(slot.slot()))
                .toList());
        java.util.function.Function<Integer, BankGuiAction> resolver =
                BankGuiActions.resolver(layout);

        GuiView.Builder builder = GuiView.chest(title, layout.size());
        List<SlotFailure> failures = new ArrayList<>();
        Map<Integer, ItemStack> icons = new LinkedHashMap<>();
        Map<Integer, BankGuiAction> actions = new LinkedHashMap<>();
        for (BankGuiLayout.SlotConfig slot : ordered) {
            Component name;
            List<Component> lore;
            try {
                Map<String, Object> vars = displayVars(slot);
                name = messages.renderMessage(slot.nameKey(), vars);
                List<Component> lines = new ArrayList<>(slot.loreKeys().size());
                for (String loreKey : slot.loreKeys()) {
                    lines.add(messages.renderMessage(loreKey, vars));
                }
                lore = List.copyOf(lines);
            } catch (Exception failure) {
                failures.add(new SlotFailure(slot.slot(),
                        "message render failed for '" + slot.nameKey() + "': "
                                + failure.getClass().getSimpleName()));
                continue;
            }
            ItemStack icon = renderer.buildButton(slot, name, lore);
            if (icon == null) {
                failures.add(new SlotFailure(slot.slot(),
                        "button item failed for material '" + slot.material() + "'"));
                continue;
            }
            icons.put(slot.slot(), icon);
            BankGuiAction action = resolver.apply(slot.slot());
            actions.put(slot.slot(), action == null ? BankGuiAction.none() : action);
        }
        if (!failures.isEmpty()) {
            throw new ViewBuildException(failures);
        }
        for (Map.Entry<Integer, ItemStack> entry : icons.entrySet()) {
            int slot = entry.getKey();
            BankGuiAction action = actions.get(slot);
            Consumer<GuiButtonClick> handler;
            try {
                handler = handlers.forAction(action, slot);
            } catch (Exception failure) {
                throw new ViewBuildException(List.of(new SlotFailure(slot,
                        "click handler failed: " + failure.getClass().getSimpleName())));
            }
            if (handler == null) {
                throw new ViewBuildException(List.of(new SlotFailure(slot,
                        "click handler missing")));
            }
            builder.button(slot, buttonId(action, slot), entry.getValue(), handler);
        }
        return builder.build();
    }

    /** Stable per-slot button id: unique within the view, derived from action and slot. */
    private static @NotNull String buttonId(@NotNull BankGuiAction action, int slot) {
        return "bank-" + action.type().name().toLowerCase() + "-" + slot;
    }

    /**
     * Button display vars: a withdraw slot exposes its configured amount as
     * the {@code {amount}} template var so the locale lore can never drift
     * from the actual withdraw amount; other action types render without
     * vars. Amounts come from the validated layout, never from player input.
     */
    private static @NotNull Map<String, Object> displayVars(@NotNull BankGuiLayout.SlotConfig slot) {
        if (slot.type() == BankGuiLayout.ActionType.WITHDRAW) {
            return Map.of("amount", Long.toString(slot.amount()));
        }
        return Map.of();
    }
}
