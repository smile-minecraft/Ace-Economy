package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.BanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.IssuanceRefundResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-JVM reference implementation of {@link BanknoteIssuanceStore} plus a
 * phase-1 atomic stub.
 *
 * <p>Progress markers here enforce the legal-state compare-and-swap in code:
 * delivery, abort, quarantine and redeem observations each allow only their
 * documented source states. Money-bearing states ({@code DEBIT_COMMITTED},
 * {@code FINAL_VOID_REFUNDED}) are never reached through this port; they
 * commit only through {@link #commitDebit} and {@link #commitRecoveryRefund}
 * on the same backend instance in phase 2. The phase-1 stubs below always
 * refuse with a fixed non-committed outcome so red tests fail at assertions,
 * never by inventing money.</p>
 *
 * <p>Test-only seeding goes through {@link #plantForTest}: crash-window tests
 * plant the exact durable state a crash would leave behind without calling a
 * production money path. It is not durable across restarts: restart tests must
 * use a real backend, never this class.</p>
 */
public final class InMemoryBanknoteIssuanceStore implements AtomicBanknoteIssuanceStore {

    private final ConcurrentHashMap<UUID, BanknoteIssuance> records = new ConcurrentHashMap<>();
    private final java.util.function.Supplier<Instant> clock;

    public InMemoryBanknoteIssuanceStore() {
        this(Instant::now);
    }

    public InMemoryBanknoteIssuanceStore(java.util.function.Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public BanknoteIssuance prepare(BanknoteIssuance issuance) throws PersistenceException {
        Objects.requireNonNull(issuance, "issuance");
        if (issuance.status() != BanknoteIssuanceStatus.PREPARED) {
            throw new PersistenceException("prepare requires PREPARED status, got " + issuance.status());
        }
        if (issuance.debitTransactionId() != null || issuance.debitProof() != null) {
            throw new PersistenceException("prepare must carry no debit evidence");
        }
        BanknoteIssuance existing = records.putIfAbsent(issuance.nonce(), issuance);
        if (existing != null) {
            // Amount, currency and account are immutable per nonce: a replay
            // with a different payload is rejected, never merged.
            if (!existing.accountId().equals(issuance.accountId())
                    || !existing.currencyId().equals(issuance.currencyId())
                    || !existing.amount().equals(issuance.amount())) {
                throw new PersistenceException(
                        "issuance payload mismatch on replay for nonce " + issuance.nonce());
            }
            return existing;
        }
        return issuance;
    }

    /**
     * Seeding for crash scenarios: insert the exact durable record a crash would leave
     * behind, bypassing the production progress guards. Production code never
     * calls this; crash-window cases use it to plant one record per case.
     */
    public BanknoteIssuance plantForTest(BanknoteIssuance issuance) {
        Objects.requireNonNull(issuance, "issuance");
        records.put(issuance.nonce(), issuance);
        return issuance;
    }

    @Override
    public BanknoteIssuance markDeliveryStarted(UUID nonce) throws PersistenceException {
        return transition(nonce, BanknoteIssuanceStatus.DELIVERY_STARTED, null, null);
    }

    @Override
    public BanknoteIssuance markDelivered(UUID nonce) throws PersistenceException {
        return transition(nonce, BanknoteIssuanceStatus.DELIVERED, null, null);
    }

    @Override
    public BanknoteIssuance markRedeemed(UUID nonce) throws PersistenceException {
        return transition(nonce, BanknoteIssuanceStatus.REDEEMED, null, null);
    }

    @Override
    public BanknoteIssuance markAborted(UUID nonce, String reason) throws PersistenceException {
        if (reason == null || reason.isBlank()) {
            throw new PersistenceException("abort reason missing");
        }
        return transition(nonce, BanknoteIssuanceStatus.ABORTED, null, null);
    }

    @Override
    public BanknoteIssuance markQuarantined(UUID nonce, String diagnosis) throws PersistenceException {
        if (diagnosis == null || diagnosis.isBlank()) {
            throw new PersistenceException("quarantine diagnosis missing");
        }
        return transition(nonce, BanknoteIssuanceStatus.QUARANTINED, null, null);
    }

    @Override
    public Optional<BanknoteIssuance> findByNonce(UUID nonce) throws PersistenceException {
        Objects.requireNonNull(nonce, "nonce");
        return Optional.ofNullable(records.get(nonce));
    }

    @Override
    public List<BanknoteIssuance> listUnfinished() throws PersistenceException {
        List<BanknoteIssuance> out = new ArrayList<>();
        for (BanknoteIssuance issuance : records.values()) {
            if (issuance.status().isUnfinished()) {
                out.add(issuance);
            }
        }
        return out;
    }

    /**
     * Reference atomic debit for semantics-only tests: verifies the nonce
     * binding, payload immutability and debit-id uniqueness, then flips
     * {@code PREPARED -> DEBIT_COMMITTED}. There is no balance here, so the
     * returned before/after are zero placeholders; durable backends commit
     * the real deduction together with the flip.
     */
    @Override
    public IssuanceDebitResult commitDebit(UUID nonce, Transaction preparedWithdrawal,
                                           DebtPolicy debtPolicy) throws PersistenceException {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(preparedWithdrawal, "preparedWithdrawal");
        Objects.requireNonNull(debtPolicy, "debtPolicy");
        BanknoteIssuance stored = records.get(nonce);
        if (stored == null) {
            return IssuanceDebitResult.inconsistentProof();
        }
        if (stored.status() != BanknoteIssuanceStatus.PREPARED) {
            String replayBinding =
                    com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                            .checkDebitBinding(stored, preparedWithdrawal, nonce);
            if (replayBinding == null
                    && stored.debitTransactionId() != null
                    && stored.debitTransactionId().equals(preparedWithdrawal.id().toString())) {
                return IssuanceDebitResult.replay(stored);
            }
            return IssuanceDebitResult.inconsistentProof();
        }
        String bindingError =
                com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                        .checkDebitBinding(stored, preparedWithdrawal, nonce);
        if (bindingError != null) {
            return IssuanceDebitResult.inconsistentProof();
        }
        String newDebitId = preparedWithdrawal.id().toString();
        for (BanknoteIssuance other : records.values()) {
            if (!other.nonce().equals(nonce)
                    && newDebitId.equals(other.debitTransactionId())) {
                return IssuanceDebitResult.inconsistentProof();
            }
        }
        if (!com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                .scalesAgree(stored.amount(), preparedWithdrawal.amount())) {
            return IssuanceDebitResult.scaleMismatch();
        }
        if (com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                .capExceeded(stored.amount(), preparedWithdrawal.amount())) {
            return IssuanceDebitResult.capExceeded();
        }
        BanknoteIssuance updated = stored.withStatus(BanknoteIssuanceStatus.DEBIT_COMMITTED,
                preparedWithdrawal.id().toString(),
                com.smile.aceeconomy.domain.BanknoteIssuanceReasons.issueReason(nonce),
                clock.get());
        records.put(nonce, updated);
        com.smile.aceeconomy.domain.Amount zero =
                com.smile.aceeconomy.domain.Amount.zero(preparedWithdrawal.amount().scale());
        return IssuanceDebitResult.committed(zero, zero,
                preparedWithdrawal.id(), updated);
    }

    /**
     * Reference atomic void+refund for semantics-only tests: verifies the
     * refund binding and the durable debit proof presence, then flips to
     * {@code FINAL_VOID_REFUNDED}. No money moves here; durable backends
     * consume the nonce plus refund plus final-mark together.
     */
    @Override
    public IssuanceRefundResult commitRecoveryRefund(UUID nonce, Transaction preparedRefund,
                                                     DebtPolicy debtPolicy) throws PersistenceException {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(preparedRefund, "preparedRefund");
        Objects.requireNonNull(debtPolicy, "debtPolicy");
        BanknoteIssuance stored = records.get(nonce);
        if (stored == null) {
            return IssuanceRefundResult.inconsistentProof();
        }
        if (stored.status() == BanknoteIssuanceStatus.FINAL_VOID_REFUNDED) {
            return IssuanceRefundResult.replay(stored);
        }
        if (stored.status() == BanknoteIssuanceStatus.REDEEMED) {
            return IssuanceRefundResult.alreadyRedeemed(stored, null);
        }
        if (stored.status() != BanknoteIssuanceStatus.DEBIT_COMMITTED
                && stored.status() != BanknoteIssuanceStatus.DELIVERY_STARTED) {
            return IssuanceRefundResult.inconsistentProof();
        }
        String refundBinding =
                com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                        .checkRefundBinding(stored, preparedRefund, nonce);
        if (refundBinding != null) {
            return IssuanceRefundResult.inconsistentProof();
        }
        if (stored.debitTransactionId() == null
                || stored.debitProof() == null
                || stored.debitProof().isBlank()
                || !com.smile.aceeconomy.domain.BanknoteIssuanceReasons
                        .isIssueReasonFor(stored.debitProof(), nonce)) {
            return IssuanceRefundResult.inconsistentProof();
        }
        if (!com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                .scalesAgree(stored.amount(), preparedRefund.amount())) {
            return IssuanceRefundResult.scaleMismatch();
        }
        if (com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                .capExceeded(stored.amount(), preparedRefund.amount())) {
            return IssuanceRefundResult.capExceeded();
        }
        BanknoteIssuance finalIssuance = stored.withStatus(
                BanknoteIssuanceStatus.FINAL_VOID_REFUNDED, clock.get());
        records.put(nonce, finalIssuance);
        com.smile.aceeconomy.domain.Amount zero =
                com.smile.aceeconomy.domain.Amount.zero(preparedRefund.amount().scale());
        return IssuanceRefundResult.committed(zero, zero,
                preparedRefund.id(), finalIssuance);
    }

    private synchronized BanknoteIssuance transition(UUID nonce, BanknoteIssuanceStatus next,
                                                     String debitTransactionId, String debitProof)
            throws PersistenceException {
        Objects.requireNonNull(nonce, "nonce");
        BanknoteIssuance current = records.get(nonce);
        if (current == null) {
            throw new PersistenceException("unknown issuance nonce " + nonce);
        }
        if (current.status().isTerminal()
                && !(current.status() == BanknoteIssuanceStatus.DELIVERED
                        && next == BanknoteIssuanceStatus.REDEEMED)) {
            throw new PersistenceException("issuance " + nonce + " already terminal (" + current.status() + ")");
        }
        if (!allows(current.status(), next)) {
            throw new PersistenceException("illegal issuance transition "
                    + current.status() + " -> " + next + " for nonce " + nonce);
        }
        BanknoteIssuance updated = current.withStatus(next, debitTransactionId, debitProof, clock.get());
        records.put(nonce, updated);
        return updated;
    }

    private static boolean allows(BanknoteIssuanceStatus from, BanknoteIssuanceStatus to) {
        return switch (to) {
            case DELIVERY_STARTED -> from == BanknoteIssuanceStatus.DEBIT_COMMITTED;
            case DELIVERED -> from == BanknoteIssuanceStatus.DELIVERY_STARTED;
            case REDEEMED -> from == BanknoteIssuanceStatus.DEBIT_COMMITTED
                    || from == BanknoteIssuanceStatus.DELIVERY_STARTED
                    || from == BanknoteIssuanceStatus.DELIVERED;
            case ABORTED -> from == BanknoteIssuanceStatus.PREPARED;
            case QUARANTINED -> from.isUnfinished();
            default -> false;
        };
    }
}
