package com.smile.aceeconomy.infrastructure.acelib;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads the operator-owned {@code bank-gui} config section into a validated
 * {@link BankGuiLayout}.
 *
 * <p>The section is validated as a whole before any layout is constructed, so a
 * malformed config can never leave a partially applied presentation behind —
 * the caller receives either a complete layout or an exception. A missing
 * section (pre-existing config without {@code bank-gui}) falls back to the
 * legacy defaults: title key {@code gui.bank-title}, size 27, deposit on slot
 * 4, withdraw 100 on slot 11, withdraw 500 on slot 13, close on slot 15.</p>
 *
 * <p>Per-action contract:</p>
 * <ul>
 *   <li>{@code slot}: integer in {@code [0, size)}; duplicates are rejected.</li>
 *   <li>{@code type}: one of {@code deposit}, {@code withdraw}, {@code close},
 *       {@code none} (case-insensitive).</li>
 *   <li>{@code amount}: required positive integer for {@code withdraw}; must be
 *       absent (or zero) for every other type.</li>
 *   <li>{@code currency}: required for {@code withdraw} and must be a known
 *       currency id; a missing value means the runtime default currency. Must
 *       be absent for every other type.</li>
 *   <li>{@code material}: required legal Bukkit material name for every type
 *       except {@code none} (air is rejected).</li>
 *   <li>{@code name-key} / {@code lore-keys}: language keys for the button
 *       display, required / optional respectively, for every type except
 *       {@code none}.</li>
 * </ul>
 *
 * <p>Accepted input shapes: a Bukkit {@link ConfigurationSection} (what the
 * config adapter returns) or a plain {@code Map<String, Object>} (unit-test and
 * tooling seam). YAML integers surface as {@link Integer}/{@link Long}; quoted
 * numbers and fractional values are type errors, not coerced values.</p>
 */
public final class BankGuiConfigParser {

    private BankGuiConfigParser() {
    }

