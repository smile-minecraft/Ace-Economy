package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Transaction;

import java.util.List;
import java.util.UUID;

/**
 * Persistence seam for committed transaction / audit records, including the
 * rollback marker.
 *
 * <p>Transaction boundary contract:</p>
 * <ul>
 *   <li>{@link #append} persists a single record atomically.</li>
 *   <li>{@link #appendBatch} persists every record in one transaction; on failure
 *       none of them must be visible (all-or-none).</li>
 *   <li>{@link #markReverted} is the rollback marker write; it is idempotent.</li>
 * </ul>
 *
 * <p>No vendor imports; implement in {@code infrastructure.persistence}.</p>
 */
public interface TransactionRepository {

    void append(Transaction transaction) throws PersistenceException;

    /**
     * Atomically persist multiple records (e.g. a transfer's TRANSFER_OUT + TRANSFER_IN).
     * Either every record is stored or none is.
     */
    void appendBatch(List<Transaction> transactions) throws PersistenceException;

    /** Rollback marker: mark the given transaction as reverted. Idempotent. */
    void markReverted(UUID transactionId) throws PersistenceException;

    boolean isReverted(UUID transactionId) throws PersistenceException;

    List<Transaction> loadByAccount(UUID accountId) throws PersistenceException;

    List<Transaction> loadAll() throws PersistenceException;
}
