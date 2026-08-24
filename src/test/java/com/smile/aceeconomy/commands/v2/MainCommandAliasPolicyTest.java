package com.smile.aceeconomy.commands.v2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for resolving {@code settings.main-command-alias} at startup.
 *
 * <p>The alias is normalized (trim + lower-case), blank falls back to the default
 * {@code aceeco} entry point, malformed labels are rejected, and any collision with a
 * reserved label (plugin.yml roots/aliases or sibling v2 command names/aliases) fails
 * fast instead of silently overriding an existing entry point.</p>
 */
class MainCommandAliasPolicyTest {

    /** Labels shaped like the shipped plugin.yml: six roots plus their declared aliases. */
    private static final Set<String> SHIPPED_PLUGIN_LABELS = Set.of(
            "money", "balance", "bal",
            "pay",
            "aceeco",
            "withdraw",
            "baltop", "balancetop", "top",
            "bank", "menu", "bankmenu");

    @Test
    @DisplayName("blank or missing value falls back to the default aceeco entry point")
    void blankFallsBackToDefault() {
        assertEquals("aceeco", MainCommandAliasPolicy.resolve(null, SHIPPED_PLUGIN_LABELS));
        assertEquals("aceeco", MainCommandAliasPolicy.resolve("", SHIPPED_PLUGIN_LABELS));
        assertEquals("aceeco", MainCommandAliasPolicy.resolve("   ", SHIPPED_PLUGIN_LABELS));
    }

    @Test
    @DisplayName("the alias is trimmed and case-folded")
    void normalizesAlias() {
        assertEquals("eco", MainCommandAliasPolicy.resolve("  ECO ", Set.of()));
        assertEquals("myeco", MainCommandAliasPolicy.resolve("MyEco", Set.of()));
    }

    @Test
    @DisplayName("a custom free label resolves and never overrides existing entries")
    void acceptsFreeCustomLabel() {
        assertEquals("eco", MainCommandAliasPolicy.resolve("eco", SHIPPED_PLUGIN_LABELS));
    }

    @Test
    @DisplayName("the default alias itself stays valid even though aceeco is a declared root")
    void defaultAliasIsNeverAConflict() {
        assertEquals("aceeco", MainCommandAliasPolicy.resolve("aceeco", SHIPPED_PLUGIN_LABELS));
    }

    @Test
    @DisplayName("collisions with plugin.yml roots/aliases are rejected")
    void rejectsPluginLabelCollisions() {
        for (String taken : List.of("bank", "money", "pay", "withdraw", "baltop",
                "balance", "bal", "balancetop", "top", "menu", "bankmenu")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MainCommandAliasPolicy.resolve(taken, SHIPPED_PLUGIN_LABELS),
                    "alias '" + taken + "' must be rejected");
        }
    }

    @Test
    @DisplayName("collisions with sibling v2 spec names/aliases are rejected")
    void rejectsSiblingSpecCollisions() {
        assertThrows(IllegalArgumentException.class,
                () -> MainCommandAliasPolicy.resolve("money", Set.of("money")));
        assertThrows(IllegalArgumentException.class,
                () -> MainCommandAliasPolicy.resolve("BALANCE", Set.of("balance")));
    }

    @Test
    @DisplayName("labels outside [a-z0-9_-] after normalization are rejected")
    void rejectsMalformedLabels() {
        for (String bad : List.of("bank menu", "金幣", "eco!", "e.c.o")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MainCommandAliasPolicy.resolve(bad, Set.of()),
                    "alias '" + bad + "' must be rejected");
        }
    }

    @Test
    @DisplayName("declaredBukkitLabels extracts roots and aliases from the plugin description map")
    void extractsDeclaredBukkitLabels() {
        Map<String, Map<String, Object>> declared = Map.of(
                "money", Map.of("aliases", List.of("balance", "bal")),
                "pay", Map.of(),
                "baltop", Map.of("aliases", "balancetop"), // single-string form must work too
                "bank", Map.of("aliases", List.of("menu", "bankmenu")));

        Set<String> labels = MainCommandAliasPolicy.declaredBukkitLabels(declared);

        assertTrue(labels.containsAll(Set.of(
                "money", "balance", "bal", "pay", "baltop", "balancetop", "bank", "menu", "bankmenu")),
                "roots and aliases must all be collected: " + labels);
    }

    @Test
    @DisplayName("declaredBukkitLabels tolerates null/empty input")
    void toleratesMissingDeclarationMap() {
        assertEquals(Set.of(), MainCommandAliasPolicy.declaredBukkitLabels(null));
        assertEquals(Set.of(), MainCommandAliasPolicy.declaredBukkitLabels(Map.of()));
    }

    @Test
    @DisplayName("declaredAliasesByRoot groups aliases under their canonical root label")
    void extractsDeclaredAliasesByRoot() {
        Map<String, Map<String, Object>> declared = Map.of(
                "money", Map.of("aliases", List.of("Balance", "bal")),
                "pay", Map.of(),
                "baltop", Map.of("aliases", "balancetop"), // single-string form must work too
                "bank", Map.of("aliases", List.of("menu", "bankmenu")));

        Map<String, Set<String>> byRoot = MainCommandAliasPolicy.declaredAliasesByRoot(declared);

        assertEquals(Set.of("balance", "bal"), byRoot.get("money"));
        assertEquals(Set.of(), byRoot.getOrDefault("pay", Set.of()));
        assertEquals(Set.of("balancetop"), byRoot.get("baltop"));
        assertEquals(Set.of("menu", "bankmenu"), byRoot.get("bank"));
        assertEquals(4, byRoot.size());
    }

    @Test
    @DisplayName("declaredAliasesByRoot tolerates null/empty input and blank entries")
    void declaredAliasesByRootToleratesMissingDeclarations() {
        assertEquals(Map.of(), MainCommandAliasPolicy.declaredAliasesByRoot(null));
        assertEquals(Map.of(), MainCommandAliasPolicy.declaredAliasesByRoot(Map.of()));
        assertEquals(Map.of("money", Set.of()),
                MainCommandAliasPolicy.declaredAliasesByRoot(
                        Map.of("money", Map.of("aliases", List.of("", "  ")))));
    }
}
