package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.config.ConfigSchema;
import com.smile.acelib.config.ConfigVersion;
import com.smile.acelib.config.FieldSpec;

import java.util.List;

/**
 * v2 config schema (clean-slate, AceLib public API only).
 *
 * <p>Declares the v2 config contract: {@code version: "2.0"} plus the retained
 * product capabilities (multi-currency, debt policy, start balance, locales,
 * Discord, storage) expressed as nested paths with safe defaults. The schema is
 * intentionally independent of the v1 {@code config-version} surface; the v2
 * adapter never reads or migrates v1 config.</p>
 *
 * <p>Field defaults are applied by {@code ConfigManager.load()}/{@code reload()}
 * when a key is absent, so a partial on-disk config still yields a complete,
 * valid in-memory snapshot.</p>
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
                // storage
                new FieldSpec("storage.type", "sqlite", false),
                // discord
                new FieldSpec("discord.enabled", false, false),
                new FieldSpec("discord.webhook-url", "", false),
                // currencies (retained multi-currency capability)
                new FieldSpec("currencies.dollar.default", true, false),
                new FieldSpec("currencies.dollar.name", "金幣", false),
                new FieldSpec("currencies.token.default", false, false),
                new FieldSpec("currencies.token.name", "活動代幣", false),
                // leaderboard
                new FieldSpec("leaderboard.enabled", true, false),
                new FieldSpec("leaderboard.page-size", 10, false)
        ));
    }
}
