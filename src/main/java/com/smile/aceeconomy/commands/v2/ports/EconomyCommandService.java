package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.application.TransferResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async economy use-case boundary consumed by the v2 command presentation layer.
 *
 * <p>Every method returns a {@link CompletableFuture} so the command handlers never block the
 * dispatch thread and never touch Bukkit/player objects directly — the implementation (wired by
 * the CompositionRoot in a later task) performs the region-safe mutation and completes the future,
 * after which the handler replies through the Folia-safe {@code ReplySink}.</p>
 */
public interface EconomyCommandService {

    CompletableFuture<EconomyResult<Amount>> getBalance(UUID uuid, String currencyId);

    CompletableFuture<EconomyResult<Amount>> withdraw(UUID uuid, String currencyId, Amount amount);

    CompletableFuture<EconomyResult<TransferResult>> transfer(UUID from, UUID to, String currencyId, Amount amount);

    CompletableFuture<EconomyResult<AccountSnapshot>> loadAccount(UUID uuid);

    /** Resolve a currency by id (case/whitespace insensitive). Empty if unknown. */
    Optional<CurrencyInfo> resolveCurrency(String currencyId);

    /** Known currency ids, for tab completion. */
    List<String> knownCurrencyIds();

    /** Normalized id of the default currency. */
    String defaultCurrencyId();
}
