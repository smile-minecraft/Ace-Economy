package com.smile.aceeconomy.infrastructure.integration.placeholder;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.operations.LeaderboardEntry;
import com.smile.aceeconomy.ports.Clock;

import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Pure, vendor-free resolver for the v2 {@code aceeco} placeholder namespace.
 *
 * <p>Documented placeholder contract (all lower-case, evaluated against the default or a named
 * currency):</p>
 * <ul>
 *   <li>{@code %aceeco_balance%} — default-currency balance, raw (e.g. {@code 100.00}).</li>
 *   <li>{@code %aceeco_balance_formatted%} — default-currency balance with symbol (e.g. {@code $100.00}).</li>
 *   <li>{@code %aceeco_balance_<currency>%} — named-currency raw balance.</li>
 *   <li>{@code %aceeco_balance_<currency>_formatted%} — named-currency balance with symbol.</li>
 *   <li>{@code %aceeco_rank%}, {@code %aceeco_rank_<currency>%} — 1-based rank of the requesting
 *       player in the cached leaderboard snapshot; {@code null} when there is no fresh snapshot
 *       or the player is not on the board (never a guessed rank).</li>
 *   <li>{@code %aceeco_top_name_<n>%}, {@code %aceeco_top_name_<n>_<currency>%} — owner name of
 *       the n-th leaderboard row (1-based, {@code 1 <= n <= 100}).</li>
 *   <li>{@code %aceeco_top_balance_<n>%}, {@code %aceeco_top_balance_<n>_<currency>%} — raw
 *       balance of the n-th leaderboard row.</li>
 *   <li>{@code %aceeco_currency_name_<id>%}, {@code %aceeco_currency_symbol_<id>%} — display
 *       name / symbol of a configured currency.</li>
 * </ul>
 *
 * <p>Rank and top placeholders only read the shared {@link LeaderboardCache} snapshot, so
 * high-frequency placeholder refreshes never trigger storage I/O and always sort exactly like
 * {@code /baltop} (both sides read the same cached ranking). A missing or expired snapshot
 * resolves to {@code null} rather than falling back to a synchronous database query.</p>
 *
 * <p>Fail-closed behavior: any unknown placeholder name, malformed currency id, unknown currency,
 * out-of-range position, or unavailable account resolves to {@code null}. Returning {@code null}
 * from PAPI leaves the literal placeholder unexpanded rather than showing a wrong value.</p>
 */
public final class PlaceholderResolver {

    private static final Pattern CURRENCY_ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern POSITION = Pattern.compile("[0-9]+");

    /** Strict upper bound for {@code top_*} positions; larger values resolve to {@code null}. */
    private static final int MAX_TOP_N = 100;

    private final EconomyApi api;
    private final CurrencyDisplayHolder display;
    private final LeaderboardCache leaderboard;
    private final Duration leaderboardTtl;
    private final Clock clock;

    public PlaceholderResolver(EconomyApi api, CurrencyRegistry currencies) {
        this(api, new CurrencyDisplayHolder(currencies), null, null, null);
    }

    /**
     * Shared-holder constructor for production wiring: every display surface reads the
     * same holder, so a reload publish is observed atomically across Vault, commands
     * and placeholders.
     */
    public PlaceholderResolver(EconomyApi api, CurrencyDisplayHolder display) {
        this(api, display, null, null, null);
    }

    /**
     * Snapshot-backed constructor. The cache should be the same instance the leaderboard
     * command path reads, so PAPI and {@code /baltop} always agree on ordering. A {@code null}
     * cache disables rank/top placeholders (they resolve to {@code null}); when a cache is
     * given, {@code ttl} and {@code clock} must be non-null.
     */
    public PlaceholderResolver(EconomyApi api, CurrencyRegistry currencies,
            LeaderboardCache leaderboard, Duration leaderboardTtl, Clock clock) {
        this(api, new CurrencyDisplayHolder(currencies), leaderboard, leaderboardTtl, clock);
    }

