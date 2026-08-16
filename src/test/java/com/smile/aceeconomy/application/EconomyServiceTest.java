package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EconomyServiceTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    // ---------------- single-account use cases ----------------

    @Test
    @DisplayName("new account starts at the configured start balance")
    void startBalance() {
        EconomyService svc = newHarness(DebtPolicy.disabled()).service;
        svc.createAccount(alice, "Alice");
        EconomyResult<Amount> r = svc.getBalance(alice, "dollar");
        assertTrue(r.isSuccess());
        assertEquals(Amount.of(1000, 2), r.value());
    }

    @Test
    @DisplayName("non-positive deposit is rejected without changing balance")
    void negativeDeposit() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(-50, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.INVALID_AMOUNT, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("deposit increases balance and emits one audit record")
    void depositAudit() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(250, 2));
        assertTrue(r.isSuccess());
        assertEquals(Amount.of(1250, 2), r.value());
        assertEquals(1, h.audit.recorded().size());
        assertEquals(TransactionType.DEPOSIT, h.audit.recorded().get(0).type());
    }

    @Test
    @DisplayName("withdraw decreases balance")
    void withdraw() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", Amount.of(400, 2));
        assertTrue(r.isSuccess());
        assertEquals(Amount.of(600, 2), r.value());
    }

    @Test
    @DisplayName("insufficient withdraw (debt disabled) fails, balance unchanged")
    void insufficientDebtDisabled() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", Amount.of(2000, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.INSUFFICIENT_FUNDS, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("debt enabled: withdraw into negative within limit succeeds; beyond limit fails")
    void debtEnabledBoundaries() {
        EconomyTestHarness h = newHarness(DebtPolicy.enabled(Amount.of(500, 2)));
        h.service.createAccount(alice, "Alice");
        assertTrue(h.service.withdraw(alice, "dollar", Amount.of(1200, 2)).isSuccess());
        assertEquals(Amount.of(-200, 2), h.service.getBalance(alice, "dollar").value());

        // top up back then exceed limit
        h.service.deposit(alice, "dollar", Amount.of(1200, 2));
        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", Amount.of(2000, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.DEBT_LIMIT_EXCEEDED, r.error());
    }

    @Test
    @DisplayName("setBalance negative with debt disabled is rejected, balance unchanged")
    void setBalanceDebtDisabled() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        EconomyResult<Amount> r = h.service.setBalance(alice, "dollar", Amount.of(-1, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.DEBT_DISABLED, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("unknown currency and unknown account typed failures")
    void notFoundFailures() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        assertEquals(EconomyError.CURRENCY_NOT_FOUND, h.service.getBalance(alice, "ghost").error());
        assertEquals(EconomyError.ACCOUNT_NOT_FOUND, h.service.getBalance(bob, "dollar").error());
    }

    // ---------------- same-account / transfer ----------------

    @Test
    @DisplayName("same-account transfer returns SAME_ACCOUNT, no mutation")
    void sameAccountTransfer() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        EconomyResult<TransferResult> r = h.service.transfer(alice, alice, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.SAME_ACCOUNT, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("transfer moves money and audits out-then-in in fixed order")
    void transferAuditOrder() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        EconomyResult<TransferResult> r = h.service.transfer(alice, bob, "dollar", Amount.of(100, 2));
        assertTrue(r.isSuccess());
        assertEquals(Amount.of(900, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1100, 2), h.service.getBalance(bob, "dollar").value());

        assertEquals(2, h.audit.recorded().size());
        assertEquals(TransactionType.TRANSFER_OUT, h.audit.recorded().get(0).type());
        assertEquals(TransactionType.TRANSFER_IN, h.audit.recorded().get(1).type());
        assertEquals(alice, h.audit.recorded().get(0).accountId());
        assertEquals(bob, h.audit.recorded().get(1).accountId());
    }

    // ---------------- pre-commit cancellation ----------------

    @Test
    @DisplayName("pre-commit cancellation blocks mutation")
    void cancellationBlocksMutation() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        h.publisher.register(event -> event.cancel());
        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.TRANSACTION_CANCELLED, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertTrue(h.audit.recorded().isEmpty());
    }

    @Test
    @DisplayName("pre-commit cancellation blocks transfer mutation entirely")
    void transferCancellation() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        h.publisher.register(event -> event.cancel());
        EconomyResult<TransferResult> r = h.service.transfer(alice, bob, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.TRANSACTION_CANCELLED, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(bob, "dollar").value());
    }

    // ---------------- audit failure not swallowed ----------------

    @Test
    @DisplayName("audit failure is surfaced, not swallowed; balance still commits")
    void auditFailureNotSwallowed() {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        h.audit.setFailOnNextRecord(true);
        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(100, 2));
        assertTrue(r.isSuccess());
        assertTrue(r.auditFailure().isPresent(), "audit error must be surfaced");
        // balance mutation committed before audit
        assertEquals(Amount.of(1100, 2), h.service.getBalance(alice, "dollar").value());
    }

    // ---------------- concurrency ----------------

    @Test
    @DisplayName("concurrent transfers conserve total money and avoid deadlock (deterministic start)")
    void concurrentTransfersNoLostUpdate() throws Exception {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        h.service.createAccount(carol, "Carol");
        UUID[] accounts = {alice, bob, carol};

        int threads = 8;
        int transfersPerThread = 50;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            var tasks = new java.util.ArrayList<Future<Void>>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                tasks.add(exec.submit(() -> {
                    barrier.await();
                    for (int k = 0; k < transfersPerThread; k++) {
                        UUID from = accounts[(tid + k) % 3];
                        UUID to = accounts[(tid + k + 1) % 3];
                        h.service.transfer(from, to, "dollar", Amount.of(1, 2));
                    }
                    return (Void) null;
                }));
            }
            for (Future<Void> f : tasks) {
                f.get(); // propagate any exception
            }
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS), "no deadlock: tasks completed");
        }

        Amount total = Amount.zero(2);
        for (UUID u : accounts) {
            total = total.add(h.service.getBalance(u, "dollar").value());
        }
        assertEquals(Amount.of(3000, 2), total, "total money must be conserved");
    }

    @Test
    @DisplayName("cross transfer A->B and B->A simultaneously does not deadlock")
    void crossTransferNoDeadlock() throws Exception {
        EconomyTestHarness h = newHarness(DebtPolicy.disabled());
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            var f1 = exec.submit(() -> {
                barrier.await();
                for (int i = 0; i < 100; i++) {
                    h.service.transfer(alice, bob, "dollar", Amount.of(1, 2));
                }
                return (Void) null;
            });
            var f2 = exec.submit(() -> {
                barrier.await();
                for (int i = 0; i < 100; i++) {
                    h.service.transfer(bob, alice, "dollar", Amount.of(1, 2));
                }
                return (Void) null;
            });
            f1.get();
            f2.get();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS), "no deadlock");
        }
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(bob, "dollar").value());
    }

    private EconomyTestHarness newHarness(DebtPolicy policy) {
        return new EconomyTestHarness(policy, Amount.of(1000, 2));
    }
}
