package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.InMemoryBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service atomic issuance boundary (phase-1 red).
 *
 * <p>The new service methods must deduct through the atomic store on the same
 * backend instance (amount, guard, debt, pre-commit event plus atomic cap,
 * post-commit notification with the authoritative receipt, mirroring the admin
 * and redeem paths) and must never call {@code api.withdraw} followed by a
 * standalone mark. The phase-1 stubs always fail closed with
 * {@code AUDIT_FAILURE} and never touch the store, so every assertion below
 * fails until phase 2 implements the boundary without changing the public
 * API.</p>
 */
class BanknoteIssuanceAtomicServiceTest {

    private EconomyService serviceWithAccount(UUID account) {
        CurrencyRegistry registry = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true)));
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
        return new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                accounts, tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
    }

    @Test
    void issueBanknoteCommitsThroughAtomicStore() {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        EconomyService service = serviceWithAccount(account);
        AtomicBanknoteIssuanceStore store = mock(AtomicBanknoteIssuanceStore.class);
        try {
            IssuanceDebitResult committed = IssuanceDebitResult.committed(
                    Amount.of(10000L, 2), Amount.of(9900L, 2), UUID.randomUUID(),
                    com.smile.aceeconomy.domain.BanknoteIssuance.prepared(
                            nonce, account, "dollar", Amount.of(100L, 2), Instant.now()));
            org.mockito.Mockito.when(store.commitDebit(any(UUID.class),
                    any(com.smile.aceeconomy.domain.Transaction.class),
                    any(DebtPolicy.class))).thenReturn(committed);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        EconomyResult<Amount> result =
                service.issueBanknote(account, "dollar", Amount.of(100L, 2), nonce, store);

        assertTrue(result.isSuccess(),
                "atomic issue must succeed through the store, got " + result);
        verify(store, times(1)).commitDebit(any(UUID.class),
                any(com.smile.aceeconomy.domain.Transaction.class), any(DebtPolicy.class));
    }

    @Test
    void issueBanknoteNeverUsesStandaloneWithdrawThenMark() {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        EconomyService service = serviceWithAccount(account);
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();

        EconomyResult<Amount> result =
                service.issueBanknote(account, "dollar", Amount.of(100L, 2), nonce, store);

        assertTrue(result.isSuccess(),
                "atomic issue must go through commitDebit on the same instance, never"
                        + " api.withdraw plus a standalone mark; got " + result);
    }

    @Test
    void recoveryRefundUsesSystemOperatorAndAtomicCommit() {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        EconomyService service = serviceWithAccount(account);
        AtomicBanknoteIssuanceStore store = mock(AtomicBanknoteIssuanceStore.class);
        com.smile.aceeconomy.domain.BanknoteIssuance proven =
                new com.smile.aceeconomy.domain.BanknoteIssuance(nonce, account, "dollar",
                        amount,
                        com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DEBIT_COMMITTED,
                        UUID.randomUUID().toString(),
                        com.smile.aceeconomy.domain.BanknoteIssuanceReasons.issueReason(nonce),
                        Instant.now(), Instant.now());
        try {
            when(store.findByNonce(eq(nonce))).thenReturn(java.util.Optional.of(proven));
            when(store.commitRecoveryRefund(eq(nonce),
                    any(com.smile.aceeconomy.domain.Transaction.class),
                    any(DebtPolicy.class))).thenAnswer(invocation -> {
                        com.smile.aceeconomy.domain.Transaction prepared =
                                invocation.getArgument(1);
                        assertEquals(com.smile.aceeconomy.domain.TransactionType.DEPOSIT,
                                prepared.type(), "recovery refund must be a DEPOSIT");
                        assertEquals(
                                com.smile.aceeconomy.domain.BanknoteIssuanceReasons
                                        .recoveryRefundReason(nonce),
                                prepared.reason(), "refund reason must bind the nonce");
                        assertNull(prepared.operatorId(),
                                "recovery refund carries no player operator id");
                        assertEquals(
                                com.smile.aceeconomy.domain.BanknoteIssuanceReasons
                                        .SYSTEM_RECOVERY_OPERATOR,
                                prepared.operatorName(),
                                "recovery refund must run as SYSTEM_RECOVERY");
                        com.smile.aceeconomy.domain.BanknoteIssuance finalIssuance =
                                proven.withStatus(
                                        com.smile.aceeconomy.domain.BanknoteIssuanceStatus
                                                .FINAL_VOID_REFUNDED,
                                        Instant.now());
                        return com.smile.aceeconomy.ports.persistence.IssuanceRefundResult
                                .committed(Amount.of(10000L, 2), Amount.of(10100L, 2),
                                        prepared.id(), finalIssuance);
                    });
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        EconomyResult<Amount> result = service.refundIssuanceForRecovery(nonce, store);

        assertTrue(result.isSuccess(),
                "proven recovery refund must commit void+refund with SYSTEM_RECOVERY"
                        + " through the atomic store, got " + result);
        try {
            verify(store, times(1)).commitRecoveryRefund(any(UUID.class),
                    any(com.smile.aceeconomy.domain.Transaction.class), any(DebtPolicy.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void capViolationRefusesBeforeCommit() {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        EconomyService service = serviceWithAccount(account);
        AtomicBanknoteIssuanceStore store = mock(AtomicBanknoteIssuanceStore.class);
        Amount overCap = Amount.of(new java.math.BigDecimal("1000000000000001"), 2);

        EconomyResult<Amount> result =
                service.issueBanknote(account, "dollar", overCap, nonce, store);

        assertTrue(result.isFailure()
                        && result.error() == com.smile.aceeconomy.domain.EconomyError.AMOUNT_EXCEEDS_CAP,
                "over-cap issue must refuse with AMOUNT_EXCEEDS_CAP before any commit, got " + result);
        verify(store, times(0)).commitDebit(any(UUID.class),
                any(com.smile.aceeconomy.domain.Transaction.class), any(DebtPolicy.class));
    }
}
