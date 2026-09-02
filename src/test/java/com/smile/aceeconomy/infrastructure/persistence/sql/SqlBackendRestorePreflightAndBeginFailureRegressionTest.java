package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression for the two blockers in SqlBackend lifecycle review:
 * - direct restore must validate every record via SnapshotPreflight before any live DELETE/INSERT.
 * - begin failure + rollback failure must not attempt to restore autoCommit.
 */
final class SqlBackendRestorePreflightAndBeginFailureRegressionTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    // ---------------- preflight: invalid transaction type ----------------

    @Test
    void directRestoreWithInvalidTransactionTypeMustFailAndKeepLiveData() throws Exception {
        Path db = dir.resolve("preflight-bogus.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(real);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();

            UUID owner = UUID.randomUUID();
            Account account = Account.create(owner, "alice", Map.of("dollar", Fixtures.amt("10.00")));
            backend.save(account);
            assertTrue(backend.exists(owner), "sanity: live data present before invalid restore");
            int beforeCount = backend.listAll().size();

            String bogusSnapshot = buildSnapshotWithTransaction(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "dollar",
                    "10.00",
                    "BOGUS",
                    "2026-01-01T00:00:00Z"
            );

            PersistenceException thrown = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(bogusSnapshot.getBytes(StandardCharsets.UTF_8))),
                    "invalid transaction type must be rejected before any live mutation");

            // Fingerprint: original data unchanged
            assertEquals(beforeCount, backend.listAll().size(), "live rows must not be deleted after invalid restore");
            assertTrue(backend.exists(owner), "original account must survive failed restore");
            assertTrue(real.getAutoCommit(), "connection must be back in autoCommit after failed preflight");

            backend.close();
        } finally {
            real.close();
        }
    }

    @Test
    void directRestoreWithInvalidTransactionUuidMustFailAndKeepLiveData() throws Exception {
        Path db = dir.resolve("preflight-uuid.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(real);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();

            UUID owner = UUID.randomUUID();
            Account account = Account.create(owner, "bob", Map.of("dollar", Fixtures.amt("5.00")));
            backend.save(account);
            int beforeCount = backend.listAll().size();

            // Use a not-a-uuid string for the transaction id – toDomain() will throw IllegalArgumentException
            String badUuidSnapshot = buildSnapshotWithTransaction(
                    "not-a-uuid",
                    UUID.randomUUID().toString(),
                    "dollar",
                    "10.00",
                    "DEPOSIT",
                    "2026-01-01T00:00:00Z"
            );

            PersistenceException thrown = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(badUuidSnapshot.getBytes(StandardCharsets.UTF_8))),
                    "invalid UUID must be rejected before live mutation");

            assertEquals(beforeCount, backend.listAll().size(), "live data must stay intact after UUID validation failure");
            assertTrue(backend.exists(owner));
            assertTrue(real.getAutoCommit());

            backend.close();
        } finally {
            real.close();
        }
    }

    @Test
    void directRestoreInvalidRecordMustNotExecuteAnyDeleteStatement() throws Exception {
        // Mock path proves that no JDBC mutation was even attempted when preflight fails.
        // Before the fix this test fails with "nothing was thrown" because the invalid record
        // is inserted directly without validation; after the fix it throws PersistenceException
        // before any SQL is touched.
        Connection connection = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(connection.createStatement()).thenReturn(st);
        when(st.executeUpdate(anyString())).thenReturn(0);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        when(ps.executeBatch()).thenReturn(new int[]{1});

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        String bogusSnapshot = buildSnapshotWithTransaction(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "dollar",
                "1.00",
                "BOGUS",
                "2026-01-01T00:00:00Z"
        );

        assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(bogusSnapshot.getBytes(StandardCharsets.UTF_8))));

        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).rollback();
        verify(connection, never()).commit();
        // Provider's borrow would have been attempted only if we entered withBorrowed; with preflight outside,
        // the connection is never borrowed, so close/abandon is also never triggered. We verify no unsafe path.
        verify(connection, never()).close();
    }

    // ---------------- begin failure + rollback failure ----------------

    @Test
    void beginFailurePlusRollbackFailureMustNotRestoreAutoCommitAndMustPreserveSuppressed() throws Exception {
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("begin-boom")).when(connection).setAutoCommit(false);
        doThrow(new SQLException("rollback-boom")).when(connection).rollback();

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        PersistenceException thrown = assertThrows(PersistenceException.class, () -> backend.save(account),
                "begin failure must surface as PersistenceException");

        Throwable cause = thrown.getCause();
        assertNotNull(cause);
        assertTrue(cause instanceof SQLException, "cause must be SQLException from begin");
        assertEquals("begin-boom", cause.getMessage(), "primary failure must be begin failure");

        boolean suppressedFound = false;
        for (Throwable sup : cause.getSuppressed()) {
            if ("rollback-boom".equals(sup.getMessage())) {
                suppressedFound = true;
                break;
            }
        }
        assertTrue(suppressedFound, "rollback failure must be preserved as suppressed on primary");

        // Must not attempt to restore autoCommit when rollback itself failed; that would risk implicit commit.
        verify(connection, never()).setAutoCommit(true);
        verify(connection).rollback();
        // Connection must be fail-closed (abandoned) so next caller gets fresh connection.
        verify(connection).close();
    }

    @Test
    void beginFailureWithRollbackSuccessStillFailsClosedAndTriesRestoreAutoCommit() throws Exception {
        // When rollback succeeds after begin failure, current design still attempts setAutoCommit(true)
        // and keeps the connection abandoned (unknown state). This test locks that behavior so a
        // future regression does not silently swallow the unsafe flag.
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("begin-boom")).when(connection).setAutoCommit(false);
        // rollback succeeds (no throw)

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        PersistenceException thrown = assertThrows(PersistenceException.class, () -> backend.save(account));
        assertEquals("begin-boom", thrown.getCause().getMessage());

        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    // ---------------- helpers ----------------

    private static String buildSnapshotWithTransaction(
            String txId, String accountId, String currencyId, String amount,
            String type, String timestamp) {
        // Minimal valid snapshot with one transaction; accounts empty is allowed, but we include no accounts
        // The invalid field (type or id) will be caught by SnapshotPreflight.validateRecords -> toDomain().
        return "{"
                + "\"schemaVersion\":1,"
                + "\"accounts\":{},"
                + "\"transactions\":[{"
                + "\"id\":\"" + txId + "\","
                + "\"accountId\":\"" + accountId + "\","
                + "\"counterparty\":null,"
                + "\"currencyId\":\"" + currencyId + "\","
                + "\"amount\":\"" + amount + "\","
                + "\"type\":\"" + type + "\","
                + "\"balanceBefore\":null,"
                + "\"balanceAfter\":null,"
                + "\"timestamp\":\"" + timestamp + "\","
                + "\"reason\":\"test\","
                + "\"reverted\":false"
                + "}],"
                + "\"nonces\":{}"
                + "}";
    }
}
