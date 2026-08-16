package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

/**
 * Bridges the application's {@link AuditSink} port to the persistence-backed
 * {@link TransactionRepository}. Audit failures are surfaced as {@link AuditException} so the
 * application's contract (audit failure is reported, never swallowed) is preserved.
 */
public final class PersistentAuditSink implements AuditSink {

    private final TransactionRepository repository;

    public PersistentAuditSink(TransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(Transaction transaction) throws AuditException {
        try {
            repository.append(transaction);
        } catch (PersistenceException e) {
            throw new AuditException("Failed to persist audit record: " + e.getMessage(), e);
        }
    }
}