    /**
     * Snapshot-backed constructor sharing one display holder. The cache should be the same
     * instance the leaderboard command path reads, so PAPI and {@code /baltop} always agree
     * on ordering. A {@code null} cache disables rank/top placeholders (they resolve to
     * {@code null}); when a cache is given, {@code ttl} and {@code clock} must be non-null.
     */
    public PlaceholderResolver(EconomyApi api, CurrencyDisplayHolder display,
            LeaderboardCache leaderboard, Duration leaderboardTtl, Clock clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.display = Objects.requireNonNull(display, "display");
        if (leaderboard != null) {
            Objects.requireNonNull(leaderboardTtl, "leaderboardTtl");
            Objects.requireNonNull(clock, "clock");
        }
        this.leaderboard = leaderboard;
        this.leaderboardTtl = leaderboardTtl;
        this.clock = clock;
    }

    /**
     * Hot-swap display-only currency metadata (name / symbol) after a validated reload.
     * The guard rejects structural changes so placeholders can never diverge from
     * the transactional registry.
     */
    public void replaceCurrencyDisplay(CurrencyRegistry candidate) {
        com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan
                .requireDisplayOnlyChange(display.get(), candidate);
        display.publish(candidate);
    }

    /**
     * Resolve a placeholder. Returns {@code null} when the player is unavailable, the placeholder
     * name is unknown, the currency id is malformed/unknown, or the account is unavailable.
     */
    @Nullable
    public String resolve(@Nullable OfflinePlayer player, @Nullable String params) {
        if (player == null || params == null) {
            return null;
        }
        String p = params.toLowerCase(Locale.ROOT);
        String def = display.get().defaultCurrencyId();

        if (p.equals("balance")) {
            return rawBalance(player, def);
        }
        if (p.equals("balance_formatted")) {
            return formattedBalance(player, def);
        }
        if (p.startsWith("balance_")) {
            String rest = p.substring("balance_".length());
            boolean formatted = rest.endsWith("_formatted");
            String currencyId = formatted ? rest.substring(0, rest.length() - "_formatted".length()) : rest;
            if (currencyId.isEmpty() || !CURRENCY_ID.matcher(currencyId).matches()) {
                return null; // malformed currency id
            }
            if (!display.get().contains(currencyId)) {
                return null; // unknown currency
            }
            return formatted ? formattedBalance(player, currencyId) : rawBalance(player, currencyId);
        }
        if (p.equals("rank")) {
            return rank(player, def);
        }
        if (p.startsWith("rank_")) {
            String currencyId = p.substring("rank_".length());
            if (currencyId.isEmpty() || !CURRENCY_ID.matcher(currencyId).matches()) {
                return null; // malformed currency id
            }
            if (!display.get().contains(currencyId)) {
                return null; // unknown currency
            }
            return rank(player, currencyId);
        }
        if (p.startsWith("top_name_")) {
            TopRequest req = parseTop(p.substring("top_name_".length()), def);
            if (req == null) {
                return null;
            }
            return topName(req);
        }
        if (p.startsWith("top_balance_")) {
            TopRequest req = parseTop(p.substring("top_balance_".length()), def);
            if (req == null) {
                return null;
            }
            return topBalance(req);
        }
        if (p.startsWith("currency_name_")) {
            return currencyInfo(p.substring("currency_name_".length()), true);
        }
        if (p.startsWith("currency_symbol_")) {
            return currencyInfo(p.substring("currency_symbol_".length()), false);
        }
        return null; // unknown placeholder
    }

    /** Parsed {@code top_*} request: 1-based position plus resolved currency id. */
    private record TopRequest(int position, String currencyId) {
    }

