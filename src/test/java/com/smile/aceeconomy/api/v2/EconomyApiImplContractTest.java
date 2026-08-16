package com.smile.aceeconomy.api.v2;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.TransactionEvent;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link EconomyApiImpl}.
 *
 * <p>These tests document wiring invariants that production bootstrapping must preserve.</p>
 */
class EconomyApiImplContractTest {

    @Test
    @DisplayName("listener registered through API receives service-fired pre-commit events")
    void listenerReceivesServiceEvents() {
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();

        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
        EconomyService service = new EconomyService(
                currencies,
                DebtPolicy.disabled(),
                Amount.of(1000, 2),
                new InMemoryAccountRepository(),
                new RecordingAuditSink(),
                new FixedClock(),
                publisher);
        EconomyApi api = new EconomyApiImpl(service, publisher);

        List<TransactionEvent> received = new CopyOnWriteArrayList<>();
        api.registerTransactionListener(received::add);

        UUID alice = UUID.randomUUID();
        api.createAccount(alice, "Alice");
        api.deposit(alice, "dollar", Amount.of(100, 2));

        assertEquals(1, received.size(), "listener must receive the pre-commit event fired by EconomyService");
        assertEquals(alice, received.get(0).target());
    }

    @Test
    @DisplayName("listener registered through API does not receive events from a different publisher")
    void listenerIsolatedWhenPublishersDiffer() {
        InMemoryTransactionEventPublisher servicePublisher = new InMemoryTransactionEventPublisher();
        InMemoryTransactionEventPublisher apiPublisher = new InMemoryTransactionEventPublisher();

        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
        EconomyService service = new EconomyService(
                currencies,
                DebtPolicy.disabled(),
                Amount.of(1000, 2),
                new InMemoryAccountRepository(),
                new RecordingAuditSink(),
                new FixedClock(),
                servicePublisher);
        EconomyApi api = new EconomyApiImpl(service, apiPublisher);

        List<TransactionEvent> received = new CopyOnWriteArrayList<>();
        api.registerTransactionListener(received::add);

        UUID alice = UUID.randomUUID();
        api.createAccount(alice, "Alice");
        api.deposit(alice, "dollar", Amount.of(100, 2));

        assertTrue(received.isEmpty(),
                "registering on a different publisher instance must not receive service events");
    }
}
