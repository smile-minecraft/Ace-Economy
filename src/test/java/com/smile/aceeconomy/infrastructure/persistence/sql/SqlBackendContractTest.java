package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real, offline SQLite JDBC contract tests for {@link SqlBackend}. Uses a temporary on-disk database
 * so restart / reload behaviour is exercised against actual persisted state.
 */
final class SqlBackendContractTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    private SqlBackend backendFor(Path dbFile) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        return new SqlBackend(conn, new SqliteDialect());
    }

    private Transaction sampleTx(UUID id, UUID account, String currency, String amount,
                                TransactionType type, String before, String after) {
        return Fixtures.tx(id, account, null, currency,
                Fixtures.amt(amount), type, Fixtures.amt(before), Fixtures.amt(after));
    }

    @Test
    void freshCreateInitializesSchema() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("fresh.db"));
        backend.initialize();
        assertTrue(backend.isInitialized());
        assertEquals(1, backend.schemaVersion());
        assertTrue(backend.loadAll().isEmpty());
        backend.close();
    }

    @Test
    void initializeIsIdempotentOnRestart() throws Exception {
        Path db = dir.resolve("restart.db");
        SqlBackend first = backendFor(db);
        first.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        first.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));
        first.close();

        // Reopen the same file: initialize must not error and must see persisted data.
        SqlBackend second = backendFor(db);
        second.initialize();
        assertEquals(1, second.schemaVersion());
        List<Transaction> all = second.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        second.close();
    }

    @Test
    void accountRoundTrip() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("account.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        Account created = backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        assertTrue(backend.exists(owner));
        Account loaded = backend.load(owner).orElseThrow();
        assertEquals("alice", loaded.ownerName());
        assertEquals(0, Fixtures.amt("100.00").compareTo(loaded.balances().get("dollar")));
        assertEquals(created.owner(), loaded.owner());
        backend.close();
    }

    @Test
    void transactionRoundTripAndLoadByAccount() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("tx.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        assertEquals(TransactionType.DEPOSIT, all.get(0).type());

        List<Transaction> byAccount = backend.loadByAccount(account);
        assertEquals(1, byAccount.size());
        assertEquals(id, byAccount.get(0).id());
        backend.close();
    }

    @Test
    void atomicBatchPersistsAll() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("batch.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        backend.appendBatch(List.of(
                sampleTx(UUID.randomUUID(), account, "dollar", "5.00", TransactionType.DEPOSIT, "0.00", "5.00"),
                sampleTx(UUID.randomUUID(), account, "dollar", "3.00", TransactionType.WITHDRAW, "5.00", "2.00")
        ));
        assertEquals(2, backend.loadAll().size());
        assertEquals(2, backend.loadByAccount(account).size());
        backend.close();
    }

    @Test
    void batchFailureRollsBackEntireBatch() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("batchfail.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID existing = UUID.randomUUID();
        backend.append(sampleTx(existing, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        UUID good = UUID.randomUUID();
        // Second entry duplicates an already-committed id -> the whole batch must fail and the
        // "good" record must NOT be visible (all-or-none).
        assertThrows(PersistenceException.class, () -> backend.appendBatch(List.of(
                sampleTx(good, account, "dollar", "2.00", TransactionType.DEPOSIT, "1.00", "3.00"),
                sampleTx(existing, account, "dollar", "9.00", TransactionType.DEPOSIT, "1.00", "10.00")
        )));

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(existing, all.get(0).id());
        backend.close();
    }

    @Test
    void duplicateAppendRejected() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("dup.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));
        assertThrows(PersistenceException.class,
                () -> backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00")));
        backend.close();
    }

    @Test
    void rollbackMarkerIsSetAndIdempotent() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("revert.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        assertFalse(backend.isReverted(id));
        backend.markReverted(id);
        assertTrue(backend.isReverted(id));
        // Idempotent: re-marking an existing record must not throw.
        backend.markReverted(id);
        assertTrue(backend.isReverted(id));
        // Unknown id must throw.
        assertThrows(PersistenceException.class, () -> backend.markReverted(UUID.randomUUID()));
        backend.close();
    }

    @Test
    void backupAndRestoreRoundTrip() throws Exception {
        Path db = dir.resolve("backup.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backend.backup(out);
        byte[] snapshot = out.toByteArray();
        assertTrue(snapshot.length > 0);

        // Add an extra record, then restore from the snapshot: the extra must be gone.
        backend.append(sampleTx(UUID.randomUUID(), account, "dollar", "5.00", TransactionType.DEPOSIT, "10.00", "15.00"));
        assertEquals(2, backend.loadAll().size());

        backend.restore(new ByteArrayInputStream(snapshot));
        List<Transaction> restored = backend.loadAll();
        assertEquals(1, restored.size());
        assertEquals(id, restored.get(0).id());
        backend.close();
    }

    @Test
    void corruptBackupDoesNotDestroyLiveData() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("corrupt.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream("this is not json{".getBytes())));

        // Live data must be untouched.
        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        backend.close();
    }

    @Test
    void restoreRejectsSchemaVersionMismatch() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("mismatch.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        String bad = "{\"schemaVersion\":99,\"accounts\":{},\"transactions\":[]}";
        assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(bad.getBytes())));

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        backend.close();
    }

    @Test
    void needsRecreationDetectsPartialInitAndFullInit() throws Exception {
        Path db = dir.resolve("recreate.db");
        SqlBackend backend = backendFor(db);
        // Fresh: no v2 tables at all -> no recreation needed.
        assertFalse(backend.needsRecreation());

        // Simulate a partial init: an accounts table exists but no schema-version table.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + V2Schema.accountsTable() + " (owner TEXT PRIMARY KEY, owner_name TEXT)");
        }
        assertTrue(backend.needsRecreation());

        // Full init clears the partial state and makes recreation unnecessary.
        backend.initialize();
        assertFalse(backend.needsRecreation());
        assertEquals(1, backend.schemaVersion());
        backend.close();
    }

    @Test
    void truncateAndRecreateClearsData() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("truncate.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        backend.append(sampleTx(UUID.randomUUID(), account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));
        assertEquals(1, backend.loadAll().size());

        backend.truncateAndRecreate();
        assertTrue(backend.loadAll().isEmpty());
        assertEquals(1, backend.schemaVersion());
        backend.close();
    }
}
