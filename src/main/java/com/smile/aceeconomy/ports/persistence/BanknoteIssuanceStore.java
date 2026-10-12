package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.BanknoteIssuance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable, per-nonce issuance progress for banknote reissue recovery.
 *
 * <p>Money-bearing states ({@code DEBIT_COMMITTED}, {@code FINAL_VOID_REFUNDED})
 * are never reached through this port. They commit only through
 * {@link AtomicBanknoteIssuanceStore#commitDebit} and
 * {@link AtomicBanknoteIssuanceStore#commitRecoveryRefund} in ONE storage
 * transaction together with the balance and the audit record, so no window
 * exists where a deduction succeeded but the issuance still reads
 * {@code PREPARED}, and no forged proof string can invent a deduction. This
 * port carries only progress that moves no money: prepare, delivery markers,
 * abort, quarantine and the redeem observation. Every marker is a legal-state
 * compare-and-swap enforced in code (not by documentation): an illegal source
 * state throws instead of flipping.</p>
 *
 * <ul>
 *   <li>{@code prepare} is first-writer-wins per nonce: inserting the same nonce
 *       twice yields the existing record without a second deduction right. The
 *       record starts as {@code PREPARED} with no debit evidence. Amount,
 *       currency and account are immutable afterwards: a replay with a
 *       different payload is rejected.</li>
 *   <li>{@code markDeliveryStarted} allows only {@code DEBIT_COMMITTED}
 *       {@code ->} {@code DELIVERY_STARTED} and runs durably before
 *       {@code addItem}; {@code markDelivered} allows only
 *       {@code DELIVERY_STARTED} {@code ->} {@code DELIVERED} and runs only
 *       after {@code addItem} confirms.</li>
 *   <li>{@code markRedeemed} allows {@code DEBIT_COMMITTED},
 *       {@code DELIVERY_STARTED} or {@code DELIVERED} {@code ->} {@code REDEEMED}:
 *       the note was redeemed through the existing first-writer-wins redemption
 *       path, so recovery never refunds it afterwards. The delivered source is
 *       the genuine path: the delivery mark lands before the handover, so a
 *       normally redeemed note reads delivered at credit time. It writes no
 *       money. A redeem observation that fails after the credit committed never
 *       overturns the credit; the nonce is already consumed so the note cannot
 *       be reused, and the record staying delivered keeps refusing an
 *       empty-candidate rollback (conservative).</li>
 *   <li>{@code markAborted} allows only {@code PREPARED -> ABORTED} with no
 *       money movement; {@code markQuarantined} allows any unfinished state
 *       {@code ->} {@code QUARANTINED} with a loud diagnosis and no money
 *       movement.</li>
 *   <li>{@code listUnfinished} returns every non-terminal record for startup
 *       recovery; recovery is idempotent and replays safely.</li>
 *   <li>Malformed stored records fail fast (throw) instead of being dropped or
 *       resolved silently; recovery quarantines them with no money movement.</li>
 * </ul>
 *
 * <p>Storage landing (phase 2): JSON gains an optional additive
 * {@code issuances} object section (absent in old files {@code ->} empty, old
 * readers ignore the new key); SQL gains an additive
 * {@code ace_v2_issuances} table via {@code CREATE TABLE IF NOT EXISTS} with no
 * version bump, following the existing nonce / operation-audit precedent. Both
 * join snapshot / backup / restore bytes and the shared preflight validation;
 * restore keeps the existing control-file isolation. A backup taken while
 * records are pending, followed by a redeem and then a restore of that backup,
 * would resurrect a redeemable note next to an already-credited redemption
 * (double value): operators must never restore a pending backup after
 * redeeming from it — treat pending backups as dangerous, verify the
 * redemption history first, or block the restore.</p>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per
 * backend on the same backend instance that owns accounts, transactions and
 * nonces.</p>
 */
public interface BanknoteIssuanceStore {

    /**
     * Insert a {@code PREPARED} record, first-writer-wins per nonce.
     *
     * @return the stored record (the existing one on replay, unchanged)
     * @throws PersistenceException when the storage cannot decide
     */
    BanknoteIssuance prepare(BanknoteIssuance issuance) throws PersistenceException;

    /**
     * Flip to {@code DELIVERY_STARTED}. Runs durably before {@code addItem}.
     * Allows only {@code DEBIT_COMMITTED -> DELIVERY_STARTED}.
     */
    BanknoteIssuance markDeliveryStarted(UUID nonce) throws PersistenceException;

    /**
     * Flip to {@code DELIVERED}. Runs only after {@code addItem} confirms.
     * Allows only {@code DELIVERY_STARTED -> DELIVERED}.
     */
    BanknoteIssuance markDelivered(UUID nonce) throws PersistenceException;

    /**
     * Flip to {@code REDEEMED} once the note was redeemed through the existing
     * redemption path, so recovery never refunds it afterwards. Allows
     * {@code DEBIT_COMMITTED}, {@code DELIVERY_STARTED} or {@code DELIVERED}
     * {@code ->} {@code REDEEMED}; writes no money.
     */
    BanknoteIssuance markRedeemed(UUID nonce) throws PersistenceException;

    /**
     * Flip a deduction-free {@code PREPARED} record to {@code ABORTED} with no
     * money movement. Allows only {@code PREPARED -> ABORTED}.
     */
    BanknoteIssuance markAborted(UUID nonce, String reason) throws PersistenceException;

    /**
     * Keep an undecidable record for diagnosis with no money movement. Allows
     * any unfinished state {@code -> QUARANTINED}.
     */
    BanknoteIssuance markQuarantined(UUID nonce, String diagnosis) throws PersistenceException;

    /**
     * @return the record for {@code nonce}, or empty when no issuance was ever
     *         prepared (old v2 notes have no row and redeem as before)
     * @throws PersistenceException on corrupt stored records (fail fast)
     */
    Optional<BanknoteIssuance> findByNonce(UUID nonce) throws PersistenceException;

    /**
     * @return every non-terminal record for startup recovery; never null
     * @throws PersistenceException on corrupt stored records (fail fast)
     */
    List<BanknoteIssuance> listUnfinished() throws PersistenceException;
}
