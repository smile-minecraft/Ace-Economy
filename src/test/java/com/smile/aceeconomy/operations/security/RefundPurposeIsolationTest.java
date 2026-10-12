package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.operations.internal.FailedMintRefund;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.internal.TrustedRefundLedger;
import com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose;
import com.smile.aceeconomy.operations.internal.UncertainDeliveryCompensation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refund purposes never cross: a failed-mint authorization cannot fund an
 * uncertain void+refund and vice versa. A refused cross-purpose claim leaves
 * the correct authorization untouched, and same-purpose retry keeps working.
 */
class RefundPurposeIsolationTest {

    @Test
    @DisplayName("failed-mint authorization cannot be consumed as an uncertain void")
    void failedMintCannotFundUncertain(@TempDir Path dir) {
        Path file = dir.resolve("purpose-failed.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID alice = UUID.randomUUID();
        backend.create(alice, "Alice", Map.of("dollar", Amount.of(1000, 2)));
        EconomyTestHarness h = new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
        EconomyService economy = new EconomyService(h.currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, java.time.Instant::now,
                new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        economy.bindSecurityControl(new SecurityControlService());
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindRefundLedger(ledger);

        UUID nonce = UUID.randomUUID();
        Amount charge = Amount.of(200, 2);
        ledger.authorize(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT);

        EconomyResult<Amount> cross = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation");
        assertTrue(cross.isFailure(), "cross-purpose consume must be refused");
        assertEquals(0, Amount.of(1000, 2).compareTo(backend.load(alice).orElseThrow().balanceOf("dollar")),
                "a refused cross-purpose claim must move no money");
        assertFalse(backend.isConsumed(nonce), "a refused cross-purpose claim must not burn the nonce");

        // The same ledger entry for the same nonce stays authorized for its own
        // purpose: the correct refund with the identical nonce still works
        // exactly once, then the nonce is spent.
        EconomyResult<Amount> correct = economy.refundFailedMint(
                FailedMintRefund.proven(alice, "dollar", charge, nonce));
        assertTrue(correct.isSuccess(),
                "the same nonce must still refund for its own purpose after a cross refusal: "
                        + correct.message());
        assertEquals(0, Amount.of(1200, 2).compareTo(backend.load(alice).orElseThrow().balanceOf("dollar")),
                "the correct refund must credit exactly once");
        EconomyResult<Amount> repeat = economy.refundFailedMint(
                FailedMintRefund.proven(alice, "dollar", charge, nonce));
        assertTrue(repeat.isFailure(), "the same nonce must be one-use after the correct refund");
    }

    @Test
    @DisplayName("uncertain authorization cannot be consumed as a plain refund")
    void uncertainCannotFundPlainRefund(@TempDir Path dir) {
        Path file = dir.resolve("purpose-uncertain.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID alice = UUID.randomUUID();
        backend.create(alice, "Alice", Map.of("dollar", Amount.of(1000, 2)));
        EconomyTestHarness h = new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
        EconomyService economy = new EconomyService(h.currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, java.time.Instant::now,
                new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        economy.bindSecurityControl(new SecurityControlService());
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindRefundLedger(ledger);

        Amount charge = Amount.of(200, 2);
        assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());
        assertEquals(0, Amount.of(800, 2).compareTo(backend.load(alice).orElseThrow().balanceOf("dollar")));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", charge, RefundPurpose.DELIVERY_UNCERTAIN);

        EconomyResult<Amount> cross = economy.refundFailedMint(
                FailedMintRefund.proven(alice, "dollar", charge, nonce));
        assertTrue(cross.isFailure(), "uncertain authorization must not fund a plain refund");
        assertEquals(0, Amount.of(800, 2).compareTo(backend.load(alice).orElseThrow().balanceOf("dollar")),
                "a refused cross-purpose claim must move no money");
        assertFalse(backend.isConsumed(nonce),
                "a refused cross-purpose claim must not burn the nonce");

        // The same nonce stays authorized for its own uncertain purpose: the
        // correct void+refund with the identical ledger and nonce succeeds
        // exactly once, then the nonce is terminal.
        EconomyResult<Amount> correct = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation");
        assertTrue(correct.isSuccess(),
                "the same nonce must still void+refund for its own purpose after a cross refusal: "
                        + correct.message());
        assertEquals(0, Amount.of(1000, 2).compareTo(backend.load(alice).orElseThrow().balanceOf("dollar")),
                "the correct void+refund must credit exactly once");
        assertTrue(backend.isConsumed(nonce), "the voided note must be dead after the correct refund");
        EconomyResult<Amount> repeat = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation");
        assertTrue(repeat.isFailure(), "the same nonce must be one-use after the correct void+refund");
    }

    @Test
    @DisplayName("same-purpose retry still works once and terminals stay terminal")
    void samePurposeRetryAndTerminal() {
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        Amount charge = Amount.of(200, 2);

        ledger.authorize(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT);
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT));
        ledger.releaseClaim(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT);
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT),
                "same-purpose release must allow exactly one retry");
        ledger.markRefunded(nonce);
        assertFalse(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT),
                "a spent nonce must never consume again");

        UUID uncertain = UUID.randomUUID();
        ledger.authorize(uncertain, alice, "dollar", charge, RefundPurpose.DELIVERY_UNCERTAIN);
        assertTrue(ledger.tryConsume(uncertain, alice, "dollar", charge, RefundPurpose.DELIVERY_UNCERTAIN));
        ledger.markUncertain(uncertain);
        assertFalse(ledger.tryConsume(uncertain, alice, "dollar", charge, RefundPurpose.DELIVERY_UNCERTAIN),
                "an uncertain nonce must never consume again");
    }

    @Test
    @DisplayName("cross-purpose release is refused and keeps the claim")
    void crossPurposeReleaseRefused() {
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        Amount charge = Amount.of(200, 2);

        ledger.authorize(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT);
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT));
        try {
            ledger.releaseClaim(nonce, alice, "dollar", charge, RefundPurpose.DELIVERY_UNCERTAIN);
            assertTrue(false, "cross-purpose release must be refused");
        } catch (IllegalStateException expected) {
            // Expected: purposes must match exactly.
        }
        ledger.releaseClaim(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT);
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT),
                "the original claim must still be retryable after a refused cross release");
    }

    @Test
    @DisplayName("legacy four-arg authorize stays failed-mint only")
    void legacyAuthorizeStaysFailedMint() {
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        UUID nonce = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        Amount charge = Amount.of(200, 2);

        ledger.authorize(nonce, alice, "dollar", charge);
        assertFalse(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.DELIVERY_UNCERTAIN),
                "legacy authorization must not satisfy the uncertain purpose");
        assertTrue(ledger.tryConsume(nonce, alice, "dollar", charge, RefundPurpose.FAILED_MINT),
                "legacy authorization must satisfy the failed-mint purpose");
    }
}
