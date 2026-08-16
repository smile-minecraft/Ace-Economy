package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Admin economy operations behind {@code /aceeco}. */
public interface AdminCommandService {

    CompletableFuture<EconomyResult<Amount>> give(UUID target, String currencyId, Amount amount);

    CompletableFuture<EconomyResult<Amount>> take(UUID target, String currencyId, Amount amount);

    CompletableFuture<EconomyResult<Amount>> setBalance(UUID target, String currencyId, Amount amount);

    CompletableFuture<EconomyResult<Void>> reload();
}
