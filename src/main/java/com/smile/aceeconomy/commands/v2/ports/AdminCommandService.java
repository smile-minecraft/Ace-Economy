package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Admin economy operations behind {@code /aceeco}. */
public interface AdminCommandService {

    CompletableFuture<EconomyResult<Amount>> give(UUID target, String currencyId, Amount amount,
                                                 AdminAudit audit);

    CompletableFuture<EconomyResult<Amount>> take(UUID target, String currencyId, Amount amount,
                                                 AdminAudit audit);

    CompletableFuture<EconomyResult<Amount>> setBalance(UUID target, String currencyId, Amount amount,
                                                       AdminAudit audit);

    default CompletableFuture<EconomyResult<Amount>> give(UUID target, String currencyId, Amount amount) {
        return give(target, currencyId, amount, AdminAudit.unrecorded());
    }

    default CompletableFuture<EconomyResult<Amount>> take(UUID target, String currencyId, Amount amount) {
        return take(target, currencyId, amount, AdminAudit.unrecorded());
    }

    default CompletableFuture<EconomyResult<Amount>> setBalance(UUID target, String currencyId,
                                                               Amount amount) {
        return setBalance(target, currencyId, amount, AdminAudit.unrecorded());
    }

    CompletableFuture<EconomyResult<Void>> reload();
}
