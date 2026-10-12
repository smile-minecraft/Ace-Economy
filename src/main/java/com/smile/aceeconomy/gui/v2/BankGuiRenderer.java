package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds the button items for a validated {@link BankGuiLayout}. Display text is always
 * resolved through {@link ConfigLangAdapter#renderMessage} (safe component pipeline, never a
 * re-parse of operator raw MiniMessage); materials come from {@link Material#matchMaterial}
 * against the already-validated material names.
 *
 * <p>Items are fully constructed during view assembly, before the scope is asked to open.
 * A button whose item cannot be built refuses the whole open, so no partly assembled view
 * is handed over. That guarantee ends at the handover. The upstream scope then copies each
 * item onto the player region thread when the view opens, so one declaration serves every
 * player independently. If a single slot's clone or setItem fails at that stage, only that
 * slot is skipped and the view still opens, so the player can see a missing button. The
 * material resolver and item factory seams exist so offline tests can drive the same
 * mapping without Bukkit registries.
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
}
