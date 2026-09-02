package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL connection provider contract: borrow/return, same-connection atomic boundary,
 * failure recovery, rollback, and pool close without leak.
 */
final class SqlConnectionProviderTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    // ---------- SQLite provider: shared connection, safe close ----------

    @Test
    void sqliteProviderReturnsSameConnectionAndDoesNotCloseOnReturn() throws Exception {
        Path db = dir.resolve("provider.sqlite");
        Connection shared = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlConnectionProvider provider = new SqlConnectionProvider(shared);

        Connection borrowed1 = provider.borrow();
        Connection borrowed2 = provider.borrow();
        assertSame(shared, borrowed1, "SQLite provider must return the same shared connection");
        assertSame(shared, borrowed2, "SQLite provider must return the same shared connection");

        provider.returnConnection(borrowed1);
        provider.returnConnection(borrowed2);
        assertFalse(shared.isClosed(), "SQLite shared connection must NOT be closed by returnConnection");

        provider.close();
        assertTrue(shared.isClosed(), "SQLite shared connection must be closed by provider.close()");
    }

    // ---------- MySQL provider: healthy connections return to the provider-owned pool ----------

    @Test
    void mysqlProviderReusesHealthyConnectionAndClosesAtShutdown() throws Exception {
        DataSource fakePool = new FakeDataSource();
        SqlConnectionProvider provider = new SqlConnectionProvider(fakePool, 10, 30_000L, 1_800_000L);

        Connection c1 = provider.borrow();
        Connection c2 = provider.borrow();
        assertNotSame(c1, c2, "MySQL provider must borrow distinct connections from the pool");

        provider.returnConnection(c1);
        provider.returnConnection(c2);
        Connection reused = provider.borrow();
        assertNotSame(c1, reused, "each borrow gets an isolated lifecycle proxy");
        assertEquals(2, ((FakeDataSource) fakePool).getBorrowCount(),
                "healthy return must reuse a physical slot without opening another connection");
        provider.returnConnection(reused);

        provider.close();
        assertTrue(((FakeDataSource) fakePool).poolClosed, "DataSource must be closed by provider.close()");
        assertTrue(((FakeDataSource) fakePool).isClosed(c1), "idle connection must close at shutdown");
        assertTrue(((FakeDataSource) fakePool).isClosed(c2), "idle connection must close at shutdown");
    }

    // ---------- Atomic operation uses same borrowed connection ----------

    @Test
    void atomicBatchUsesSameConnectionWithinOneOperation() throws Exception {
        Path db = dir.resolve("atomic.sqlite");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlConnectionProvider provider = new SqlConnectionProvider(conn);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.initialize();

        UUID account = UUID.randomUUID();
        backend.create(account, "alice", Map.of("dollar", Fixtures.amt("100.00")));

        UUID tx1 = UUID.randomUUID();
        UUID tx2 = UUID.randomUUID();
        backend.appendBatch(java.util.List.of(
                Fixtures.tx(tx1, account, null, "dollar", Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                        Fixtures.amt("100.00"), Fixtures.amt("110.00")),
                Fixtures.tx(tx2, account, null, "dollar", Fixtures.amt("5.00"), TransactionType.WITHDRAW,
                        Fixtures.amt("110.00"), Fixtures.amt("105.00"))
        ));

        assertEquals(2, backend.loadAll().size(), "Batch must persist both records atomically");
        backend.close();
    }

    // ---------- Failure recovery: rollback and next operation succeeds ----------

    @Test
    void rollbackAfterBatchFailureLeavesBackendUsable() throws Exception {
        Path db = dir.resolve("rollback.sqlite");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlConnectionProvider provider = new SqlConnectionProvider(conn);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.initialize();

        UUID account = UUID.randomUUID();
        UUID existing = UUID.randomUUID();
        backend.append(Fixtures.tx(existing, account, null, "dollar", Fixtures.amt("1.00"),
                TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("1.00")));

        UUID good = UUID.randomUUID();
        assertThrows(PersistenceException.class, () -> backend.appendBatch(java.util.List.of(
                Fixtures.tx(good, account, null, "dollar", Fixtures.amt("2.00"), TransactionType.DEPOSIT,
                        Fixtures.amt("1.00"), Fixtures.amt("3.00")),
                Fixtures.tx(existing, account, null, "dollar", Fixtures.amt("9.00"), TransactionType.DEPOSIT,
                        Fixtures.amt("1.00"), Fixtures.amt("10.00"))
        )));

        assertEquals(1, backend.loadAll().size(), "Failed batch must roll back entirely");
        assertEquals(existing, backend.loadAll().get(0).id());

        UUID next = UUID.randomUUID();
        backend.append(Fixtures.tx(next, account, null, "dollar", Fixtures.amt("5.00"),
                TransactionType.DEPOSIT, Fixtures.amt("1.00"), Fixtures.amt("6.00")));
        assertEquals(2, backend.loadAll().size());
        backend.close();
    }

    // ---------- Pool close idempotent ----------

    @Test
    void providerCloseIsIdempotent() throws Exception {
        Path db = dir.resolve("idempotent.sqlite");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlConnectionProvider provider = new SqlConnectionProvider(conn);
        provider.close();
        provider.close(); // must not throw
        assertTrue(conn.isClosed());
    }

    // ---------- Pool-size=1: nested borrow detection ----------

    @Test
    void redeemPreparedDoesNotNestBorrowWithPoolSizeOne() throws Exception {
        // SingleSlotDataSource returns the SAME connection for every borrow call.
        // If redeemPrepared or redeem nests a borrow, the second getConnection() call
        // would block forever (or, with our blocking policy, throw immediately).
        Path db = dir.resolve("pool1.db");
        Connection shared = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SingleSlotDataSource pool = new SingleSlotDataSource(shared);
            SqlConnectionProvider provider = new SqlConnectionProvider(pool, 1, 30_000L, 1_800_000L);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();

            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Fixtures.amt("50.00")));

            // redeemPrepared must not nest-borrow: existsWithConnection reuses the held conn.
            // If it did nest-borrow, getConnection() would throw immediately because the pool
            // is single-slot and the slot is already held by the outer borrow.
            UUID nonce = UUID.randomUUID();
            int callsBeforeRedeem = pool.getConnectionCallCount();
            assertTrue(callsBeforeRedeem >= 1, "setup should have borrowed at least once");

            // The critical path: redeemPrepared borrows once and must complete without
            // requesting a second connection from the pool. We pass the account directly
            // (not via backend.load()) so the only borrow counted is from redeemPrepared itself.
            com.smile.aceeconomy.domain.Account accountObj = com.smile.aceeconomy.domain.Account.create(
                    account, "alice", Map.of("dollar", Fixtures.amt("50.00")));
            com.smile.aceeconomy.domain.Amount amount = Fixtures.amt("5.00");
            com.smile.aceeconomy.domain.Transaction tx = Fixtures.tx(
                    UUID.randomUUID(), account, null, "dollar", amount, TransactionType.DEPOSIT,
                    Fixtures.amt("50.00"), Fixtures.amt("55.00"));
            com.smile.aceeconomy.ports.persistence.RedemptionResult result =
                    backend.redeemPrepared(nonce, accountObj, tx);

            assertTrue(result.isCommitted(), "redeemPrepared must commit with pool-size=1");
            assertEquals(callsBeforeRedeem, pool.getConnectionCallCount(),
                    "redeemPrepared must reuse the provider-owned slot without a nested borrow");
            assertTrue(backend.isConsumed(nonce), "nonce must be consumed");
            backend.close();
        } finally {
            shared.close();
        }
    }

    @Test
    void redeemDoesNotNestBorrowWithPoolSizeOne() throws Exception {
        Path db = dir.resolve("pool1-redeem.db");
        Connection shared = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SingleSlotDataSource pool = new SingleSlotDataSource(shared);
            SqlConnectionProvider provider = new SqlConnectionProvider(pool, 1, 30_000L, 1_800_000L);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();

            UUID account = UUID.randomUUID();
            backend.create(account, "bob", Map.of("dollar", Fixtures.amt("200.00")));

            UUID nonce = UUID.randomUUID();
            int callsBeforeRedeem = pool.getConnectionCallCount();
            com.smile.aceeconomy.ports.persistence.RedemptionResult result =
                    backend.redeem(nonce, account, "dollar", Fixtures.amt("10.00"));

            assertTrue(result.isCommitted(), "redeem must commit with pool-size=1");
            assertEquals(callsBeforeRedeem, pool.getConnectionCallCount(),
                    "redeem must reuse the provider-owned slot without a nested borrow");
            assertTrue(backend.isConsumed(nonce), "nonce must be consumed");
            backend.close();
        } finally {
            shared.close();
        }
    }

    // ---------- Fail-closed on rollback failure ----------

    @Test
    void rollbackFailureClosesConnectionAndPreservesOriginalException() throws Exception {
        // Use a DataSource that returns SavingConnection wrappers with failOnRollback=true.
        // When appendBatch encounters a duplicate key, it tries to rollback. The rollback
        // fails (simulated), and the fail-closed path must:
        //   1. Attach the rollback failure as suppressed on the original exception
        //   2. Close the connection (marked unsafe)
        Path db = dir.resolve("rollback-fail.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            // First, set up the schema and a known transaction using a normal backend.
            SqlConnectionProvider normalProvider = new SqlConnectionProvider(
                    new SingleConnectionDataSource(real));
            SqlBackend normalBackend = new SqlBackend(normalProvider, new SqliteDialect());
            normalBackend.initialize();
            UUID account = UUID.randomUUID();
            normalBackend.create(account, "test", Map.of("dollar", Fixtures.amt("0.00")));
            UUID existingTx = UUID.randomUUID();
            normalBackend.append(Fixtures.tx(existingTx, account, null, "dollar",
                    Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("1.00")));
            normalBackend.close();

            // Now use a backend with rollback-failing connections.
            FailingRollbackDataSource failingDs = new FailingRollbackDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    failingDs, 10, 30_000L, 1_800_000L, false);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.setInitializedForTest(true);

            UUID goodTx = UUID.randomUUID();
            try {
                backend.appendBatch(java.util.List.of(
                        Fixtures.tx(goodTx, account, null, "dollar",
                                Fixtures.amt("2.00"), TransactionType.DEPOSIT,
                                Fixtures.amt("1.00"), Fixtures.amt("3.00")),
                        Fixtures.tx(existingTx, account, null, "dollar",
                                Fixtures.amt("9.00"), TransactionType.DEPOSIT,
                                Fixtures.amt("1.00"), Fixtures.amt("10.00"))
                ));
                fail("appendBatch should throw when rollback fails");
            } catch (PersistenceException e) {
                Throwable cause = e.getCause();
                assertNotNull(cause, "PersistenceException must carry the original cause");
                assertTrue(cause instanceof SQLException, "Cause must be SQLException");
                SQLException sqlCause = (SQLException) cause;
                Throwable[] suppressed = sqlCause.getSuppressed();
                boolean foundRollbackFailure = false;
                for (Throwable t : suppressed) {
                    if (t instanceof SQLException s
                            && "simulated rollback failure".equals(s.getMessage())) {
                        foundRollbackFailure = true;
                        break;
                    }
                }
                assertTrue(foundRollbackFailure,
                        "Rollback failure must be suppressed on the original SQLException");
            }
            // The connection used by the failing batch must be closed (fail closed).
            SavingConnection lastUsed = failingDs.lastUsedConnection();
            assertNotNull(lastUsed, "A connection must have been borrowed");
            assertTrue(lastUsed.closed,
                    "Connection must be closed when rollback fails (fail closed)");
        } finally {
            real.close();
        }
    }

    // ---------- Fail-closed on auto-commit restore failure ----------

    @Test
    void autoCommitRestoreFailureClosesConnectionAndThrows() throws Exception {
        // After a successful commit, if setAutoCommit(true) fails, the connection must
        // be closed and the failure must be surfaced.
        Path db = dir.resolve("autocommit-fail.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            // First, set up the schema and an account using a normal backend.
            SqlConnectionProvider normalProvider = new SqlConnectionProvider(
                    new SingleConnectionDataSource(real));
            SqlBackend normalBackend = new SqlBackend(normalProvider, new SqliteDialect());
            normalBackend.initialize();
            UUID account = UUID.randomUUID();
            normalBackend.create(account, "test", Map.of("dollar", Fixtures.amt("0.00")));
            normalBackend.close();

            // Now use a backend where setAutoCommit(true) fails after commit.
            FailingAutoCommitDataSource failingDs = new FailingAutoCommitDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    failingDs, 10, 30_000L, 1_800_000L, false);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.setInitializedForTest(true);

            try {
                backend.appendBatch(java.util.List.of(
                        Fixtures.tx(UUID.randomUUID(), account, null, "dollar",
                                Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                                Fixtures.amt("0.00"), Fixtures.amt("1.00"))
                ));
                fail("appendBatch should throw when setAutoCommit(true) fails after commit");
            } catch (PersistenceException e) {
                Throwable cause = e.getCause();
                assertNotNull(cause, "PersistenceException must carry the cause");
                assertTrue(cause instanceof SQLException, "Cause must be SQLException");
            }
            SavingConnection lastUsed = failingDs.lastUsedConnection();
            assertNotNull(lastUsed, "A connection must have been borrowed");
            assertTrue(lastUsed.closed,
                    "Connection must be closed when auto-commit restore fails (fail closed)");
        } finally {
            real.close();
        }
    }

    // ---------- Fake DataSource for MySQL branch ----------

    private static final class FakeDataSource implements DataSource, AutoCloseable {
        private int borrowCount = 0;
        boolean poolClosed = false;

        @Override
        public Connection getConnection() throws SQLException {
            borrowCount++;
            return DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() { return null; }
        @Override
        public void setLogWriter(java.io.PrintWriter out) { }
        @Override
        public void setLoginTimeout(int seconds) { }
        @Override
        public int getLoginTimeout() { return 0; }
        @Override
        public java.util.logging.Logger getParentLogger() { return null; }
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }

        @Override
        public void close() throws Exception {
            poolClosed = true;
        }

        boolean isClosed(Connection c) {
            try {
                return c == null || c.isClosed();
            } catch (SQLException e) {
                return true;
            }
        }

        int getBorrowCount() { return borrowCount; }
    }

    /**
     * DataSource that simulates a Hikari pool with pool-size=1: each getConnection()
     * call returns a wrapper around the same physical connection. The wrapper's
     * close() is a no-op (simulating the pool returning the connection to its slot).
     * We track the getConnection() call count to detect nested borrow calls.
     */
    private static final class SingleSlotDataSource implements DataSource {
        private final Connection shared;
        private final AtomicInteger calls = new AtomicInteger(0);

        SingleSlotDataSource(Connection shared) {
            this.shared = shared;
        }

        @Override
        public Connection getConnection() throws SQLException {
            calls.incrementAndGet();
            return new PooledConnectionWrapper(shared);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        int getConnectionCallCount() {
            return calls.get();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /**
     * Connection wrapper that delegates to a shared physical connection but whose
     * close() is a no-op. Simulates Hikari pool behavior: closing the wrapper
     * returns the connection to the pool, not to the driver.
     */
    private static final class PooledConnectionWrapper implements Connection {
        private final Connection delegate;

        PooledConnectionWrapper(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            // no-op: simulating Hikari pool returning connection to its slot
        }

        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public java.sql.Statement createStatement(int rsType, int rsConc) throws SQLException { return delegate.createStatement(rsType, rsConc); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int rsType, int rsConc) throws SQLException { return delegate.prepareStatement(sql, rsType, rsConc); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int rsType, int rsConc) throws SQLException { return delegate.prepareCall(sql, rsType, rsConc); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public java.sql.Statement createStatement(int rsType, int rsConc, int rsHold) throws SQLException { return delegate.createStatement(rsType, rsConc, rsHold); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int rsType, int rsConc, int rsHold) throws SQLException { return delegate.prepareStatement(sql, rsType, rsConc, rsHold); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int rsType, int rsConc, int rsHold) throws SQLException { return delegate.prepareCall(sql, rsType, rsConc, rsHold); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int autoGenKeys) throws SQLException { return delegate.prepareStatement(sql, autoGenKeys); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int[] colIndexes) throws SQLException { return delegate.prepareStatement(sql, colIndexes); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, String[] colNames) throws SQLException { return delegate.prepareStatement(sql, colNames); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int millis) throws SQLException { delegate.setNetworkTimeout(executor, millis); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }

    /** DataSource that returns SavingConnection wrappers with failOnRollback=true. */
    private static final class FailingRollbackDataSource implements DataSource {
        private final Connection delegate;
        private SavingConnection last;

        FailingRollbackDataSource(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() {
            last = new SavingConnection(delegate);
            last.failOnRollback = true;
            return last;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        SavingConnection lastUsedConnection() {
            return last;
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** DataSource that returns SavingConnection wrappers with failOnSetAutoCommitTrue=true. */
    private static final class FailingAutoCommitDataSource implements DataSource {
        private final Connection delegate;
        private SavingConnection last;

        FailingAutoCommitDataSource(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() {
            last = new SavingConnection(delegate);
            last.failOnSetAutoCommitTrue = true;
            return last;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        SavingConnection lastUsedConnection() {
            return last;
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** DataSource that returns a new SavingConnection wrapper per borrow. */
    private static final class SingleConnectionDataSource implements DataSource {
        private final Connection delegate;

        SingleConnectionDataSource(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return new SavingConnection(delegate);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /**
     * Connection proxy that delegates to a real SQLite connection but can be configured
     * to throw on rollback() or setAutoCommit(true). Tracks whether close() was called.
     */
    private static final class SavingConnection implements Connection {
        private final Connection delegate;
        boolean failOnRollback = false;
        boolean failOnSetAutoCommitTrue = false;
        boolean closed = false;

        SavingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public void rollback() throws SQLException {
            if (failOnRollback) {
                throw new SQLException("simulated rollback failure");
            }
            delegate.rollback();
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            if (autoCommit && failOnSetAutoCommitTrue) {
                throw new SQLException("simulated setAutoCommit(true) failure");
            }
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public void close() {
            // Set the closed flag but do NOT close the delegate. This simulates a Hikari
            // pool returning the connection to its slot (the pool decides whether to
            // physically close). The fail-closed helpers (rollbackOrClose,
            // restoreAutoCommitOrClose) call close() to mark the connection as unsafe;
            // the delegate stays open so the test can verify state without breaking
            // subsequent operations on the same physical connection.
            closed = true;
        }

        // Delegate everything else
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public boolean isClosed() throws SQLException { return closed || delegate.isClosed(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public java.sql.Statement createStatement(int rsType, int rsConc) throws SQLException { return delegate.createStatement(rsType, rsConc); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int rsType, int rsConc) throws SQLException { return delegate.prepareStatement(sql, rsType, rsConc); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int rsType, int rsConc) throws SQLException { return delegate.prepareCall(sql, rsType, rsConc); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public java.sql.Statement createStatement(int rsType, int rsConc, int rsHold) throws SQLException { return delegate.createStatement(rsType, rsConc, rsHold); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int rsType, int rsConc, int rsHold) throws SQLException { return delegate.prepareStatement(sql, rsType, rsConc, rsHold); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int rsType, int rsConc, int rsHold) throws SQLException { return delegate.prepareCall(sql, rsType, rsConc, rsHold); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int autoGenKeys) throws SQLException { return delegate.prepareStatement(sql, autoGenKeys); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, int[] colIndexes) throws SQLException { return delegate.prepareStatement(sql, colIndexes); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql, String[] colNames) throws SQLException { return delegate.prepareStatement(sql, colNames); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int millis) throws SQLException { delegate.setNetworkTimeout(executor, millis); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }

        // Need Properties import for setClientInfo
    }
}
