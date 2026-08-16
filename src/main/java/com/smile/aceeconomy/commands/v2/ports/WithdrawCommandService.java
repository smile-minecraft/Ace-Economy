package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.commands.v2.CommandModels.WithdrawReceipt;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Withdrawal boundary that deducts the balance and hands out the physical banknote item.
 *
 * <p>The actual inventory mutation is performed by the implementation (wired later) through the
 * {@code FoliaContextExecutor} seam; the command layer only supplies the identity, currency and
 * amount and consumes the typed receipt.</p>
 */
public interface WithdrawCommandService {

    CompletableFuture<EconomyResult<WithdrawReceipt>> withdraw(UUID playerUuid, String currencyId, Amount amount);
}