    /**
     * @param knownCurrencyIds normalized known currency ids, or {@code null} to
     *                         defer membership validation (format is still
     *                         enforced). The startup wiring always passes the
     *                         real registry ids; the config adapter passes
     *                         {@code null} only when the candidate currencies
     *                         section itself is unreadable (its own error is
     *                         reported separately at startup).
     */
    public static BankGuiLayout parse(Object rawBankGui, Set<String> knownCurrencyIds) {
        if (rawBankGui == null) {
            return defaults();
        }
        Map<String, Object> root = flatten(rawBankGui, "bank-gui");
        boolean enabled = enabledField(root);
        String titleKey = titleKeyField(root);
        int size = sizeField(root);
        Map<String, Object> actions = actionsField(root);
        Map<String, BankGuiLayout.SlotConfig> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : actions.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid bank-gui.actions keys: must be non-blank text");
            }
            parsed.put(name, parseAction(name, entry.getValue(), size, knownCurrencyIds));
        }
        return BankGuiLayout.of(enabled, titleKey, size, parsed);
    }

    /** Legacy defaults reproducing the pre-configurable slot behaviour. */
    static BankGuiLayout defaults() {
        Map<String, BankGuiLayout.SlotConfig> actions = new LinkedHashMap<>();
        actions.put("deposit", new BankGuiLayout.SlotConfig(
                "deposit", 4, BankGuiLayout.ActionType.DEPOSIT, 0L, null,
                "CHEST", "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
        actions.put("withdraw100", new BankGuiLayout.SlotConfig(
                "withdraw100", 11, BankGuiLayout.ActionType.WITHDRAW, 100L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("withdraw500", new BankGuiLayout.SlotConfig(
                "withdraw500", 13, BankGuiLayout.ActionType.WITHDRAW, 500L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close", new BankGuiLayout.SlotConfig(
                "close", 15, BankGuiLayout.ActionType.CLOSE, 0L, null,
                "BARRIER", "gui.bank-close-name", List.of()));
        return BankGuiLayout.of(true, "gui.bank-title", 27, actions);
    }

    private static BankGuiLayout.SlotConfig parseAction(String name, Object raw, int size,
                                                        Set<String> knownCurrencyIds) {
        String path = "bank-gui.actions." + name;
        Map<String, Object> fields = flatten(raw, path);
        int slot = slotField(fields, path, size);
        BankGuiLayout.ActionType type = typeField(fields, path);
        long amount = 0L;
        String currencyId = null;
        if (type == BankGuiLayout.ActionType.WITHDRAW) {
            amount = amountField(fields, path);
            currencyId = currencyField(fields, path, knownCurrencyIds);
        } else {
            rejectAmount(fields, path, type);
            rejectCurrency(fields, path, type);
        }
        String material = "";
        String nameKey = "";
        List<String> loreKeys = List.of();
        if (type != BankGuiLayout.ActionType.NONE) {
            material = materialField(fields, path);
            nameKey = nameKeyField(fields, path);
            loreKeys = loreKeysField(fields, path);
        }
        return new BankGuiLayout.SlotConfig(name, slot, type, amount, currencyId,
                material, nameKey, loreKeys);
    }

    private static boolean enabledField(Map<String, Object> root) {
        Object raw = root.get("enabled");
        if (raw == null) {
            return true;
        }
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException("invalid bank-gui.enabled: must be true or false");
        }
        return value;
    }

    private static String titleKeyField(Map<String, Object> root) {
        Object raw = root.get("title-key");
        if (raw == null) {
            return "gui.bank-title";
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid bank-gui.title-key: must be a non-blank language key");
        }
        return value.trim();
    }

    private static int sizeField(Map<String, Object> root) {
        Object raw = root.get("size");
        if (raw == null) {
            return 27;
        }
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(
                    "invalid bank-gui.size: must be one of 9, 18, 27, 36, 45, 54");
        }
        long size = ((Number) raw).longValue();
        if (size != 9 && size != 18 && size != 27 && size != 36 && size != 45 && size != 54) {
            throw new IllegalArgumentException(
                    "invalid bank-gui.size: must be one of 9, 18, 27, 36, 45, 54");
        }
        return (int) size;
    }

    private static Map<String, Object> actionsField(Map<String, Object> root) {
        Object raw = root.get("actions");
        if (raw == null) {
            throw new IllegalArgumentException(
                    "invalid bank-gui.actions: must define at least one action");
        }
        Map<String, Object> actions = flatten(raw, "bank-gui.actions");
        if (actions.isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid bank-gui.actions: must define at least one action");
        }
        return actions;
    }

    private static int slotField(Map<String, Object> fields, String path, int size) {
        Object raw = fields.get("slot");
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".slot: must be an integer between 0 and " + (size - 1));
        }
        long slot = ((Number) raw).longValue();
        if (slot < 0 || slot >= size) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".slot: must be an integer between 0 and " + (size - 1));
        }
        return (int) slot;
    }

    private static BankGuiLayout.ActionType typeField(Map<String, Object> fields, String path) {
        Object raw = fields.get("type");
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".type: must be one of deposit, withdraw, close, none");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "deposit" -> BankGuiLayout.ActionType.DEPOSIT;
            case "withdraw" -> BankGuiLayout.ActionType.WITHDRAW;
            case "close" -> BankGuiLayout.ActionType.CLOSE;
            case "none" -> BankGuiLayout.ActionType.NONE;
            default -> throw new IllegalArgumentException(
                    "invalid " + path + ".type: must be one of deposit, withdraw, close, none");
        };
    }

    private static long amountField(Map<String, Object> fields, String path) {
        Object raw = fields.get("amount");
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".amount: must be a positive integer");
        }
        long amount = ((Number) raw).longValue();
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".amount: must be a positive integer");
        }
        return amount;
    }

    private static void rejectAmount(Map<String, Object> fields, String path,
                                     BankGuiLayout.ActionType type) {
        Object raw = fields.get("amount");
        if (raw == null) {
            return;
        }
        if (raw instanceof Integer || raw instanceof Long) {
            if (((Number) raw).longValue() == 0) {
                return;
            }
        }
        throw new IllegalArgumentException("invalid " + path + ".amount: must not be set for type "
                + type.name().toLowerCase(Locale.ROOT));
    }

    private static String currencyField(Map<String, Object> fields, String path,
                                        Set<String> knownCurrencyIds) {
        Object raw = fields.get("currency");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".currency: must be a known currency id");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (knownCurrencyIds != null && !knownCurrencyIds.contains(normalized)) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".currency: unknown currency '" + normalized + "'");
        }
        return normalized;
    }

    private static void rejectCurrency(Map<String, Object> fields, String path,
                                       BankGuiLayout.ActionType type) {
        Object raw = fields.get("currency");
        if (raw == null) {
            return;
        }
        if (raw instanceof String value && value.isBlank()) {
            return;
        }
        throw new IllegalArgumentException("invalid " + path + ".currency: must not be set for type "
                + type.name().toLowerCase(Locale.ROOT));
    }

    private static String materialField(Map<String, Object> fields, String path) {
        Object raw = fields.get("material");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".material: must be a non-blank material name");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        Material material;
        try {
            material = Material.matchMaterial(normalized);
        } catch (Throwable failure) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".material: unknown material '" + normalized + "'");
        }
        // Name-based air check on purpose: Material.isAir() initializes the
        // Bukkit registry, which is unavailable in unit tests and unnecessary
        // here (a button made of air is never valid).
        if (material == null || material.name().equals("AIR")
                || material.name().endsWith("_AIR")) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".material: unknown material '" + normalized + "'");
        }
        return material.name();
    }

    private static String nameKeyField(Map<String, Object> fields, String path) {
        Object raw = fields.get("name-key");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".name-key: must be a non-blank language key");
        }
        return value.trim();
    }

    private static List<String> loreKeysField(Map<String, Object> fields, String path) {
        Object raw = fields.get("lore-keys");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "invalid " + path + ".lore-keys: must be a list of language keys");
        }
        for (Object entry : list) {
            if (!(entry instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException(
                        "invalid " + path + ".lore-keys: must be a list of language keys");
            }
        }
        return list.stream().map(entry -> ((String) entry).trim()).toList();
    }

    /** Normalize a section-or-map node into a plain ordered map of direct children. */
    static Map<String, Object> flatten(Object raw, String what) {
        if (raw instanceof ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                out.put(key, section.get(key));
            }
            return out;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("invalid " + what + " keys: must be text");
                }
                out.put(key, entry.getValue());
            }
            return out;
        }
        throw new IllegalArgumentException("invalid " + what + ": must be a mapping");
    }
}
