package com.smile.aceeconomy.infrastructure.integration.vault;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyResult;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * v2 Vault {@link Economy} adapter.
 *
 * <p>Maps the v2 typed {@link EconomyApi} onto Vault's synchronous {@code Economy} contract. Every
 * failure is mapped to a safe Vault outcome (no success is ever claimed on a v2 failure):</p>
 * <ul>
 *   <li>deposit/withdraw success → {@code EconomyResponse} with the new balance.</li>
 *   <li>deposit/withdraw failure → {@code EconomyResponse} with {@code amount=0}, the current
 *       (or zero) balance, {@code FAILURE} and the v2 message; the transaction is NOT retried.</li>
 *   <li>balance/has on a missing account → {@code 0.0}/{@code false} (safe default, never throws).</li>
 * </ul>
 *
 * <p>Vault has no per-currency concept, so the adapter always operates on the registry's default
 * currency. This class is the adapter; registration ownership lives in
 * {@link VaultEconomyLifecycle}.</p>
 *
 * <p>Name-based methods resolve through a {@link PlayerIdentityResolver} (online players and
 * cached offline records only — never blocking storage or network I/O) and then delegate to
 * the same UUID core path as the {@code OfflinePlayer} overloads, so the UUID stays the
 * account key across renames. An unknown or blank name is never presented as a valid
 * zero-balance account: queries return the safe default and log a {@code FINE} diagnostic,
 * mutations return {@code FAILURE} naming the problem. World parameters are accepted but
 * ignored: there are no per-world balances, every lookup reports the global balance.</p>
 */
public final class VaultEconomyProvider implements Economy {

    private static final Logger LOG = Logger.getLogger(VaultEconomyProvider.class.getName());

    private final EconomyApi api;
    private volatile CurrencyRegistry currencies;
    private final PlayerIdentityResolver identities;

    public VaultEconomyProvider(EconomyApi api, CurrencyRegistry currencies) {
        this(api, currencies, new BukkitPlayerIdentityResolver());
    }

    /**
     * Hot-swap display-only currency metadata (name / symbol) after a validated reload.
     * The guard rejects structural changes so Vault formatting can never diverge from
     * the transactional registry.
     */
    public void replaceCurrencyDisplay(CurrencyRegistry candidate) {
        com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan
                .requireDisplayOnlyChange(this.currencies, candidate);
        this.currencies = candidate;
    }

    public VaultEconomyProvider(EconomyApi api, CurrencyRegistry currencies,
            PlayerIdentityResolver identities) {
        this.api = api;
        this.currencies = currencies;
        this.identities = identities != null ? identities : new BukkitPlayerIdentityResolver();
    }

    /**
     * Normalizes a Vault-supplied player name: trims surrounding whitespace and treats a
     * blank value as unknown. Matching itself is case-insensitive and lives in the
     * resolver; this only decides whether a lookup is worth attempting.
     *
     * @return the trimmed name, or {@code null} when there is nothing to resolve
     */
    private static String normalizeName(String playerName) {
        if (playerName == null) {
            return null;
        }
        String name = playerName.strip();
        return name.isEmpty() ? null : name;
    }

    private Optional<OfflinePlayer> resolveName(String playerName) {
        String name = normalizeName(playerName);
        if (name == null) {
            return Optional.empty();
        }
        try {
            Optional<OfflinePlayer> found = identities.resolve(name);
            return found != null ? found : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String describeName(String playerName) {
        String name = normalizeName(playerName);
        return name == null ? "blank" : "unknown '" + name + "'";
    }

    private static void logUnknownLookup(String method, String playerName) {
        LOG.fine("Vault " + method + ": " + describeName(playerName)
                + " player name; returning the safe default instead of a zero-balance account");
    }

    private static EconomyResponse unknownNameFailure(String playerName) {
        String name = normalizeName(playerName);
        String reason = name == null
                ? "player name is blank"
                : "unknown player name '" + name + "': no online or cached player record";
        logUnknownLookup("mutation", playerName);
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, reason);
    }

    // ---------- identity ----------

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "AceEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return currencies.getDefault().scale();
    }

    @Override
    public String format(double amount) {
        Currency def = currencies.getDefault();
        return def.symbol() + String.format(Locale.ROOT, "%." + def.scale() + "f", amount);
    }

