package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.WithdrawResult;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the production bank GUI withdraw path ({@code ProductionAdapters.BankUseCase}).
 * They lock the money-safety rule shared with the {@code /withdraw} command path: the deduction
 * commits only together with a minted note — if the item cannot be materialised after the
 * withdrawal succeeded, the account is refunded exactly once and the reply stays a typed
 * rejection; a refund outage must stay observable, never silently swallowed.
 */
class ProductionAdaptersBankWithdrawTest {

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();

    private ProductionAdapters.BankUseCase useCase(EconomyApi api) {
        return new ProductionAdapters.BankUseCase(api, currencies(), banknotes,
                new BanknoteValidator(new InMemoryIdempotencyGuard()),
                Mockito.mock(AtomicRedemptionStore.class));
    }

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    // ---------------- mint failure compensation ----------------

    /**
     * The production {@code EconomyService.withdraw} resolves to the AFTER balance
     * (before − amount), never to the withdrawn amount itself. Every success stub in
     * this class mirrors that contract so the compensation rule can be observed for
     * real: the refund must carry the requested withdrawal amount, not the balance
     * that happens to come back in the withdrawal result.
     */
    private static Amount afterBalance(long before, long withdrawn, int scale) {
        return Amount.of(before - withdrawn, scale);
    }

    @Test
    void mintFailureAfterDeductionRefundsTheRequestedAmountOnceAndRejects() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(player, "dollar", amount)).thenReturn(EconomyResult.success(afterBalance(1000, 100, 2)));
        when(api.deposit(player, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        banknotes.failNextMint = true;

        WithdrawResult result = useCase(api).withdraw(player, 100L, "dollar");

        assertTrue(result.rejected(), "a mint failure must surface as a rejection");
        verify(api, times(1)).withdraw(player, "dollar", amount);
        // The refund must equal the withdrawn amount (100), not the after balance (900)
        // that the production withdraw result actually resolves to.
        verify(api, times(1)).deposit(player, "dollar", amount);
        verify(api, never()).deposit(player, "dollar", afterBalance(1000, 100, 2));
        assertTrue(banknotes.minted().isEmpty(), "no note exists, so nothing may be delivered");
    }

    @Test
    void fullBalanceWithdrawStillRefundsTheFullAmountWhenMintFails() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        // Withdrawing the whole balance resolves to an after balance of zero; the
        // compensation must still refund the full withdrawn amount, never zero.
        when(api.withdraw(player, "dollar", amount)).thenReturn(EconomyResult.success(afterBalance(100, 100, 2)));
        when(api.deposit(player, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        banknotes.failNextMint = true;

        WithdrawResult result = useCase(api).withdraw(player, 100L, "dollar");

        assertTrue(result.rejected(), "a mint failure must surface as a rejection");
        verify(api, times(1)).deposit(player, "dollar", amount);
    }

    @Test
    void mintThrowAfterDeductionRefundsTheRequestedAmountOnceAndRejects() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(player, "dollar", amount)).thenReturn(EconomyResult.success(afterBalance(1000, 100, 2)));
        when(api.deposit(player, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        banknotes.throwNextMint = new RuntimeException("acelib item factory blew up");

        WithdrawResult result = useCase(api).withdraw(player, 100L, "dollar");

        assertTrue(result.rejected(), "a throwing mint must surface as a typed rejection, not propagate");
        verify(api, times(1)).deposit(player, "dollar", amount);
        assertTrue(banknotes.minted().isEmpty(), "no note exists, so nothing may be delivered");
    }

    @Test
    void failedRefundStillRejectsExactlyOnceWithoutRetry() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(player, "dollar", amount)).thenReturn(EconomyResult.success(afterBalance(1000, 100, 2)));
        when(api.deposit(player, "dollar", amount))
                .thenReturn(EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing"));
        banknotes.failNextMint = true;

        WithdrawResult result = useCase(api).withdraw(player, 100L, "dollar");

        assertTrue(result.rejected(), "a failed refund must still fail the withdraw closed");
        verify(api, times(1)).deposit(player, "dollar", amount);
    }

    @Test
    void compensationUsesTheSameCurrencyAsTheDeduction() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(5L, 0);
        when(api.withdraw(player, "token", amount)).thenReturn(EconomyResult.success(afterBalance(100, 5, 0)));
        when(api.deposit(player, "token", amount)).thenReturn(EconomyResult.success(amount));
        banknotes.failNextMint = true;

        useCase(api).withdraw(player, 5L, "token");

        verify(api).deposit(player, "token", amount);
    }

    // ---------------- regression: existing outcomes unchanged ----------------

    @Test
    void successfulMintDeliversTheNoteWithoutAnyRefund() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(player, "dollar", amount)).thenReturn(EconomyResult.success(afterBalance(1000, 100, 2)));

        WithdrawResult result = useCase(api).withdraw(player, 100L, "dollar");

        assertTrue(result.success(), "a minted note must succeed the withdraw");
        verify(api, never()).deposit(any(UUID.class), anyString(), any(Amount.class));
        assertEquals(100L, banknotes.minted().get(0).value());
        assertEquals("dollar", banknotes.minted().get(0).currency());
    }

    @Test
    void rejectedDeductionMintsNothingAndNeverRefunds() {
        EconomyApi api = Mockito.mock(EconomyApi.class);
        UUID player = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(player, "dollar", amount))
                .thenReturn(EconomyResult.failure(EconomyError.INSUFFICIENT_FUNDS, "insufficient funds"));

        WithdrawResult result = useCase(api).withdraw(player, 100L, "dollar");

        assertTrue(result.rejected(), "a business rejection must surface as a rejection");
        assertTrue(banknotes.minted().isEmpty(), "nothing may be minted without a committed deduction");
        verify(api, never()).deposit(any(UUID.class), anyString(), any(Amount.class));
    }
}
