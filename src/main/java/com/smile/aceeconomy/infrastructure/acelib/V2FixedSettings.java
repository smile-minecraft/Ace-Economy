package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.config.ConfigBinder;
import com.smile.acelib.config.ConfigSnapshot;

import org.jetbrains.annotations.Nullable;

/**
 * Bound view of the fixed v2 config scalars.
 *
 * <p>Every component maps to one declared {@code V2ConfigSchema} field via an
 * explicit {@link ConfigBinder.ConfigKey} path and is bound with
 * {@link ConfigBinder#bind(ConfigSnapshot, Class)} while the candidate config
 * is still unvalidated: a wrong type or an out-of-range number fails the load
 * with the full dotted field path, before anything is published. Numeric
 * ranges that the schema expresses as validation rules travel on
 * {@link ConfigBinder.ConfigRange}; the two debt/start-balance doubles carry
 * no range (any finite value is legal) and their finiteness stays an explicit
 * adapter check because the binder accepts non-finite numbers.</p>
 *
 * <p>Deliberately <em>not</em> bound as binder enums: {@code storage.type} is
 * case-insensitive with surrounding whitespace tolerated (and an absent value
 * means {@code json}), and {@code settings.locale} resolves through the
 * adapter locale table. A case-sensitive binder enum would reject inputs the
 * product accepts today, so both stay {@link String} components with explicit
 * membership checks in the adapter. Cross-field rules (required MySQL
 * strings, the SQLite data-folder gate) and the operator-owned dynamic blocks
 * ({@code currencies}, {@code bank-gui}) likewise stay in their existing
 * validators: the binder owns per-field type and range, nothing more.</p>
 *
 * <p>Missing keys bind to {@code null} for {@link String} components and fail
 * closed for primitives. In practice every key is present because
 * {@code ConfigManager.load()} applies the schema defaults before the
 * snapshot is taken; a missing primitive therefore means the schema and this
 * record drifted apart, and failing the load is the correct response.</p>
 */
public record V2FixedSettings(
        @ConfigBinder.ConfigKey("storage.type") @Nullable String storageType,
        @ConfigBinder.ConfigKey("settings.locale") @Nullable String locale,
        @ConfigBinder.ConfigKey("storage.mysql.port")
        @ConfigBinder.ConfigRange(min = 1, max = 65535) int mysqlPort,
        @ConfigBinder.ConfigKey("storage.mysql.pool-size")
        @ConfigBinder.ConfigRange(min = 1, max = 1000) int mysqlPoolSize,
        @ConfigBinder.ConfigKey("storage.mysql.max-lifetime")
        @ConfigBinder.ConfigRange(min = 1, max = 9.223372036854776E18) long mysqlMaxLifetime,
        @ConfigBinder.ConfigKey("leaderboard.cache-time-seconds")
        @ConfigBinder.ConfigRange(min = 1, max = 86400) int leaderboardCacheTimeSeconds,
        @ConfigBinder.ConfigKey("leaderboard.page-size")
        @ConfigBinder.ConfigRange(min = 1, max = 100) int leaderboardPageSize,
        @ConfigBinder.ConfigKey("economy.default-debt-limit") double defaultDebtLimit,
        @ConfigBinder.ConfigKey("start-balance") double startBalance,
        @ConfigBinder.ConfigKey("economy.allow-negative-balance") boolean allowNegativeBalance,
        @ConfigBinder.ConfigKey("discord.enabled") boolean discordEnabled,
        @ConfigBinder.ConfigKey("leaderboard.enabled") boolean leaderboardEnabled,
        @ConfigBinder.ConfigKey("settings.main-command-alias") @Nullable String mainCommandAlias,
        @ConfigBinder.ConfigKey("storage.sqlite.path") @Nullable String sqlitePath,
        @ConfigBinder.ConfigKey("storage.mysql.host") @Nullable String mysqlHost,
        @ConfigBinder.ConfigKey("storage.mysql.database") @Nullable String mysqlDatabase,
        @ConfigBinder.ConfigKey("storage.mysql.username") @Nullable String mysqlUsername,
        @ConfigBinder.ConfigKey("storage.mysql.password") @Nullable String mysqlPassword,
        @ConfigBinder.ConfigKey("discord.webhook-url") @Nullable String discordWebhookUrl) {
}
