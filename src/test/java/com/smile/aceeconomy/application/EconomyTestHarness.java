package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;

import java.util.List;

/** Shared in-memory wiring for application/api tests (no vendor code). */
final class EconomyTestHarness {

    final CurrencyRegistry currencies;
    final InMemoryAccountRepository repo;
    final RecordingAuditSink audit;
    final FixedClock clock;
    final InMemoryTransactionEventPublisher publisher;
    final EconomyService service;

    EconomyTestHarness(DebtPolicy debtPolicy, Amount startBalance) {
        this.currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        this.repo = new InMemoryAccountRepository();
        this.audit = new RecordingAuditSink();
        this.clock = new FixedClock();
        this.publisher = new InMemoryTransactionEventPublisher();
        this.service = new EconomyService(currencies, debtPolicy, startBalance, repo, audit, clock, publisher);
    }

    Currency dollar() {
        return currencies.getDefault();
    }

    Amount dollar(double v) {
        return currencies.getDefault().amountOf(v);
    }
}
