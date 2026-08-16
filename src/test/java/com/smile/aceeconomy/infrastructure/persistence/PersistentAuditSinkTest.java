package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for the audit->persistence bridge. Uses a fake {@link TransactionRepository}. */
final class PersistentAuditSinkTest {

    @Test
    void recordDelegatesToRepository() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        PersistentAuditSink sink = new PersistentAuditSink(repo);
        UUID owner = UUID.randomUUID();
        Transaction tx = Fixtures.tx(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("10.00"), TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("10.00"));
        sink.record(tx);
        assertEquals(1, repo.appended.size());
        assertEquals(tx.id(), repo.appended.get(0).id());
    }

    @Test
    void repositoryFailureBecomesAuditException() {
        TransactionRepository failing = new TransactionRepository() {
            @Override
            public void append(Transaction transaction) throws PersistenceException {
                throw new PersistenceException("boom");
            }

            @Override
            public void appendBatch(List<Transaction> transactions) throws PersistenceException {
                throw new PersistenceException("boom");
            }

            @Override
            public void markReverted(UUID transactionId) throws PersistenceException {
                throw new PersistenceException("boom");
            }

            @Override
            public boolean isReverted(UUID transactionId) throws PersistenceException {
                return false;
            }

            @Override
            public List<Transaction> loadByAccount(UUID accountId) throws PersistenceException {
                return List.of();
            }

            @Override
            public List<Transaction> loadAll() throws PersistenceException {
                return List.of();
            }
        };
        PersistentAuditSink sink = new PersistentAuditSink(failing);
        Transaction tx = Fixtures.tx(UUID.randomUUID(), UUID.randomUUID(), null, "dollar",
                Fixtures.amt("1.00"), TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("1.00"));
        assertThrows(AuditException.class, () -> sink.record(tx));
    }

    private static final class RecordingRepo implements TransactionRepository {
        final List<Transaction> appended = new java.util.ArrayList<>();

        @Override
        public void append(Transaction transaction) throws PersistenceException {
            appended.add(transaction);
        }

        @Override
        public void appendBatch(List<Transaction> transactions) throws PersistenceException {
            appended.addAll(transactions);
        }

        @Override
        public void markReverted(UUID transactionId) throws PersistenceException {
        }

        @Override
        public boolean isReverted(UUID transactionId) throws PersistenceException {
            return false;
        }

        @Override
        public List<Transaction> loadByAccount(UUID accountId) throws PersistenceException {
            return List.of();
        }

        @Override
        public List<Transaction> loadAll() throws PersistenceException {
            return List.of();
        }
    }
}
