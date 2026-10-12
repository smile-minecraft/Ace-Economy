package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.application.BanknoteIssuanceRecovery;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.infrastructure.persistence.InMemoryBanknoteIssuanceStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-window decision table for durable issuance recovery (phase-1 red).
 *
 * <p>Each test plants one issuance record at the exact durable state a crash
 * would leave behind, then asserts the recovery decision plus the value
 * conservation that must hold (a refunded nonce is consumed and can never be
 * redeemed; a delivered or redeemed note is never refunded). Planting uses the
 * seeding-only {@code plantForTest} seam with a nonce-bound debit binding
 * ({@code banknote:issue:&lt;nonce&gt;}), never a standalone money mark: there
 * is no production path that flips to {@code DEBIT_COMMITTED} or
 * {@code FINAL_VOID_REFUNDED} without the single-transaction atomic commit.
 * The phase-1 recovery skeleton returns fixed values, so every decision
 * assertion below fails until phase 2 implements the table in the class
 * javadoc of {@link BanknoteIssuanceRecovery}.</p>
 */
class BanknoteIssuanceCrashWindowTest {

    private static BanknoteIssuance prepared(UUID nonce, UUID account) {
        return BanknoteIssuance.prepared(nonce, account, "dollar",
                Amount.of(100L, 2), Instant.now());
    }

    private static InMemoryBanknoteIssuanceStore storeWith(BanknoteIssuance issuance) {
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        store.prepare(issuance);
        return store;
    }

    /**
     * Plant the exact durable state an atomic debit commit would leave behind:
     * {@code DEBIT_COMMITTED} with the debit id and the nonce-bound reason.
     * No standalone production mark is used.
     */
    private static InMemoryBanknoteIssuanceStore storeWithDebitCommitted(UUID nonce, UUID account) {
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        store.prepare(prepared(nonce, account));
        UUID debitId = UUID.randomUUID();
        BanknoteIssuance committed = new BanknoteIssuance(nonce, account, "dollar",
                Amount.of(100L, 2), BanknoteIssuanceStatus.DEBIT_COMMITTED,
                debitId.toString(), BanknoteIssuanceReasons.issueReason(nonce),
                Instant.now(), Instant.now());
        store.plantForTest(committed);
        return store;
    }

    @Test
    void preparedCrashAbortsWithoutRefund() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWith(prepared(nonce, account));
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        assertEquals(BanknoteIssuanceRecovery.RecoveryDecision.ABORT_WITHOUT_REFUND,
                recovery.decideFor(store.findByNonce(nonce).orElseThrow()),
                "PREPARED crash left no deduction: recovery must abort with no refund");

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(1, report.scanned(), "one unfinished record must be scanned");
        assertEquals(1, report.aborted(), "PREPARED crash must close as aborted");
        assertEquals(0, report.refunded(), "aborting a deduction-free record must never refund");
        assertEquals(BanknoteIssuanceStatus.ABORTED,
                store.findByNonce(nonce).orElseThrow().status());
    }

    @Test
    void debitCommittedCrashRefundsAndVoids() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWithDebitCommitted(nonce, account);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        assertEquals(BanknoteIssuanceRecovery.RecoveryDecision.REFUND_AND_VOID,
                recovery.decideFor(store.findByNonce(nonce).orElseThrow()),
                "DEBIT_COMMITTED crash with a debit proof must refund and void together");

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(1, report.refunded(), "proven deduction must be refunded exactly once");
        assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                store.findByNonce(nonce).orElseThrow().status(),
                "refunded record must be final with its nonce voided, never redeemable");
    }

    @Test
    void mintedBeforeMarkCrashRefundsAndVoids() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWithDebitCommitted(nonce, account);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        assertEquals(BanknoteIssuanceRecovery.RecoveryDecision.REFUND_AND_VOID,
                recovery.decideFor(store.findByNonce(nonce).orElseThrow()),
                "crash after debit but before the delivery mark keeps the debit proof:"
                        + " recovery must refund and void, never leave the charge standing");

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(1, report.refunded());
        assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                store.findByNonce(nonce).orElseThrow().status());
    }

    @Test
    void deliveryStartedCrashRefundsAndVoids() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWithDebitCommitted(nonce, account);
        store.markDeliveryStarted(nonce);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        assertEquals(BanknoteIssuanceRecovery.RecoveryDecision.REFUND_AND_VOID,
                recovery.decideFor(store.findByNonce(nonce).orElseThrow()),
                "DELIVERY_STARTED crash with a debit proof and an unconsumed nonce"
                        + " must void and refund in the same commit (no blind refund"
                        + " that leaves a redeemable note)");

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(1, report.refunded());
        assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                store.findByNonce(nonce).orElseThrow().status());
    }

    @Test
    void deliveryStartedButRedeemedNeverRefunds() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWithDebitCommitted(nonce, account);
        store.markDeliveryStarted(nonce);
        store.markRedeemed(nonce);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        assertEquals(BanknoteIssuanceRecovery.RecoveryDecision.ALREADY_FINAL,
                recovery.decideFor(store.findByNonce(nonce).orElseThrow()),
                "an already redeemed note must never be refunded:"
                        + " refunding it would pay the same money twice");

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(0, report.refunded(), "redeemed records must yield no refund");
        assertEquals(BanknoteIssuanceStatus.REDEEMED,
                store.findByNonce(nonce).orElseThrow().status());
    }

    @Test
    void deliveredTicketNeverRefunds() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWithDebitCommitted(nonce, account);
        store.markDeliveryStarted(nonce);
        store.markDelivered(nonce);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        assertEquals(BanknoteIssuanceRecovery.RecoveryDecision.ALREADY_FINAL,
                recovery.decideFor(store.findByNonce(nonce).orElseThrow()),
                "a confirmed delivery must never be refunded");

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(0, report.refunded(), "delivered records must yield no refund");
        assertTrue(store.listUnfinished().isEmpty(),
                "delivered records must not stay pending");
    }

    @Test
    void completedHandoverLeavesNoPendingAndMarksRecoveryComplete() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = storeWithDebitCommitted(nonce, account);
        store.markDeliveryStarted(nonce);
        store.markDelivered(nonce);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(0, report.refunded());
        assertTrue(recovery.isRecoveryComplete(),
                "startup recovery must report complete once the run finishes");
    }
}
