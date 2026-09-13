package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Renders a validated {@link BankGuiLayout} into a bank top inventory. Display text is always
 * resolved through {@link ConfigLangAdapter#renderMessage} (safe component pipeline, never a
 * re-parse of operator raw MiniMessage); materials come from {@link Material#matchMaterial}
 * against the already-validated material names.
 *
 * <p>All Bukkit touches ({@code ItemStack} creation, meta writes, inventory writes) must happen
 * inside the player's region context. In production the caller ({@code V2BankGuiSession}) invokes
 * {@link #renderInto} from the AceLib async-update renderer, which AceLib guarantees to run on
 * the player region thread; the material resolver and item factory seams exist so offline tests
 * can drive the same mapping without Bukkit registries.
 */
public final class BankGuiRenderer {

    /**
     * Creates Bukkit item stacks. Production uses {@code ItemStack::new}; tests inject fakes.
     */
    public interface ItemFactory {
        @NotNull ItemStack create(@NotNull Material material);
    }

    private final ItemFactory itemFactory;
    private final Function<String, Material> materialResolver;

    public BankGuiRenderer(@NotNull ItemFactory itemFactory,
                           @NotNull Function<String, Material> materialResolver) {
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.materialResolver = Objects.requireNonNull(materialResolver, "materialResolver");
    }

    /** Production renderer: real item stacks with {@link Material#matchMaterial} lookup. */
    public static @NotNull BankGuiRenderer production() {
        return new BankGuiRenderer(ItemStack::new, Material::matchMaterial);
    }

    /**
     * Slots that carry a visible button. {@code NONE} entries resolve to no action and render
     * nothing, so they are skipped here exactly as they are in the action resolver.
     */
    public @NotNull Map<Integer, BankGuiLayout.SlotConfig> plan(@NotNull BankGuiLayout layout) {
        Objects.requireNonNull(layout, "layout");
        Map<Integer, BankGuiLayout.SlotConfig> planned = new LinkedHashMap<>();
        for (BankGuiLayout.SlotConfig slot : layout.actions().values()) {
            if (slot.type() == BankGuiLayout.ActionType.NONE) {
                continue;
            }
            planned.put(slot.slot(), slot);
        }
        return Map.copyOf(planned);
    }

    /**
     * Build one button stack, or {@code null} when the configured material no longer resolves
     * (the slot is left empty rather than failing the whole render).
     */
    public ItemStack buildButton(@NotNull BankGuiLayout.SlotConfig slot,
                                 @NotNull Component displayName,
                                 @NotNull List<Component> lore) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(lore, "lore");
        Material material;
        try {
            material = materialResolver.apply(slot.material());
        } catch (Exception e) {
            return null;
        }
        if (material == null || material.isAir()) {
            return null;
        }
        ItemStack stack;
        try {
            stack = itemFactory.create(material);
        } catch (Exception e) {
            return null;
        }
        if (stack == null) {
            return null;
        }
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.displayName(displayName);
                meta.lore(List.copyOf(lore));
                stack.setItemMeta(meta);
            }
        } catch (Exception e) {
            return null;
        }
        return stack;
    }

    /**
     * Write every planned button into the top inventory. Slots whose material no longer
     * resolves, or whose item construction fails, are skipped individually so one bad entry
     * cannot blank the whole GUI.
     *
     * @return the slots actually written, in layout order
     */
    public @NotNull Set<Integer> renderInto(@NotNull Inventory top,
                                            @NotNull BankGuiLayout layout,
                                            @NotNull ConfigLangAdapter messages) {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(messages, "messages");
        Map<Integer, BankGuiLayout.SlotConfig> planned = plan(layout);
        // Preserve layout definition order for deterministic writes.
        List<BankGuiLayout.SlotConfig> ordered = new ArrayList<>(layout.actions().values().stream()
                .filter(slot -> planned.containsKey(slot.slot()))
                .toList());
        Set<Integer> written = new java.util.LinkedHashSet<>();
        for (BankGuiLayout.SlotConfig slot : ordered) {
            Map<String, Object> vars = displayVars(slot);
            Component name;
            List<Component> lore;
            try {
                name = messages.renderMessage(slot.nameKey(), vars);
                List<Component> lines = new ArrayList<>(slot.loreKeys().size());
                for (String loreKey : slot.loreKeys()) {
                    lines.add(messages.renderMessage(loreKey, vars));
                }
                lore = List.copyOf(lines);
            } catch (Exception e) {
                continue;
            }
            ItemStack button = buildButton(slot, name, lore);
            if (button == null) {
                continue;
            }
            try {
                top.setItem(slot.slot(), button);
            } catch (Exception e) {
                continue;
            }
            written.add(slot.slot());
        }
        return Set.copyOf(written);
    }

    /**
     * Button display vars: a withdraw slot exposes its configured amount as the {@code {amount}}
     * template var so the locale lore can never drift from the actual withdraw amount; other
     * action types render without vars. Amounts come from the validated layout (operator config),
     * never from player input, and travel through the safe escaped-substitution pipeline.
     */
    private static Map<String, Object> displayVars(@NotNull BankGuiLayout.SlotConfig slot) {
        if (slot.type() == BankGuiLayout.ActionType.WITHDRAW) {
            return Map.of("amount", Long.toString(slot.amount()));
        }
        return Map.of();
    }
}
