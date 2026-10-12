package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.ports.WithdrawResult;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GUI use case compensates a failed mint through the same single-use
 * trusted ledger as the command flow: the deduction commits, the mint
 * observably fails without exposing a note, the pipeline grants exactly one
 * four-field authorization, and the core credits it exempt from the gate.
 * The balance is restored exactly and the reply stays a typed rejection.
 */
class GuiRefundProducerTest {

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true)));
    }

    @Test
    @DisplayName("GUI failed mint compensates through the real ledger with the balance restored")
    void guiFailedMintCompensatesThroughRealLedger() {
        CurrencyRegistry registry = currencies();
        EconomyService economy = new EconomyService(registry, DebtPolicy.disabled(),
                Amount.zero(2), new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        EconomyApi api = new EconomyApiImpl(economy, new InMemoryTransactionEventPublisher());
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindRefundLedger(ledger);
        ProductionAdapters.BankUseCase useCase = new ProductionAdapters.BankUseCase(api, economy,
                registry, banknotes, new BanknoteValidator(new InMemoryIdempotencyGuard()),
                Mockito.mock(AtomicRedemptionStore.class));
        useCase.bindRefundLedger(ledger);

        UUID playerId = UUID.randomUUID();
        economy.createAccount(playerId, "GuiProducer");
        economy.deposit(playerId, "dollar", Amount.of(500, 2));
        Amount before = economy.getBalance(playerId, "dollar").value();
        banknotes.failNextMint = true;

        WithdrawResult result = useCase.withdraw(playerId, 100L, "dollar");

        assertTrue(result.rejected(), "a failed mint must surface as a typed rejection");
        assertEquals(0, before.compareTo(economy.getBalance(playerId, "dollar").value()),
                "the GUI compensation must restore the exact balance through the trusted path");
        assertTrue(banknotes.minted().isEmpty(), "no note exists, so nothing may be delivered");
    }

    @Test
    @DisplayName("GUI throwing mint compensates through the real ledger")
    void guiThrowingMintCompensatesThroughRealLedger() {
        CurrencyRegistry registry = currencies();
        EconomyService economy = new EconomyService(registry, DebtPolicy.disabled(),
                Amount.zero(2), new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        EconomyApi api = new EconomyApiImpl(economy, new InMemoryTransactionEventPublisher());
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindRefundLedger(ledger);
        ProductionAdapters.BankUseCase useCase = new ProductionAdapters.BankUseCase(api, economy,
                registry, banknotes, new BanknoteValidator(new InMemoryIdempotencyGuard()),
                Mockito.mock(AtomicRedemptionStore.class));
        useCase.bindRefundLedger(ledger);

        UUID playerId = UUID.randomUUID();
        economy.createAccount(playerId, "GuiProducer");
        economy.deposit(playerId, "dollar", Amount.of(500, 2));
        Amount before = economy.getBalance(playerId, "dollar").value();
        banknotes.throwNextMint = new RuntimeException("acelib item factory blew up");

        WithdrawResult result = useCase.withdraw(playerId, 100L, "dollar");

        assertTrue(result.rejected(), "a throwing mint must surface as a typed rejection");
        assertEquals(0, before.compareTo(economy.getBalance(playerId, "dollar").value()),
                "the GUI compensation must restore the exact balance through the trusted path");
    }
}
