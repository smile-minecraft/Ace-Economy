package com.smile.aceeconomy.api.v2;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.UUID;

/**
 * Default {@link EconomyApi} implementation delegating to {@link EconomyService}.
 *
 * <p>The {@code publisher} injected here must be the same {@link InMemoryTransactionEventPublisher}
 * instance passed to {@link EconomyService}, otherwise listeners registered through this API will
 * not receive pre-commit events fired by the service.</p>
 */
public final class EconomyApiImpl implements EconomyApi {

    private final EconomyService service;
    private final InMemoryTransactionEventPublisher publisher;

    public EconomyApiImpl(EconomyService service, InMemoryTransactionEventPublisher publisher) {
        this.service = service;
        this.publisher = publisher;
    }

    @Override
    public EconomyResult<AccountSnapshot> createAccount(UUID uuid, String ownerName) {
        return service.createAccount(uuid, ownerName);
    }

    @Override
    public EconomyResult<AccountSnapshot> loadAccount(UUID uuid) {
        return service.load(uuid);
    }

    @Override
    public EconomyResult<Amount> getBalance(UUID uuid, String currencyId) {
        return service.getBalance(uuid, currencyId);
    }

    @Override
    public EconomyResult<Amount> deposit(UUID uuid, String currencyId, Amount amount) {
        return service.deposit(uuid, currencyId, amount);
    }

    @Override
    public EconomyResult<Amount> withdraw(UUID uuid, String currencyId, Amount amount) {
        return service.withdraw(uuid, currencyId, amount);
    }

    @Override
    public EconomyResult<Amount> setBalance(UUID uuid, String currencyId, Amount amount) {
        return service.setBalance(uuid, currencyId, amount);
    }

    @Override
    public EconomyResult<TransferResult> transfer(UUID from, UUID to, String currencyId, Amount amount) {
        return service.transfer(from, to, currencyId, amount);
    }

    @Override
    public void registerTransactionListener(TransactionListener listener) {
        publisher.register(listener);
    }

    @Override
    public void unregisterTransactionListener(TransactionListener listener) {
        publisher.unregister(listener);
    }
}