    /**
     * Strictly parse the remainder of a {@code top_*} placeholder: {@code <n>} or
     * {@code <n>_<currency>}. The position must be decimal digits in {@code 1..100};
     * anything else (empty, signed, non-numeric, oversized, malformed/unknown currency)
     * yields {@code null} instead of throwing.
     */
    @Nullable
    private TopRequest parseTop(String rest, String defaultCurrencyId) {
        String nPart;
        String currencyId = defaultCurrencyId;
        int sep = rest.indexOf('_');
        if (sep < 0) {
            nPart = rest;
        } else {
            nPart = rest.substring(0, sep);
            currencyId = rest.substring(sep + 1);
            if (currencyId.isEmpty() || !CURRENCY_ID.matcher(currencyId).matches()) {
                return null; // malformed currency id
            }
            if (!display.get().contains(currencyId)) {
                return null; // unknown currency
            }
        }
        if (nPart.isEmpty() || !POSITION.matcher(nPart).matches()) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(nPart);
        } catch (NumberFormatException overflow) {
            return null;
        }
        if (n < 1 || n > MAX_TOP_N) {
            return null;
        }
        return new TopRequest(n, currencyId);
    }

    /** Fresh snapshot for one currency, or {@code null} when absent/expired/unwired. */
    @Nullable
    private List<LeaderboardEntry> snapshot(String currencyId) {
        if (leaderboard == null || leaderboardTtl == null || clock == null) {
            return null;
        }
        Instant now = clock.instant();
        if (now == null) {
            return null;
        }
        return leaderboard.getIfFresh(currencyId, leaderboardTtl, now);
    }

    /** 1-based rank of the player in the cached snapshot; {@code null} when not on the board. */
    @Nullable
    private String rank(OfflinePlayer player, String currencyId) {
        List<LeaderboardEntry> ranked = snapshot(currencyId);
        if (ranked == null) {
            return null;
        }
        for (LeaderboardEntry entry : ranked) {
            if (entry.accountId().equals(player.getUniqueId())) {
                return String.valueOf(entry.rank());
            }
        }
        return null; // player not on the board: never guess a rank
    }

    /** Owner name of the n-th cached row; {@code null} when the position is beyond the board. */
    @Nullable
    private String topName(TopRequest req) {
        List<LeaderboardEntry> ranked = snapshot(req.currencyId());
        if (ranked == null || req.position() > ranked.size()) {
            return null;
        }
        return ranked.get(req.position() - 1).ownerName();
    }

    /** Raw balance of the n-th cached row; {@code null} when the position is beyond the board. */
    @Nullable
    private String topBalance(TopRequest req) {
        List<LeaderboardEntry> ranked = snapshot(req.currencyId());
        if (ranked == null || req.position() > ranked.size()) {
            return null;
        }
        return ranked.get(req.position() - 1).balance().value().toPlainString();
    }

    /** Display name (or symbol) of a configured currency; {@code null} for unknown ids. */
    @Nullable
    private String currencyInfo(String currencyId, boolean displayName) {
        if (currencyId.isEmpty() || !CURRENCY_ID.matcher(currencyId).matches()) {
            return null; // malformed currency id
        }
        if (!display.get().contains(currencyId)) {
            return null; // unknown currency
        }
        Currency c = display.get().get(currencyId);
        return displayName ? c.displayName() : c.symbol();
    }

    @Nullable
    private String rawBalance(OfflinePlayer player, String currencyId) {        EconomyResult<Amount> r = api.getBalance(player.getUniqueId(), currencyId);
        if (r.isFailure()) {
            return null; // unavailable account
        }
        return r.value().value().toPlainString();
    }

    @Nullable
    private String formattedBalance(OfflinePlayer player, String currencyId) {
        EconomyResult<Amount> r = api.getBalance(player.getUniqueId(), currencyId);
        if (r.isFailure()) {
            return null; // unavailable account
        }
        Currency c = display.get().get(currencyId);
        return c.symbol() + r.value().value().toPlainString();
    }
}
