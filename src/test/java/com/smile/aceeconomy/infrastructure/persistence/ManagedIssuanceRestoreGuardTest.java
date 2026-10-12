package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restore guard over managed issuance records: a settled managed nonce must
 * never rewind to an unfinished state, while an identical snapshot restores
 * cleanly. Plain consumed nonces are deliberately not covered here.
 */
class ManagedIssuanceRestoreGuardTest {

    private static final DebtPolicy DEBT = DebtPolicy.disabled();

    private static Transaction preparedDebit(UUID txId, UUID account, Amount amount,
            Amount before, Amount after, UUID nonce) {
        return new Transaction(txId, account, null, "dollar", amount,
                TransactionType.WITHDRAW, before, after, Instant.now(),
                BanknoteIssuanceReasons.issueReason(nonce));
    }

    private static UUID settleJson(JsonPersistenceBackend backend, UUID account, UUID nonce,
            Amount amount) throws Exception {
        AtomicBanknoteIssuanceStore store = backend;
        store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
        Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                before, before.subtract(amount), nonce);
        IssuanceDebitResult committed = store.commitDebit(nonce, prepared, DEBT);
        assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
        store.markDeliveryStarted(nonce);
        store.markDelivered(nonce);
        assertEquals(BanknoteIssuanceStatus.DELIVERED,
                backend.findByNonce(nonce).orElseThrow().status());
        return nonce;
    }

    private static UUID settleSql(SqlBackend backend, UUID account, UUID nonce,
            Amount amount) throws Exception {
        AtomicBanknoteIssuanceStore store = backend;
        store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
        Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                before, before.subtract(amount), nonce);
        IssuanceDebitResult committed = store.commitDebit(nonce, prepared, DEBT);
        assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
        store.markDeliveryStarted(nonce);
        store.markDelivered(nonce);
        assertEquals(BanknoteIssuanceStatus.DELIVERED,
                backend.findByNonce(nonce).orElseThrow().status());
        return nonce;
    }

    @Test
    void jsonIdenticalSettledRestorePasses(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("guard-pass.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            UUID nonce = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            settleJson(backend, account, nonce, Amount.of(100L, 2));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            backend.backup(out);
            backend.restore(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "an identical restore must keep the settled managed record");
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonRewoundSettledRestoreIsRefused(@TempDir Path dir) throws Exception {
        Path liveFile = dir.resolve("guard-live.json");
        JsonPersistenceBackend live = new JsonPersistenceBackend(liveFile);
        live.initialize();
        try {
            UUID account = UUID.randomUUID();
            UUID nonce = UUID.randomUUID();
            live.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            settleJson(live, account, nonce, Amount.of(100L, 2));

            Path candidateFile = dir.resolve("guard-candidate.json");
            JsonPersistenceBackend candidate = new JsonPersistenceBackend(candidateFile);
            candidate.initialize();
            try {
                candidate.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
                candidate.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                        Amount.of(100L, 2), Instant.now()));
                ByteArrayOutputStream rewound = new ByteArrayOutputStream();
                candidate.backup(rewound);

                PersistenceException refused = assertThrows(PersistenceException.class,
                        () -> live.restore(new ByteArrayInputStream(rewound.toByteArray())),
                        "restoring a PREPARED candidate over a DELIVERED live record must be refused");
                assertTrue(refused.getMessage() != null
                        && refused.getMessage().contains("Restore refused"),
                        "diagnosis must name the restore refusal, got: " + refused.getMessage());
                assertEquals(BanknoteIssuanceStatus.DELIVERED,
                        live.findByNonce(nonce).orElseThrow().status(),
                        "a refused restore must leave the settled live record untouched");
            } finally {
                candidate.close();
            }
        } finally {
            live.close();
        }
    }

    @Test
    void sqliteIdenticalSettledRestorePasses(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("guard-pass.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            UUID nonce = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            settleSql(backend, account, nonce, Amount.of(100L, 2));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            backend.backup(out);
            backend.restore(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "an identical restore must keep the settled managed record");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteRewoundSettledRestoreIsRefused(@TempDir Path dir) throws Exception {
        Path liveDb = dir.resolve("guard-live.db");
        SqlBackend live = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + liveDb)),
                new SqliteDialect());
        live.initialize();
        try {
            UUID account = UUID.randomUUID();
            UUID nonce = UUID.randomUUID();
            live.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            settleSql(live, account, nonce, Amount.of(100L, 2));

            Path candidateDb = dir.resolve("guard-candidate.db");
            SqlBackend candidate = new SqlBackend(
                    new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + candidateDb)),
                    new SqliteDialect());
            candidate.initialize();
            try {
                candidate.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
                candidate.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                        Amount.of(100L, 2), Instant.now()));
                ByteArrayOutputStream rewound = new ByteArrayOutputStream();
                candidate.backup(rewound);

                PersistenceException refused = assertThrows(PersistenceException.class,
                        () -> live.restore(new ByteArrayInputStream(rewound.toByteArray())),
                        "restoring a PREPARED candidate over a DELIVERED live record must be refused");
                assertTrue(refused.getMessage() != null
                        && refused.getMessage().contains("Restore refused"),
                        "diagnosis must name the restore refusal, got: " + refused.getMessage());
                assertEquals(BanknoteIssuanceStatus.DELIVERED,
                        live.findByNonce(nonce).orElseThrow().status(),
                        "a refused restore must leave the settled live record untouched");
            } finally {
                candidate.close();
            }
        } finally {
            live.close();
        }
    }
}
