package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for transaction cleanup under RuntimeException/Error.
 * Each test documents the invariant that a manual transaction must be
 * rolled back and auto-commit restored even when the failure is not a
 * SQLException, and that the borrow is fail-closed when cleanup itself fails.
 */
class SqlBackendRuntimeCleanupTest {

    @Test
    void createSchemaRuntimeExceptionTriggersRollbackAndRestore() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new RuntimeException("boom-runtime"));
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        assertThrows(RuntimeException.class, backend::initialize);

        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void createSchemaErrorTriggersRollbackAndRestore() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new AssertionError("boom-error"));
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        assertThrows(AssertionError.class, backend::initialize);

        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void saveRuntimeExceptionTriggersRollbackAndRestore() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        PreparedStatement deletePs = mock(PreparedStatement.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        ResultSet accountRs = mock(ResultSet.class);
        when(selectPs.executeQuery()).thenReturn(accountRs);
        when(accountRs.next()).thenReturn(false);
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectPs)
                .thenReturn(deletePs)
                .thenReturn(insertPs)
                .thenReturn(mock(PreparedStatement.class));
        when(deletePs.executeUpdate()).thenThrow(new RuntimeException("save-boom"));

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        assertThrows(RuntimeException.class, () -> backend.save(account));

        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void saveErrorTriggersRollbackAndRestore() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        PreparedStatement deletePs = mock(PreparedStatement.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        ResultSet accountRs = mock(ResultSet.class);
        when(selectPs.executeQuery()).thenReturn(accountRs);
        when(accountRs.next()).thenReturn(false);
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectPs)
                .thenReturn(deletePs)
                .thenReturn(insertPs)
                .thenReturn(mock(PreparedStatement.class));
        when(deletePs.executeUpdate()).thenThrow(new OutOfMemoryError("save-error"));

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        assertThrows(OutOfMemoryError.class, () -> backend.save(account));

        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void restoreRuntimeExceptionTriggersRollback() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        doThrow(new RuntimeException("restore-boom")).when(ps).executeUpdate();

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        UUID owner = UUID.randomUUID();
        String snapshot = "{\"schemaVersion\":1,\"accounts\":{\""
                + owner + "\":{\"owner\":\"" + owner + "\",\"ownerName\":\"a\",\"balances\":{}}"
                + "},\"transactions\":[],\"nonces\":{}}";
        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> backend.restore(new java.io.ByteArrayInputStream(bytes)));

        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void beginFailureMarksUnsafeSoConnectionNotReturnedSafe() throws Exception {
        Connection connection = mock(Connection.class);
        doThrow(new RuntimeException("begin-boom")).when(connection).setAutoCommit(false);

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        assertThrows(RuntimeException.class, () -> backend.save(account));

        verify(connection).close();
    }

    @Test
    void rollbackFailureWithRuntimePrimaryStillMarksUnsafeAndPreservesSuppressed() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new RuntimeException("primary-runtime"));
        doThrow(new java.sql.SQLException("rollback-boom")).when(connection).rollback();

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        RuntimeException thrown = assertThrows(RuntimeException.class, backend::initialize);
        boolean hasSuppressed = false;
        for (Throwable t : thrown.getSuppressed()) {
            if ("rollback-boom".equals(t.getMessage())) {
                hasSuppressed = true;
            }
        }
        assertTrue(hasSuppressed, "rollback failure must be suppressed on primary RuntimeException");
        verify(connection).close();
        verify(connection, never()).setAutoCommit(true);
    }

    @Test
    void restoreAutoCommitFailureAfterCommitWithRuntimeMustWrapAsPersistenceExceptionCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        PreparedStatement versionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(versionPs);
        when(versionPs.executeUpdate()).thenReturn(1);

        RuntimeException injected = new RuntimeException("restore-boom-runtime");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        PersistenceException thrown = assertThrows(PersistenceException.class, backend::initialize,
                "post-commit restore failure must be wrapped as PersistenceException with committed guidance");
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"),
                "message must contain committed guidance so operator does not retry as rollback");
        assertEquals(injected, thrown.getCause(), "original RuntimeException must be preserved as cause");
        verify(connection).close();
    }

    @Test
    void restoreAutoCommitFailureAfterCommitWithErrorMustWrapAsPersistenceExceptionCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        PreparedStatement versionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(versionPs);
        when(versionPs.executeUpdate()).thenReturn(1);

        AssertionError injected = new AssertionError("restore-boom-error");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        PersistenceException thrown = assertThrows(PersistenceException.class, backend::initialize,
                "post-commit restore failure for Error must be wrapped as PersistenceException");
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"),
                "message must contain committed guidance even for Error");
        assertEquals(injected, thrown.getCause(), "original Error must be preserved as cause");
        verify(connection).close();
    }

    @Test
    void restoreAutoCommitFailureAfterCommitWithSqlExceptionMustWrapAsPersistenceExceptionCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        PreparedStatement versionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(versionPs);
        when(versionPs.executeUpdate()).thenReturn(1);

        SQLException injected = new SQLException("restore-boom-sql");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        PersistenceException thrown = assertThrows(PersistenceException.class, backend::initialize);
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
    }

    @Test
    void savePostCommitRestoreFailureWithRuntimeMustWrapAndAbandon() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        PreparedStatement balPs = mock(PreparedStatement.class);
        ResultSet accountRs = mock(ResultSet.class);
        when(selectPs.executeQuery()).thenReturn(accountRs);
        when(accountRs.next()).thenReturn(false);
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectPs)
                .thenReturn(insertPs)
                .thenReturn(balPs);
        when(insertPs.executeUpdate()).thenReturn(1);
        when(balPs.executeBatch()).thenReturn(new int[]{1});

        RuntimeException injected = new RuntimeException("save-restore-boom");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        PersistenceException thrown = assertThrows(PersistenceException.class, () -> backend.save(account));
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
        // The abandon close failure, if any, should be suppressed on the cause
        // Here close succeeds, so just verify unsafe abandoned
    }

    @Test
    void savePostCommitRestoreFailureWithErrorMustWrapAndAbandon() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        PreparedStatement balPs = mock(PreparedStatement.class);
        ResultSet accountRs = mock(ResultSet.class);
        when(selectPs.executeQuery()).thenReturn(accountRs);
        when(accountRs.next()).thenReturn(false);
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectPs)
                .thenReturn(insertPs)
                .thenReturn(balPs);
        when(insertPs.executeUpdate()).thenReturn(1);
        when(balPs.executeBatch()).thenReturn(new int[]{1});

        AssertionError injected = new AssertionError("save-restore-error");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        PersistenceException thrown = assertThrows(PersistenceException.class, () -> backend.save(account));
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
    }

    @Test
    void truncateAndRecreatePostCommitRestoreFailureWithRuntimeMustWrapCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.executeUpdate(anyString())).thenReturn(0);
        PreparedStatement versionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(versionPs);
        when(versionPs.executeUpdate()).thenReturn(1);

        RuntimeException injected = new RuntimeException("truncate-restore-boom");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        PersistenceException thrown = assertThrows(PersistenceException.class, backend::truncateAndRecreate);
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
    }

    @Test
    void truncateAndRecreatePostCommitRestoreFailureWithErrorMustWrapCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.executeUpdate(anyString())).thenReturn(0);
        PreparedStatement versionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(versionPs);
        when(versionPs.executeUpdate()).thenReturn(1);

        AssertionError injected = new AssertionError("truncate-restore-error");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        PersistenceException thrown = assertThrows(PersistenceException.class, backend::truncateAndRecreate);
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
    }

    @Test
    void restorePostCommitFailureWithRuntimeMustWrapCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        when(ps.executeBatch()).thenReturn(new int[]{1});

        RuntimeException injected = new RuntimeException("restore-op-boom");
        // Need to make commit succeed but setAutoCommit(true) fail.
        // We stub setAutoCommit to throw only when true.
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        String snapshot = "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],\"nonces\":{}}";
        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);

        PersistenceException thrown = assertThrows(PersistenceException.class,
                () -> backend.restore(new java.io.ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
    }

    @Test
    void restorePostCommitFailureWithErrorMustWrapCommitted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        when(ps.executeBatch()).thenReturn(new int[]{1});

        AssertionError injected = new AssertionError("restore-op-error");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        String snapshot = "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],\"nonces\":{}}";
        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);

        PersistenceException thrown = assertThrows(PersistenceException.class,
                () -> backend.restore(new java.io.ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().toLowerCase().contains("committed"));
        assertEquals(injected, thrown.getCause());
        verify(connection).close();
    }

    @Test
    void postCommitRestoreFailureCloseSuppressedOnCause() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        PreparedStatement versionPs = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(versionPs);
        when(versionPs.executeUpdate()).thenReturn(1);

        RuntimeException injected = new RuntimeException("restore-boom");
        doAnswer(invocation -> {
            Boolean val = invocation.getArgument(0);
            if (Boolean.TRUE.equals(val)) {
                throw injected;
            }
            return null;
        }).when(connection).setAutoCommit(anyBoolean());
        // Make abandon close also fail, should be suppressed on cause
        doThrow(new SQLException("close-fail")).when(connection).close();

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        PersistenceException thrown = assertThrows(PersistenceException.class, backend::initialize);
        assertEquals(injected, thrown.getCause());
        boolean suppressedFound = false;
        for (Throwable sup : injected.getSuppressed()) {
            if ("close-fail".equals(sup.getMessage())) {
                suppressedFound = true;
            }
        }
        assertTrue(suppressedFound, "abandon close failure must be suppressed on the JDBC cause");
        verify(connection).close();
    }

    @Test
    void restoreErrorTriggersRollbackAndRestore() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        doThrow(new AssertionError("restore-error")).when(ps).executeUpdate();

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        UUID owner = UUID.randomUUID();
        String snapshot = "{\"schemaVersion\":1,\"accounts\":{\""
                + owner + "\":{\"owner\":\"" + owner
                + "\",\"ownerName\":\"a\",\"balances\":{}}"
                + "},\"transactions\":[],\"nonces\":{}}";
        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);

        assertThrows(AssertionError.class, () -> backend.restore(new java.io.ByteArrayInputStream(bytes)));

        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void beginFailureWithErrorMarksUnsafe() throws Exception {
        Connection connection = mock(Connection.class);
        doThrow(new AssertionError("begin-error")).when(connection).setAutoCommit(false);

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.setInitializedForTest(true);
        Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));

        assertThrows(AssertionError.class, () -> backend.save(account));

        verify(connection).close();
    }

    @Test
    void rollbackThrowingRuntimeExceptionStillMarksUnsafe() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new RuntimeException("primary"));
        doThrow(new RuntimeException("rollback-runtime")).when(connection).rollback();

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());

        RuntimeException thrown = assertThrows(RuntimeException.class, backend::initialize);
        boolean hasSuppressed = false;
        for (Throwable t : thrown.getSuppressed()) {
            if ("rollback-runtime".equals(t.getMessage())) {
                hasSuppressed = true;
            }
        }
        assertTrue(hasSuppressed, "rollback RuntimeException must be suppressed on primary");
        verify(connection).close();
        verify(connection, never()).setAutoCommit(true);
    }

    @Test
    void dropSchemaRuntimeExceptionTriggersRollback() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(anyString())).thenThrow(new RuntimeException("drop-boom"));

        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        assertThrows(RuntimeException.class, backend::truncateAndRecreate);
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void saveAfterRuntimeFailureRemainsUsableWithRealConnection() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("runtime-cleanup-real");
        java.sql.Connection real = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("real.db"));
        try {
            java.sql.Connection throwing = new ThrowingConnectionWrapper(real, false);
            SqlBackend backend = new SqlBackend(new SqlConnectionProvider(throwing), new SqliteDialect());
            backend.initialize();
            ((ThrowingConnectionWrapper) throwing).arm();

            Account account = Account.create(UUID.randomUUID(), "alice", Map.of("dollar", Fixtures.amt("10.00")));
            assertThrows(RuntimeException.class, () -> backend.save(account));
            assertTrue(throwing.getAutoCommit(), "after RuntimeException rollback, autoCommit must be restored");

            Account account2 = Account.create(UUID.randomUUID(), "bob", Map.of("dollar", Fixtures.amt("5.00")));
            backend.save(account2);
            assertTrue(backend.exists(account2.owner()));

            backend.close();
        } finally {
            real.close();
        }
    }

    private static class ThrowingConnectionWrapper implements Connection {
        private final Connection delegate;
        private boolean shouldThrow;
        private boolean inTransaction = false;

        ThrowingConnectionWrapper(Connection delegate, boolean armed) {
            this.delegate = delegate;
            this.shouldThrow = armed;
        }

        void arm() {
            this.shouldThrow = true;
        }

        @Override public void setAutoCommit(boolean autoCommit) throws java.sql.SQLException {
            delegate.setAutoCommit(autoCommit);
            inTransaction = !autoCommit;
        }

        @Override public boolean getAutoCommit() throws java.sql.SQLException { return delegate.getAutoCommit(); }

        @Override public PreparedStatement prepareStatement(String sql) throws java.sql.SQLException {
            if (inTransaction && shouldThrow) {
                shouldThrow = false;
                throw new RuntimeException("injected-runtime");
            }
            return delegate.prepareStatement(sql);
        }

        @Override public Statement createStatement() throws java.sql.SQLException { return delegate.createStatement(); }
        @Override public void commit() throws java.sql.SQLException { delegate.commit(); }
        @Override public void rollback() throws java.sql.SQLException { delegate.rollback(); }
        @Override public void close() throws java.sql.SQLException { delegate.close(); }
        @Override public boolean isClosed() throws java.sql.SQLException { return delegate.isClosed(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException { return delegate.getMetaData(); }
        @Override public boolean isValid(int timeout) throws java.sql.SQLException { return delegate.isValid(timeout); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws java.sql.SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws java.sql.SQLException { return delegate.nativeSQL(sql); }
        @Override public void setReadOnly(boolean readOnly) throws java.sql.SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws java.sql.SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws java.sql.SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws java.sql.SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws java.sql.SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws java.sql.SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws java.sql.SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws java.sql.SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws java.sql.SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws java.sql.SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws java.sql.SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws java.sql.SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) throws java.sql.SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws java.sql.SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws java.sql.SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws java.sql.SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String name) throws java.sql.SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(java.sql.Savepoint savepoint) throws java.sql.SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws java.sql.SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws java.sql.SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws java.sql.SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws java.sql.SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override public java.sql.Clob createClob() throws java.sql.SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws java.sql.SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws java.sql.SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws java.sql.SQLException { return delegate.createSQLXML(); }
        @Override public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws java.sql.SQLException { return delegate.getClientInfo(name); }
        @Override public java.util.Properties getClientInfo() throws java.sql.SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws java.sql.SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws java.sql.SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws java.sql.SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws java.sql.SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws java.sql.SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws java.sql.SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws java.sql.SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException { return delegate.isWrapperFor(iface); }
    }
}
