package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.config.ConfigSchema;
import com.smile.acelib.config.ConfigVersion;
import com.smile.acelib.config.FieldSpec;

import java.util.List;

/**
 * v2 config schema (clean-slate, AceLib public API only).
 *
 * <p>Declares the v2 config contract: {@code version: "2.0"} plus the retained
 * product capabilities (debt policy, start balance, locales, Discord, storage,
 * leaderboard) expressed as nested paths with safe defaults. The schema is
 * intentionally independent of the v1 {@code config-version} surface; the v2
 * adapter never reads or migrates v1 config.</p>
 *
 * <p>The {@code currencies} section is deliberately NOT declared here: it is an
 * operator-owned dynamic map (any number of currencies, each with name/symbol/
 * scale/default). Fabricating pinned dollar/token entries would both block an
 * arbitrary valid map and silently revive a default currency the operator removed.
 * The section is validated as a whole at startup by {@link CurrencyConfigParser},
 * which fail-fasts on missing/empty/malformed input instead of applying defaults.</p>
 *
 * <p>Field defaults are applied by {@code ConfigManager.load()}/{@code reload()}
 * when a key is absent, so a partial on-disk config still yields a complete,
 * valid in-memory snapshot for every declared field.</p>
 *
 * <p>The {@code bank-gui} section is deliberately NOT declared here: it is an
 * operator-owned block whose scalar defaults ({@code enabled}, {@code title-key},
 * {@code size}) and legacy slot behaviour live in {@link BankGuiConfigParser}.
 * Declaring them as schema fields would fabricate a partial section for
 * pre-existing configs that never defined {@code bank-gui}. The section is
 * validated as a whole at startup by the parser, which fail-fasts on malformed
 * input instead of applying defaults; a config without {@code bank-gui} keeps
 * loading under schema 2.0 and receives the legacy slot behaviour.</p>
 */
public final class V2ConfigSchema {

    /** v2 config version, matching AceLib {@code ConfigManager.VERSION_KEY}. */
    public static final ConfigVersion V2 = new ConfigVersion(2, 0);

    private V2ConfigSchema() {
    }

    public static ConfigSchema build() {
        return new ConfigSchema(V2, List.of(
                // economy / debt policy (retained capability)
                new FieldSpec("economy.allow-negative-balance", true, false),
                new FieldSpec("economy.default-debt-limit", 0.0, false),
                // start balance (retained capability)
                new FieldSpec("start-balance", 1000.0, false),
                // settings / locale
                new FieldSpec("settings.locale", "zh_TW", false),
                new FieldSpec("settings.main-command-alias", "aceeco", false),
                // storage — backend selection and per-backend connection settings.
                // All values flow into PersistenceBackendFactory; defaults match the
                // v2.0.0 fresh-install contract (JSON) and the parser's hard-coded defaults.
                new FieldSpec("storage.type", "json", false),
                new FieldSpec("storage.sqlite.path", "data-v2.sqlite", false),
                new FieldSpec("storage.mysql.host", "localhost", false),
                new FieldSpec("storage.mysql.port", 3306, false),
                new FieldSpec("storage.mysql.database", "aceeconomy", false),
                new FieldSpec("storage.mysql.username", "root", false),
                new FieldSpec("storage.mysql.password", "", false),
                new FieldSpec("storage.mysql.pool-size", 10, false),
                new FieldSpec("storage.mysql.max-lifetime", 1_800_000, false),
                // discord
                new FieldSpec("discord.enabled", false, false),
                new FieldSpec("discord.webhook-url", "", false),
                // leaderboard — the enabled flag gates the executable baltop v2 spec at
                // startup; cache/page-size tune the query service. Startup-only wiring:
                // a restart is required for structural changes to take effect.
                new FieldSpec("leaderboard.enabled", true, false),
                new FieldSpec("leaderboard.cache-time-seconds", 300, false),
                new FieldSpec("leaderboard.page-size", 10, false)
        ));
    }
}
