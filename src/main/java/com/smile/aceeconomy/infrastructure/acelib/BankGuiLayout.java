package com.smile.aceeconomy.infrastructure.acelib;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable bank GUI layout resolved from the {@code bank-gui} config section.
 *
 * <p>The layout carries the inventory title language key, the inventory size,
 * and the per-action button definitions (slot, action type, withdraw amount /
 * currency, display material, name / lore language keys). Display text is always
 * referenced by language key, never by raw MiniMessage, so operator input is
 * rendered through the safe component pipeline and never re-parsed.</p>
 *
 * <p>Per-slot item rendering additionally depends on the GUI backend: the
 * AceLib GUI surface only accepts title / size / protected slots when opening
 * an inventory, so material / name / lore entries are validated at startup and
 * retained in this model for documentation and future rendering support. Slot
 * to action resolution, title and size are applied today.</p>
 */
public final class BankGuiLayout {

    public enum ActionType { DEPOSIT, WITHDRAW, CLOSE, NONE }

    /**
     * One configured button. {@code amount} is only meaningful for
     * {@link ActionType#WITHDRAW} (positive); {@code currencyId} is the
     * normalized currency id or {@code null} for the runtime default currency.
     */
    public static final class SlotConfig {
        private final String name;
        private final int slot;
        private final ActionType type;
        private final long amount;
        private final String currencyId;
        private final String material;
        private final String nameKey;
        private final List<String> loreKeys;

        public SlotConfig(String name, int slot, ActionType type, long amount,
                          String currencyId, String material,
                          String nameKey, List<String> loreKeys) {
            this.name = Objects.requireNonNull(name, "name");
            this.slot = slot;
            this.type = Objects.requireNonNull(type, "type");
            this.amount = amount;
            this.currencyId = currencyId;
            this.material = material == null ? "" : material;
            this.nameKey = nameKey == null ? "" : nameKey;
            this.loreKeys = loreKeys == null ? List.of() : List.copyOf(loreKeys);
        }

        public String name() {
            return name;
        }

        public int slot() {
            return slot;
        }

        public ActionType type() {
            return type;
        }

        public long amount() {
            return amount;
        }

        /** Normalized currency id, or {@code null} for the runtime default. */
        public String currencyId() {
            return currencyId;
        }

        public String material() {
            return material;
        }

        public String nameKey() {
            return nameKey;
        }

        public List<String> loreKeys() {
            return loreKeys;
        }
    }

    private final boolean enabled;
    private final String titleKey;
    private final int size;
    private final Map<String, SlotConfig> actionsByName;
    private final Map<Integer, SlotConfig> actionsBySlot;

    private BankGuiLayout(boolean enabled, String titleKey, int size,
                          Map<String, SlotConfig> actionsByName) {
        this.enabled = enabled;
        this.titleKey = titleKey;
        this.size = size;
        this.actionsByName = Collections.unmodifiableMap(new LinkedHashMap<>(actionsByName));
        Map<Integer, SlotConfig> bySlot = new LinkedHashMap<>();
        for (SlotConfig slot : actionsByName.values()) {
            if (bySlot.put(slot.slot(), slot) != null) {
                throw new IllegalArgumentException(
                        "bank-gui.actions." + slot.name() + ".slot duplicates slot " + slot.slot());
            }
        }
        this.actionsBySlot = Collections.unmodifiableMap(bySlot);
    }

    /**
     * Build a layout from already-parsed entries. Structural invariants (size,
     * slot range, per-type amount rules, display-key presence) are enforced
     * here; currency membership and material legality are enforced by
     * {@link BankGuiConfigParser} which owns config-path diagnostics.
     */
    public static BankGuiLayout of(boolean enabled, String titleKey, int size,
                                   Map<String, SlotConfig> actions) {
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(actions, "actions");
        if (titleKey.isBlank()) {
            throw new IllegalArgumentException("bank-gui.title-key must be a non-blank language key");
        }
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException(
                    "bank-gui.size must be one of 9, 18, 27, 36, 45, 54");
        }
        if (actions.isEmpty()) {
            throw new IllegalArgumentException(
                    "bank-gui.actions must define at least one action");
        }
        for (Map.Entry<String, SlotConfig> entry : actions.entrySet()) {
            SlotConfig slot = entry.getValue();
            if (slot.slot() < 0 || slot.slot() >= size) {
                throw new IllegalArgumentException("bank-gui.actions." + entry.getKey()
                        + ".slot must be between 0 and " + (size - 1));
            }
            if (slot.type() == ActionType.WITHDRAW && slot.amount() <= 0) {
                throw new IllegalArgumentException(
                        "bank-gui.actions." + entry.getKey() + ".amount must be a positive integer");
            }
            if (slot.type() != ActionType.WITHDRAW && slot.amount() != 0) {
                throw new IllegalArgumentException("bank-gui.actions." + entry.getKey()
                        + ".amount must not be set for type " + slot.type().name().toLowerCase());
            }
            if (slot.type() != ActionType.NONE) {
                if (slot.material().isBlank()) {
                    throw new IllegalArgumentException("bank-gui.actions." + entry.getKey()
                            + ".material must be a non-blank material name");
                }
                if (slot.nameKey().isBlank()) {
                    throw new IllegalArgumentException("bank-gui.actions." + entry.getKey()
                            + ".name-key must be a non-blank language key");
                }
            }
        }
        return new BankGuiLayout(enabled, titleKey, size, actions);
    }

    public boolean enabled() {
        return enabled;
    }

    public String titleKey() {
        return titleKey;
    }

    public int size() {
        return size;
    }

    public Map<String, SlotConfig> actions() {
        return actionsByName;
    }

    public Optional<SlotConfig> actionForSlot(int slot) {
        return Optional.ofNullable(actionsBySlot.get(slot));
    }

    /** Every configured button slot; callers protect these from player interaction. */
    public Set<Integer> protectedSlots() {
        return actionsBySlot.keySet();
    }
}
