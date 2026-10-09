package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Transaction;

import java.util.UUID;

/**
 * Atomic single-record import boundary. Implementations must consume the idempotency key,
 * apply the balance effect (creating the account first when allowed) and persist the audit
 * record inside ONE storage transaction, so an audit failure rolls back the whole record
 * with no half-applied balance and no burned key.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #importPrepared} is first-writer-wins per key: concurrent or repeated
 *       applications of the same key yield exactly one {@code COMMITTED}; every other
 *       attempt observes {@code REPLAY} with no state change. Concurrency scope is
 *       backend-specific, matching {@link AtomicRedemptionStore}: SQL/SQLite (JDBC
 *       transaction + unique nonce primary key) provides cross-process first-writer-wins;
 *       JSON ({@code ReentrantLock} + copy-on-write + atomic rename) guarantees only a
 *       single backend instance / single JVM.</li>
 *   <li>An unknown account with {@code createMissing == false} yields
 *       {@code ACCOUNT_MISSING} and intentionally leaves the key unconsumed, so a later
 *       retry with creation enabled (or once the account exists) applies normally instead
 *       of reporting a duplicate skip.</li>
 *   <li>An unknown account with {@code createMissing == true} is created atomically with
 *       the key consumption and the audit record: either the new account, the record and
 *       the consumed key are all durable, or none of them are.</li>
 *   <li>For an existing account the balance effect is derived from the LIVE row inside the
 *       same transaction (a {@code SET} to the prepared target), never by overwriting the
 *       row with a stale caller snapshot: a general transaction that commits first is
 *       observed, never silently lost. The returned {@code COMMITTED} balances are the
 *       authoritative ones; callers rebuild their audit view from them instead of trusting
 *       a pre-read.</li>
 *   <li>A committed application persists the updated balance, one audit record of type
 *       {@code SET}, and the consumed key together — all-or-none; a storage failure throws
 *       {@link PersistenceException} instead of deciding. There is deliberately no
 *       compensating rollback on top: compensation that can itself fail is what produced
 *       the swallowed half-states this boundary replaces.</li>
 * </ul>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per backend alongside
 * the other ports of the same backend instance.</p>
 */
public interface AtomicImportStore {

    /**
     * Atomically consume {@code nonce}, set the account balance to the prepared target
     * (creating the account when missing and allowed) and persist the audit record.
     *
     * @param nonce the single-use import key for this record
     * @param prepared the account carrying the target balance and, for a missing account,
     *                 the full initial balances plus owner name; for an existing account the
     *                 balances are only a hint — the live row decides
     * @param preparedTx the audit record to append (type {@code SET}); its id, timestamp and
     *                   reason are persisted as-is, its before/after are replaced with the
     *                   authoritative live-derived balances
     * @param createMissing whether a missing account may be created as part of the commit
     * @return the decided outcome; never {@code null}
     * @throws PersistenceException when the storage cannot decide (failure is not replay)
     */
    ImportCommitResult importPrepared(UUID nonce, Account prepared, Transaction preparedTx,
                                      boolean createMissing) throws PersistenceException;
}
