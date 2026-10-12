package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.infrastructure.persistence.InMemoryBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.BanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup recovery barrier and failure behaviour (phase-1 red).
 *
 * <p>Decided contract: recovery runs once on the existing IO scheduler after
 * persistence/application are ready (no new pool, no region blocking), new
 * managed issuance and managed redemption wait for
 * {@link BanknoteIssuanceRecovery#isRecoveryComplete()}, and a recovery failure
 * keeps the plugin enabled with a loud diagnosis while keeping pending rows
 * and blocking the related money paths instead of discarding data or throwing
 * the plugin down. The phase-1 stub reports an empty run and never completes,
 * so every assertion below fails until phase 2 implements the barrier.
 * Money-bearing states are never planted through standalone marks: tests use
 * {@code prepare} plus the atomic stubs, so a forged proof string can never
 * invent a deduction.</p>
 */
class BanknoteIssuanceStartupBarrierTest {

    @Test
    void recoveryCompletesAndUnblocksManagedIssuance() {
        UUID nonce = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        store.prepare(BanknoteIssuance.prepared(nonce, UUID.randomUUID(), "dollar",
                Amount.of(100L, 2), Instant.now()));
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();

        assertEquals(1, report.scanned(), "startup run must observe the pending record");
        assertTrue(recovery.isRecoveryComplete(),
                "startup recovery must complete so new managed issuance is unblocked");
    }

    @Test
    void recoveryFailureKeepsPluginReadyWithLoudDiagnosis() {
        BanknoteIssuanceStore failing = new BanknoteIssuanceStore() {
            @Override
            public BanknoteIssuance prepare(BanknoteIssuance issuance) {
                throw new PersistenceException("store down");
            }

            @Override
            public BanknoteIssuance markDeliveryStarted(UUID nonce) {
                throw new PersistenceException("store down");
            }

            @Override
            public BanknoteIssuance markDelivered(UUID nonce) {
                throw new PersistenceException("store down");
            }

            @Override
            public BanknoteIssuance markRedeemed(UUID nonce) {
                throw new PersistenceException("store down");
            }

            @Override
            public BanknoteIssuance markAborted(UUID nonce, String reason) {
                throw new PersistenceException("store down");
            }

            @Override
            public BanknoteIssuance markQuarantined(UUID nonce, String diagnosis) {
                throw new PersistenceException("store down");
            }

            @Override
            public java.util.Optional<BanknoteIssuance> findByNonce(UUID nonce) {
                throw new PersistenceException("store down");
            }

            @Override
            public List<BanknoteIssuance> listUnfinished() {
                throw new PersistenceException("store down");
            }
        };
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(failing);

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();

        assertTrue(report.diagnoses().size() > 0,
                "a failed recovery must leave a loud diagnosis, never a silent pass");
    }

    @Test
    void quarantinedRecordsStayPendingWithoutMoneyMovement() {
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        UUID nonce = UUID.randomUUID();
        store.prepare(BanknoteIssuance.prepared(nonce, UUID.randomUUID(), "dollar",
                Amount.of(100L, 2), Instant.now()));
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();

        assertEquals(1, report.scanned(), "startup run must observe the pending record");
        assertEquals(report.scanned(),
                report.refunded() + report.aborted() + report.quarantined(),
                "every scanned record must resolve to exactly one counted outcome;"
                        + " proof-less records quarantine with no money movement");
    }
}