    @Override
    public String currencyNamePlural() {
        return currencies.getDefault().displayName();
    }

    @Override
    public String currencyNameSingular() {
        return currencies.getDefault().displayName();
    }

    // ---------- account ----------

    @Override
    public boolean hasAccount(String playerName) {
        return resolveName(playerName).map(player -> hasAccount(player)).orElseGet(() -> {
            logUnknownLookup("hasAccount(String)", playerName);
            return false;
        });
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        return api.loadAccount(player.getUniqueId()).isSuccess();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return resolveName(playerName).map(player -> createPlayerAccount(player)).orElseGet(() -> {
            logUnknownLookup("createPlayerAccount(String)", playerName);
            return false;
        });
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        return api.createAccount(player.getUniqueId(), player.getName()).isSuccess();
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ---------- balance / has ----------
    //
    // Synchronous Vault queries run on the caller's thread (usually the server main thread),
    // so they must never block on storage. Balances are served from the read cache only:
    // a hit returns the last persisted value, a miss returns the safe default 0.0.
    // The cache is refreshed by every successful write through the application service and
    // dropped on offline / write failure / reload, so it is never the source of truth.
    // A post-reload miss returning 0.0 is the accepted product contract (see the reload
    // section of docs/operations.md): the next persisted read re-primes the entry, and no
    // synchronous refill runs on the calling thread.

    private double balanceOf(OfflinePlayer player, String currencyId) {
        if (player == null || !currencies.contains(currencyId)) {
            return 0.0;
        }
        return api.cachedBalance(player.getUniqueId(), currencyId)
                .map(cached -> cached.value().doubleValue())
                .orElse(0.0);
    }

    @Override
    public double getBalance(String playerName) {
        return resolveName(playerName).map(player -> getBalance(player)).orElseGet(() -> {
            logUnknownLookup("getBalance(String)", playerName);
            return 0.0;
        });
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balanceOf(player, currencies.defaultCurrencyId());
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return balanceOf(player, currencies.defaultCurrencyId());
    }

    @Override
    public boolean has(String playerName, double amount) {
        return resolveName(playerName).map(player -> has(player, amount)).orElseGet(() -> {
            logUnknownLookup("has(String, double)", playerName);
            return false;
        });
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return balanceOf(player, currencies.defaultCurrencyId()) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ---------- deposit / withdraw ----------

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return resolveName(playerName).map(player -> deposit(player, amount))
                .orElseGet(() -> unknownNameFailure(playerName));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return deposit(player, amount);
    }

    private EconomyResponse deposit(OfflinePlayer player, double amount) {
        String currencyId = currencies.defaultCurrencyId();
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "amount must be non-negative");
        }
        Amount amt;
        try {
            amt = currencies.get(currencyId).amountOf(amount);
        } catch (IllegalArgumentException e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "amount scale exceeds currency precision");
        }
        EconomyResult<Amount> r = api.deposit(player.getUniqueId(), currencyId, amt);
        if (r.isSuccess()) {
            return new EconomyResponse(amount, r.value().value().doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, null);
        }
        return new EconomyResponse(0, balanceOf(player, currencyId), EconomyResponse.ResponseType.FAILURE,
                r.message());
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return resolveName(playerName).map(player -> withdraw(player, amount))
                .orElseGet(() -> unknownNameFailure(playerName));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdraw(player, amount);
    }

    private EconomyResponse withdraw(OfflinePlayer player, double amount) {
        String currencyId = currencies.defaultCurrencyId();
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player is null");
        }
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "amount must be non-negative");
        }
        Amount amt;
        try {
            amt = currencies.get(currencyId).amountOf(amount);
        } catch (IllegalArgumentException e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                    "amount scale exceeds currency precision");
        }
        EconomyResult<Amount> r = api.withdraw(player.getUniqueId(), currencyId, amt);
        if (r.isSuccess()) {
            return new EconomyResponse(amount, r.value().value().doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, null);
        }
        return new EconomyResponse(0, balanceOf(player, currencyId), EconomyResponse.ResponseType.FAILURE,
                r.message());
    }

    // ---------- banks (unsupported) ----------

    private static final String BANK_UNSUPPORTED = "bank features are not supported";

    @Override
    public EconomyResponse createBank(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, BANK_UNSUPPORTED);
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }
}
