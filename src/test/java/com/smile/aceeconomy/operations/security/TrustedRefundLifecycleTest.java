package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.operations.internal.FailedMintRefund;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.internal.TrustedRefundLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trusted-refund claim lives at most once: without a bound ledger the
 * refund fails closed, a spent or uncertain nonce can never authorize again,
 * a re-authorize with changed parameters is refused, concurrent claims admit
 * at most one, and only a definitely-uncommitted failure releases the same
 * claim for retry.
 */
class TrustedRefundLifecycleTest {

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    @Test
    @DisplayName("refund without a bound ledger fails closed with no money movement")
    void refundWithoutLedgerFailsClosed() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        h.service().bindSecurityControl(new SecurityControlService());

        FailedMintRefund proof = FailedMintRefund.proven(
                alice, "dollar", Amount.of(200, 2), UUID.randomUUID());
        EconomyResult<Amount> r = h.service().refundFailedMint(proof);

        assertTrue(r.isFailure(), "refund without a bound ledger must fail closed");
        assertEquals(Amount.of(1000, 2), h.service().getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("a spent nonce can never authorize or consume again")
    void spentNonceNeverReauthorizes() {
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();

        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)));
        ledger.markRefunded(nonce);

        assertThrows(IllegalStateException.class,
                () -> ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2)),
                "a spent nonce must never authorize again");
        assertFalse(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)),
                "a spent nonce must never consume again");
    }

    @Test
    @DisplayName("re-authorizing the same nonce with changed parameters is refused")
    void reauthorizeWithChangedParamsRefused() {
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();

        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        assertThrows(IllegalStateException.class,
                () -> ledger.authorize(nonce, alice, "dollar", Amount.of(999, 2)),
                "the same nonce with a different amount must be refused");
        assertThrows(IllegalStateException.class,
                () -> ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2)),
                "the same nonce must not grant a second claim even with identical parameters");
    }

    @Test
    @DisplayName("concurrent claims admit at most one")
    void concurrentClaimsAdmitAtMostOne() throws Exception {
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> {
                ready.countDown();
                assertTrue(fire.await(10, TimeUnit.SECONDS), "claimants must start together");
                return ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2));
            });
            Future<Boolean> second = pool.submit(() -> {
                ready.countDown();
                assertTrue(fire.await(10, TimeUnit.SECONDS), "claimants must start together");
                return ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both claimants must be ready");
            fire.countDown();
            int granted = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, granted, "exactly one concurrent claim may be granted");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("only a definitely-uncommitted failure releases the same claim for retry")
    void definiteFailureReleasesSameClaim() {
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();

        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)));
        ledger.releaseClaim(nonce, alice, "dollar", Amount.of(200, 2));

        assertTrue(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)),
                "the same claim must be retryable after a definitely-uncommitted failure");
    }

    @Test
    @DisplayName("releasing with tampered parameters is refused and keeps the claim")
    void releaseWithTamperedParamsRefused() {
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();

        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)));
        assertThrows(IllegalStateException.class,
                () -> ledger.releaseClaim(nonce, alice, "dollar", Amount.of(999, 2)),
                "releasing with a different amount must be refused");
        ledger.releaseClaim(nonce, alice, "dollar", Amount.of(200, 2));
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)),
                "the original claim must still be retryable after a tampered release");
    }

    @Test
    @DisplayName("an uncertain claim is terminal and never consumable again")
    void uncertainClaimNeverConsumable() {
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();

        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)));
        ledger.markUncertain(nonce);

        assertFalse(ledger.tryConsume(nonce, alice, "dollar", Amount.of(200, 2)),
                "an uncertain claim must never be consumed again");
        assertThrows(IllegalStateException.class,
                () -> ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2)),
                "an uncertain nonce must never authorize again");
        assertThrows(IllegalStateException.class,
                () -> ledger.releaseClaim(nonce, alice, "dollar", Amount.of(200, 2)),
                "an uncertain claim must never be released for retry");
    }

    @Test
    @DisplayName("service success settles the nonce so the proof cannot refund twice")
    void serviceSuccessSettlesNonce() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        h.service().withdraw(alice, "dollar", Amount.of(200, 2));
        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        h.service().bindSecurityControl(control);
        h.service().bindRefundLedger(ledger);

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        FailedMintRefund proof = FailedMintRefund.proven(alice, "dollar", Amount.of(200, 2), nonce);
        EconomyResult<Amount> first = h.service().refundFailedMint(proof);
        assertTrue(first.isSuccess(), "the authorized refund must succeed");

        EconomyResult<Amount> second = h.service().refundFailedMint(proof);
        assertTrue(second.isFailure(), "the settled proof must not refund twice");
        assertThrows(IllegalStateException.class,
                () -> ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2)),
                "the service must settle the nonce so it can never authorize again");
        assertEquals(Amount.of(1000, 2), h.service().getBalance(alice, "dollar").value());
    }
}
