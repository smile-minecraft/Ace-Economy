package com.smile.aceeconomy.api.v2;

import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.ports.CommittedTransactionListener;

import java.util.UUID;

/**
 * v2 native economy API.
 *
 * <p>This is a NEW public surface. It does NOT promise v1 binary compatibility: method
 * signatures, the typed result type ({@link EconomyResult}), the event type
 * ({@link com.smile.aceeconomy.domain.TransactionEvent}) and the failure model ({@link EconomyError}) are intentionally
 * different from v1 {@code EconomyProvider} / {@code EconomyTransactionEvent}.</p>
 */
public interface EconomyApi {

    EconomyResult<AccountSnapshot> createAccount(UUID uuid, String ownerName);

    EconomyResult<AccountSnapshot> loadAccount(UUID uuid);

    EconomyResult<Amount> getBalance(UUID uuid, String currencyId);

    /**
     * Zero-I/O cached balance for synchronous callers (such as Vault) that must never block
     * on storage. Empty on miss or unknown currency: callers fall back to a safe default
     * instead of waiting on the calling thread. The default implementation returns empty;
     * storage-backed implementations override it with their read cache.
     */
    default java.util.Optional<Amount> cachedBalance(UUID uuid, String currencyId) {
        return java.util.Optional.empty();
    }

    /**
     * Background priming read for miss-triggered loaders (such as balance placeholders).
     * A hit returns the cached value without storage I/O; a miss loads from persistence
     * and publishes only when the entry is still absent and no invalidation landed during
     * the read, so a delayed reply can neither resurrect a dropped entry nor overwrite a
     * newer write. The default implementation delegates to {@link #getBalance} as a
     * best-effort prime; storage-backed implementations override it with the fenced
     * conditional publish.
     */
    default EconomyResult<Amount> primeBalanceIfAbsent(UUID uuid, String currencyId) {
        return getBalance(uuid, currencyId);
    }

    EconomyResult<Amount> deposit(UUID uuid, String currencyId, Amount amount);

    EconomyResult<Amount> withdraw(UUID uuid, String currencyId, Amount amount);

    EconomyResult<Amount> setBalance(UUID uuid, String currencyId, Amount amount);

    EconomyResult<TransferResult> transfer(UUID from, UUID to, String currencyId, Amount amount);

    /**
     * Registers a listener for pre-commit transaction events.
     *
     * <p>The implementation must use the same {@code TransactionEventPublisher} instance as
     * {@link com.smile.aceeconomy.application.EconomyService}; otherwise the listener will never
     * receive events.</p>
     */
    void registerTransactionListener(TransactionListener listener);

    /**
     * Unregisters a previously registered listener.
     *
     * <p>Must target the same publisher instance used by
     * {@link com.smile.aceeconomy.application.EconomyService}.</p>
     */
    void unregisterTransactionListener(TransactionListener listener);

    /**
     * Registers a non-cancellable post-commit observer. It fires only after a transaction
     * (balances and audit records) has been durably committed; never for cancelled or
     * uncommitted operations. Observer failures never change the committed result.
     */
    void registerPostCommitListener(CommittedTransactionListener listener);

    /**
     * Unregisters a previously registered post-commit observer.
     */
    void unregisterPostCommitListener(CommittedTransactionListener listener);
}
