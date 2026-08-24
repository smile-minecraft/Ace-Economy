package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds and registers the complete v2 command presentation surface. */
public final class V2CommandRegistry {

    private final List<CommandSpec> specs;

    private V2CommandRegistry(List<CommandSpec> specs) {
        this.specs = specs;
    }

    public static V2CommandRegistry create(CommandServices services) {
        return create(services, MainCommandAliasPolicy.DEFAULT_MAIN_ALIAS, true, List.of());
    }

    /**
     * Production factory: gates the baltop spec behind the leaderboard toggle and wires the
     * configured main-command alias onto the aceeco spec. Startup-only by contract — Bukkit
     * only routes labels declared in plugin.yml and AceLib bridges attach to those static
     * roots, so alias and enabled changes need a restart; reload never re-registers commands.
     *
     * @param configuredAlias raw {@code settings.main-command-alias} value (may be blank)
     * @param leaderboardEnabled {@code leaderboard.enabled}; false omits the baltop spec entirely
     * @param bukkitDeclaredLabels every root/alias label plugin.yml declares; collisions fail fast
     */
    public static V2CommandRegistry create(CommandServices services, String configuredAlias,
                                           boolean leaderboardEnabled,
                                           Collection<String> bukkitDeclaredLabels) {
        return create(services, configuredAlias, leaderboardEnabled, bukkitDeclaredLabels, Map.of());
    }

    /**
     * Production factory: gates the baltop spec behind the leaderboard toggle, wires the
     * configured main-command alias onto the aceeco spec, and merges plugin.yml-declared
     * aliases onto the matching specs. Startup-only by contract — Bukkit only routes labels
     * declared in plugin.yml and AceLib bridges attach to those static roots, so alias and
     * enabled changes need a restart; reload never re-registers commands.
     *
     * @param configuredAlias raw {@code settings.main-command-alias} value (may be blank)
     * @param leaderboardEnabled {@code leaderboard.enabled}; false omits the baltop spec entirely
     * @param bukkitDeclaredLabels every root/alias label plugin.yml declares; collisions fail fast
     * @param bukkitAliasesByRoot plugin.yml-declared aliases keyed by canonical root; merged onto
     *        the matching spec so Bukkit-routed alias labels dispatch to the canonical command
     */
    public static V2CommandRegistry create(CommandServices services, String configuredAlias,
                                           boolean leaderboardEnabled,
                                           Collection<String> bukkitDeclaredLabels,
                                           Map<String, ? extends Collection<String>> bukkitAliasesByRoot) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(bukkitDeclaredLabels, "bukkitDeclaredLabels");
        Objects.requireNonNull(bukkitAliasesByRoot, "bukkitAliasesByRoot");
        List<CommandSpec> specs = new ArrayList<>();
        specs.add(MoneyCommandSpec.create(services));
        specs.add(PayCommandSpec.create(services));
        specs.add(WithdrawCommandSpec.create(services));
        if (leaderboardEnabled) {
            specs.add(BaltopCommandSpec.create(services));
        }
        specs.add(BankCommandSpec.create(services));
        // Bukkit hands the typed alias (not the root name) to the executor as the dispatch
        // label, so every statically routed alias must exist in the AceLib registry too.
        List<CommandSpec> routable = new ArrayList<>(specs.stream()
                .map(spec -> withDeclaredAliases(spec, bukkitAliasesByRoot.get(spec.name())))
                .toList());

        Set<String> reserved = new LinkedHashSet<>(bukkitDeclaredLabels);
        for (CommandSpec spec : routable) {
            reserved.add(spec.name());
            reserved.addAll(spec.aliases());
        }
        String alias = MainCommandAliasPolicy.resolve(configuredAlias, reserved);
        // The default entry point is the aceeco spec's own primary name; repeating it as an
        // alias would be a no-op duplicate, so only a distinct label is attached.
        List<String> aceEcoAliases = alias.equals(MainCommandAliasPolicy.DEFAULT_MAIN_ALIAS)
                ? List.of()
                : List.of(alias);
        routable.add(AceEcoCommandSpec.create(services, aceEcoAliases));
        return new V2CommandRegistry(List.copyOf(routable));
    }

    /**
     * Rebuild {@code spec} with the plugin.yml-declared aliases for its root merged in.
     * Entries are normalized and deduplicated; blank entries and the spec's own name are
     * dropped. Returns the original instance when nothing new is added. An alias claimed by
     * two roots still fails fast at registration via the registry's conflict checks.
     */
    private static CommandSpec withDeclaredAliases(CommandSpec spec,
                                                   Collection<String> declaredAliases) {
        if (declaredAliases == null || declaredAliases.isEmpty()) {
            return spec;
        }
        Set<String> merged = new LinkedHashSet<>(spec.aliases());
        for (String raw : declaredAliases) {
            if (raw == null) {
                continue;
            }
            String alias = raw.trim().toLowerCase(Locale.ROOT);
            if (!alias.isEmpty() && !alias.equals(spec.name())) {
                merged.add(alias);
            }
        }
        if (merged.size() == spec.aliases().size()) {
            return spec;
        }
        CommandSpec.Builder builder = CommandSpec.builder(spec.name())
                .description(spec.description())
                .usage(spec.usage())
                .permission(spec.permission())
                .aliases(merged.toArray(new String[0]));
        spec.subCommands().values().forEach(builder::subCommand);
        return builder.build();
    }

    public List<CommandSpec> specs() {
        return specs;
    }

    public void register(CommandRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        specs.forEach(registry::register);
    }
}
