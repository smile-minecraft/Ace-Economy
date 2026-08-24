package com.smile.aceeconomy.commands.v2;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Startup policy for {@code settings.main-command-alias}: normalizes the configured value,
 * falls back to the default entry point when blank, and rejects malformed labels or any
 * collision with an already-declared command label instead of silently overriding it.
 *
 * <p>Startup-only by contract: Bukkit routes command labels that plugin.yml declares, and
 * AceLib bridges attach executors to those static roots. The alias therefore resolves inside
 * the v2 command registry (validated against every declared label), and changing the value
 * requires a restart; reload never re-registers commands.</p>
 */
public final class MainCommandAliasPolicy {

    /** The shipped primary admin entry point; also the fallback alias value. */
    public static final String DEFAULT_MAIN_ALIAS = "aceeco";

    private static final Pattern VALID_LABEL = Pattern.compile("[a-z0-9_-]+");

    private MainCommandAliasPolicy() {
    }

    /**
     * Normalize and validate the configured alias against reserved labels.
     *
     * @throws IllegalArgumentException when the label is malformed or collides with a
     *         reserved label; the default entry point itself is always allowed because it
     *         is the alias target's own primary name, never an override.
     */
    public static String resolve(String configured, Collection<String> reservedLabels) {
        String alias = configured == null || configured.isBlank()
                ? DEFAULT_MAIN_ALIAS
                : configured.trim().toLowerCase(Locale.ROOT);
        if (!VALID_LABEL.matcher(alias).matches()) {
            throw new IllegalArgumentException(
                    "settings.main-command-alias must match [a-z0-9_-]: " + configured);
        }
        if (alias.equals(DEFAULT_MAIN_ALIAS)) {
            return alias;
        }
        for (String reserved : reservedLabels) {
            if (reserved != null && reserved.equalsIgnoreCase(alias)) {
                throw new IllegalArgumentException(
                        "settings.main-command-alias conflicts with an existing command label: "
                                + alias);
            }
        }
        return alias;
    }

    /**
     * Collect every label Bukkit will route for this plugin: root command names plus their
     * declared aliases, from the shape returned by
     * {@code JavaPlugin#getDescription().getCommands()} (aliases may be a list or a single
     * string). Tolerates null/empty input.
     */
    public static Set<String> declaredBukkitLabels(Map<String, Map<String, Object>> declaredCommands) {
        if (declaredCommands == null || declaredCommands.isEmpty()) {
            return Set.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : declaredCommands.entrySet()) {
            addLabel(labels, entry.getKey());
            Map<String, Object> description = entry.getValue();
            if (description == null) {
                continue;
            }
            Object aliases = description.get("aliases");
            if (aliases instanceof Collection<?> collection) {
                for (Object alias : collection) {
                    addLabel(labels, alias);
                }
            } else {
                addLabel(labels, aliases);
            }
        }
        return Set.copyOf(labels);
    }

    /**
     * Collect plugin.yml-declared aliases grouped by their canonical root label, from the
     * shape returned by {@code JavaPlugin#getDescription().getCommands()} (aliases may be a
     * list or a single string). Bukkit routes each declared alias to the root's own
     * PluginCommand while handing the typed alias to the executor as the dispatch label, so
     * the v2 registry merges these onto the matching spec to keep every routed label
     * resolvable. Roots and aliases are normalized (trim + lower-case); blank entries are
     * dropped. Tolerates null/empty input.
     */
    public static Map<String, Set<String>> declaredAliasesByRoot(
            Map<String, Map<String, Object>> declaredCommands) {
        if (declaredCommands == null || declaredCommands.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> byRoot = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : declaredCommands.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String root = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (root.isEmpty()) {
                continue;
            }
            Set<String> aliases = byRoot.computeIfAbsent(root, key -> new LinkedHashSet<>());
            Map<String, Object> description = entry.getValue();
            if (description == null) {
                continue;
            }
            Object rawAliases = description.get("aliases");
            if (rawAliases instanceof Collection<?> collection) {
                for (Object alias : collection) {
                    addLabel(aliases, alias);
                }
            } else {
                addLabel(aliases, rawAliases);
            }
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        byRoot.forEach((root, aliases) -> immutable.put(root, Set.copyOf(aliases)));
        return Map.copyOf(immutable);
    }

    private static void addLabel(Set<String> labels, Object raw) {
        if (!(raw instanceof String label)) {
            return;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            labels.add(normalized);
        }
    }
}
