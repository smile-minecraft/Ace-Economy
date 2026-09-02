package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistenceBackendFactory;
import com.smile.aceeconomy.infrastructure.persistence.StorageConfig;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle tests for the SQL cleanup rewrite. Each test below names the JDBC-observable
 * invariant it proves so future readers can tell which behavior is exercised when the test
 * double cannot fully mirror the real driver.
 *
 * <p>The fixtures here use an in-memory SQLite file plus a {@link Connection} wrapper that
 * records call counts and lets the test inject failures on {@code rollback()},
 * {@code setAutoCommit(true)} and {@code close()}. What the wrapper DOES model:</p>
 * <ul>
 *   <li>that the wrapper's {@code close()} sets the {@code closed} flag without throwing
 *       unless {@code failOnClose} is set;</li>
 *   <li>that the {@code closed} flag survives across multiple JDBC API calls so the test
 *       can assert the post-cleanup borrow is genuinely closed.</li>
 * </ul>
 *
 * <p>What the wrapper does NOT model:</p>
 * <ul>
 *   <li>real Hikari eviction semantics — we only assert the borrow is closed and the next
 *       operation succeeds;</li>
 *   <li>real driver auto-commit transitions on already-committed transactions — we only
 *       assert {@code setAutoCommit(true)} was called and the result is observable.</li>
 * </ul>
 */
final class SqlLifecycleTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    /**
     * Invariant: a SQL failure followed by a successful rollback must restore auto-commit
     * on the borrowed shared connection, so ordinary repository writes keep working.
     */
    @Test
    void rollbackSuccessAfterBatchFailureRestoresAutoCommit() throws Exception {
        Path db = dir.resolve("rollback-restore.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(conn);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();

            UUID account = UUID.randomUUID();
            UUID existing = UUID.randomUUID();
            backend.append(Fixtures.tx(existing, account, null, "dollar",
                    Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("1.00")));
            assertTrue(conn.getAutoCommit(), "sanity: connection starts in auto-commit");

            UUID good = UUID.randomUUID();
            assertThrows(PersistenceException.class, () -> backend.appendBatch(List.of(
                    Fixtures.tx(good, account, null, "dollar",
                            Fixtures.amt("2.00"), TransactionType.DEPOSIT,
                            Fixtures.amt("1.00"), Fixtures.amt("3.00")),
                    Fixtures.tx(existing, account, null, "dollar",
                            Fixtures.amt("9.00"), TransactionType.DEPOSIT,
                            Fixtures.amt("1.00"), Fixtures.amt("10.00"))
            )));

            assertTrue(conn.getAutoCommit(),
                    "after a failed batch with a successful rollback, auto-commit must be restored");
            backend.close();
        } finally {
            conn.close();
        }
    }

    /**
     * Invariant: rollback failure must NOT swallow the close failure. The original
     * exception chain on the typed error must carry rollback AND close as suppressed
     * causes so an operator can see why the cleanup could not complete cleanly.
     */
    @Test
    void rollbackFailurePreservesPrimaryAndCloseAsSuppressed() throws Exception {
        Path db = dir.resolve("rollback-chain.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SqlConnectionProvider normalProvider = new SqlConnectionProvider(
                    new SingleConnectionDataSource(real));
            SqlBackend normalBackend = new SqlBackend(normalProvider, new SqliteDialect());
            normalBackend.initialize();
            UUID account = UUID.randomUUID();
            normalBackend.create(account, "alice", Map.of("dollar", Fixtures.amt("0.00")));
            UUID existing = UUID.randomUUID();
            normalBackend.append(Fixtures.tx(existing, account, null, "dollar",
                    Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("1.00")));
            normalBackend.close();

            RollbackAndCloseFailDataSource failing = new RollbackAndCloseFailDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    failing, 10, 30_000L, 1_800_000L, false);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.setInitializedForTest(true);

            UUID good = UUID.randomUUID();
            PersistenceException pe = assertThrows(PersistenceException.class,
                    () -> backend.appendBatch(List.of(
                            Fixtures.tx(good, account, null, "dollar",
                                    Fixtures.amt("2.00"), TransactionType.DEPOSIT,
                                    Fixtures.amt("1.00"), Fixtures.amt("3.00")),
                            Fixtures.tx(existing, account, null, "dollar",
                                    Fixtures.amt("9.00"), TransactionType.DEPOSIT,
                                    Fixtures.amt("1.00"), Fixtures.amt("10.00"))
                    )));

            Throwable cause = pe.getCause();
            assertNotNull(cause, "primary SQL exception must be the cause");
            assertTrue(cause instanceof SQLException, "cause must be SQLException");
            Throwable[] suppressed = cause.getSuppressed();
            boolean rollbackSeen = false;
            boolean closeSeen = false;
            for (Throwable t : suppressed) {
                if (t instanceof SQLException s) {
                    if ("simulated rollback failure".equals(s.getMessage())) {
                        rollbackSeen = true;
                    }
                    if ("simulated close failure".equals(s.getMessage())) {
                        closeSeen = true;
                    }
                }
            }
            assertTrue(rollbackSeen, "rollback failure must be on suppressed chain");
            assertTrue(closeSeen,
                    "close failure must NOT be silently swallowed; must appear on suppressed chain");
            assertTrue(failing.last.closed,
                    "connection must be marked closed (fail-closed) when rollback fails");
            failing.last.failOnClose = false;
            backend.close();
        } finally {
            real.close();
        }
    }

    /**
     * Invariant: a successful commit followed by a {@code setAutoCommit(true)} failure must
     * close the wrapper AND surface a typed exception whose message makes it unambiguous
     * that the data IS committed (so the operator does not roll back).
     */
    @Test
    void commitSuccessWithRestoreFailureClosesConnectionAndSaysCommitSucceeded() throws Exception {
        Path db = dir.resolve("commit-restore.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SqlConnectionProvider normalProvider = new SqlConnectionProvider(
                    new SingleConnectionDataSource(real));
            SqlBackend normalBackend = new SqlBackend(normalProvider, new SqliteDialect());
            normalBackend.initialize();
            UUID account = UUID.randomUUID();
            normalBackend.create(account, "alice", Map.of("dollar", Fixtures.amt("0.00")));
            normalBackend.close();

            RestoreFailingDataSource failing = new RestoreFailingDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    failing, 10, 30_000L, 1_800_000L, false);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.setInitializedForTest(true);

            UUID good = UUID.randomUUID();
            PersistenceException pe = assertThrows(PersistenceException.class, () -> backend.appendBatch(List.of(
                    Fixtures.tx(good, account, null, "dollar",
                            Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                            Fixtures.amt("0.00"), Fixtures.amt("1.00"))
            )));
            String lower = pe.getMessage() == null ? "" : pe.getMessage().toLowerCase();
            assertTrue(lower.contains("commit") || lower.contains("committed"),
                    "operator guidance must mention commit so the caller does not roll back: "
                            + pe.getMessage());
            assertTrue(failing.last.closed,
                    "connection must be marked closed when restore fails after a successful commit");
            backend.close();
        } finally {
            real.close();
        }
    }

    /**
     * Invariant: redeem's replay branch must run its own rollback + restore cleanup. If
     * the restore step fails, the connection must be marked unsafe (fail closed) and the
     * call must NOT return a misleading replay signal — the state is genuinely uncertain
     * so a typed error is the only honest answer.
     */
    @Test
    void redeemReplayRestoreFailureFailsClosedInsteadOfReturningReplay() throws Exception {
        Path db = dir.resolve("replay-restore.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SqlConnectionProvider normalProvider = new SqlConnectionProvider(
                    new SingleConnectionDataSource(real));
            SqlBackend normalBackend = new SqlBackend(normalProvider, new SqliteDialect());
            normalBackend.initialize();
            UUID account = UUID.randomUUID();
            normalBackend.create(account, "alice", Map.of("dollar", Fixtures.amt("0.00")));
            UUID nonce = UUID.randomUUID();
            RedemptionResult first = normalBackend.redeem(nonce, account, "dollar",
                    Fixtures.amt("25.00"));
            assertTrue(first.isCommitted(), "first redeem must commit");
            normalBackend.close();

            ReplayRestoreFailDataSource failing = new ReplayRestoreFailDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    failing, 10, 30_000L, 1_800_000L, false);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.setInitializedForTest(true);

            assertThrows(PersistenceException.class,
                    () -> backend.redeem(nonce, account, "dollar", Fixtures.amt("25.00")),
                    "replay-cleanup restore failure must surface as PersistenceException, "
                            + "not as a misleading replay signal");
            assertTrue(failing.last.closed,
                    "replay-cleanup restore failure must close the connection (fail closed)");
            backend.close();
        } finally {
            real.close();
        }
    }

    /**
     * Invariant: after the underlying connection of the pool has been closed out from
     * under the backend, the next operation must get a fresh JDBC connection from the
     * DataSource and successfully persist a write. Proved by observable JDBC state
     * (loadAll after the write), not just by a borrow-call counter.
     */
    @Test
    void nextOperationAfterConnectionClosedByPoolUsesAFreshConnection() throws Exception {
        Path db = dir.resolve("recovery.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            DropOnCloseDataSource pool = new DropOnCloseDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(pool);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Fixtures.amt("0.00")));

            pool.dropUnderlyingConnection();

            UUID txId = UUID.randomUUID();
            backend.append(Fixtures.tx(txId, account, null, "dollar",
                    Fixtures.amt("5.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("5.00")));
            assertEquals(1, backend.loadAll().size(),
                    "after underlying connection loss, the backend must still persist the write");
            assertEquals(txId, backend.loadAll().get(0).id());
            assertTrue(pool.freshConnectionsIssued() >= 1,
                    "DataSource must have issued a fresh connection after the underlying drop");
            backend.close();
        } finally {
            real.close();
        }
    }

    /**
     * Invariant: a safe operation (no failures) must return the borrowed connection to the
     * provider-owned pool exactly once. The provider, not the transaction helper, owns the
     * physical close at shutdown.
     */
    @Test
    void safeOperationClosesConnectionExactlyOnce() throws Exception {
        Path db = dir.resolve("safe-close.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            CloseCountingDataSource ds = new CloseCountingDataSource(real);
            SqlConnectionProvider provider = new SqlConnectionProvider(ds, 10, 30_000L, 1_800_000L);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();

            // Run a transaction that should succeed.
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Fixtures.amt("0.00")));

            int closedAfterCreate = ds.distinctCloseCount;
            // Each read owns and releases its own wrapper exactly once.
            backend.isConsumed(UUID.randomUUID());
            assertEquals(closedAfterCreate, ds.distinctCloseCount,
                    "a read must return the wrapper without closing the physical connection");
            backend.isConsumed(UUID.randomUUID());
            assertEquals(closedAfterCreate, ds.distinctCloseCount,
                    "each safe borrow must reuse the idle physical connection");
            backend.close();
            assertEquals(closedAfterCreate + 1, ds.distinctCloseCount,
                    "provider shutdown must close the physical connection once");
        } finally {
            real.close();
        }
    }

    /**
     * Invariant: when the DataSource is acquired but initialize() throws, the DataSource
     * is closed exactly once and no cleanup callback is registered.
     */
    @Test
    void factoryMysqlInitializationFailureClosesDataSourceWithoutLeak() throws Exception {
        CountingAutoCloseableDataSource ds = new CountingAutoCloseableDataSource();
        ds.failOnGetConnection = true;
        List<Runnable> cleanups = new ArrayList<>();
        PersistenceBackendFactory.ResourceRegistry registry = cleanups::add;
        StorageConfig.Mysql config = new StorageConfig.Mysql(
                "jdbc:mysql://localhost:3306/test", "u", "p", 1, 60_000L);
        PersistenceBackendFactory.MysqlDataSourceFactory factory =
                (jdbcUrl, u, p, poolSize, maxLifetimeMs) -> ds;

        assertThrows(Exception.class, () -> PersistenceBackendFactory.create(
                config, registry, null, factory));
        assertEquals(1, ds.closeCount,
                "DataSource must be closed exactly once when initialize fails (no leak)");
        assertEquals(0, cleanups.size(),
                "no cleanup callback may be registered when initialize fails");
    }

    /**
     * Invariant: factory shutdown calls backend.close() exactly once — the provider
     * closure it triggers must be the single ownership path, with no defensive close
     * on the raw connection afterwards.
     */
    @Test
    void factoryShutdownReleasesSqliteConnectionExactlyOnce() throws Exception {
        Path db = dir.resolve("factory-shutdown.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            CloseCountingConnectionWrapper counting = new CloseCountingConnectionWrapper(real);
            List<Runnable> cleanups = new ArrayList<>();
            PersistenceBackendFactory.ResourceRegistry registry = cleanups::add;
            StorageConfig.Sqlite config = new StorageConfig.Sqlite(db);
            PersistenceBackendFactory.SqliteConnector connector = file -> counting;

            PersistenceBackendFactory.WiringResult result = PersistenceBackendFactory.create(
                    config, registry, connector, null);
            assertNotNull(result, "factory must build a wiring result");

            for (Runnable cleanup : cleanups) {
                cleanup.run();
            }
            assertEquals(1, counting.closeCount,
                    "SQLite connection must be closed exactly once during shutdown (no double-close)");
        } finally {
            real.close();
        }
    }

    // ============================================================
    // Support doubles for lifecycle assertions
    // ============================================================

    /** Wraps a delegate, records call counts and lets the test inject failures. */
    static class CountingConnection implements Connection {
        final Connection delegate;
        boolean failOnRollback;
        boolean failOnClose;
        boolean failOnSetAutoCommitTrue;
        int rollbackCalls;
        int closeCalls;
        int setAutoCommitCalls;
        boolean closed;

        CountingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        @Override public void rollback() throws SQLException {
            rollbackCalls++;
            if (failOnRollback) throw new SQLException("simulated rollback failure");
            delegate.rollback();
        }
        @Override public void close() throws SQLException {
            closeCalls++;
            closed = true;
            if (failOnClose) throw new SQLException("simulated close failure");
        }
        @Override public boolean isClosed() throws SQLException { return closed || delegate.isClosed(); }
        @Override public void setAutoCommit(boolean a) throws SQLException {
            setAutoCommitCalls++;
            if (a && failOnSetAutoCommitTrue) {
                throw new SQLException("simulated setAutoCommit(true) failure");
            }
            delegate.setAutoCommit(a);
        }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s) throws SQLException { return delegate.prepareStatement(s); }
        @Override public java.sql.CallableStatement prepareCall(String s) throws SQLException { return delegate.prepareCall(s); }
        @Override public String nativeSQL(String s) throws SQLException { return delegate.nativeSQL(s); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean r) throws SQLException { delegate.setReadOnly(r); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String c) throws SQLException { delegate.setCatalog(c); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int l) throws SQLException { delegate.setTransactionIsolation(l); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public java.sql.Statement createStatement(int a, int b) throws SQLException { return delegate.createStatement(a, b); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b) throws SQLException { return delegate.prepareStatement(s, a, b); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b) throws SQLException { return delegate.prepareCall(s, a, b); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(java.sql.Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(java.sql.Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public java.sql.Statement createStatement(int a, int b, int c) throws SQLException { return delegate.createStatement(a, b, c); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b, int c) throws SQLException { return delegate.prepareStatement(s, a, b, c); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b, int c) throws SQLException { return delegate.prepareCall(s, a, b, c); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int k) throws SQLException { return delegate.prepareStatement(s, k); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int[] k) throws SQLException { return delegate.prepareStatement(s, k); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, String[] k) throws SQLException { return delegate.prepareStatement(s, k); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String n, String v) throws java.sql.SQLClientInfoException { delegate.setClientInfo(n, v); }
        @Override public void setClientInfo(Properties p) throws java.sql.SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String t, Object[] e) throws SQLException { return delegate.createArrayOf(t, e); }
        @Override public java.sql.Struct createStruct(String t, Object[] a) throws SQLException { return delegate.createStruct(t, a); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor e) throws SQLException { delegate.abort(e); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor e, int m) throws SQLException { delegate.setNetworkTimeout(e, m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    private static final class SingleConnectionDataSource implements DataSource {
        private final Connection delegate;
        SingleConnectionDataSource(Connection delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() { return new CountingConnection(delegate); }
        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** DataSource whose connections fail both rollback() and close(). */
    private static final class RollbackAndCloseFailDataSource implements DataSource {
        final Connection delegate;
        CountingConnection last;
        RollbackAndCloseFailDataSource(Connection delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() {
            last = new CountingConnection(delegate);
            last.failOnRollback = true;
            last.failOnClose = true;
            return last;
        }
        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    /** DataSource whose connections fail {@code setAutoCommit(true)} once. */
    private static final class RestoreFailingDataSource implements DataSource {
        final Connection delegate;
        CountingConnection last;
        RestoreFailingDataSource(Connection delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() {
            last = new CountingConnection(delegate);
            last.failOnSetAutoCommitTrue = true;
            return last;
        }
        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    private static final class ReplayRestoreFailDataSource implements DataSource {
        final Connection delegate;
        CountingConnection last;
        boolean onceFailed;
        ReplayRestoreFailDataSource(Connection delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() {
            last = new ReplayRestoreFailConnection(delegate);
            return last;
        }
        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
        private final class ReplayRestoreFailConnection extends CountingConnection {
            ReplayRestoreFailConnection(Connection d) { super(d); }
            @Override public void setAutoCommit(boolean autoCommit) throws SQLException {
                if (autoCommit && !onceFailed) {
                    onceFailed = true;
                    throw new SQLException("simulated replay restore failure");
                }
                super.setAutoCommit(autoCommit);
            }
        }
    }

    private static final class DropOnCloseDataSource implements DataSource {
        private final Connection firstDelegate;
        private final Path dbFile;
        private final AtomicInteger borrows = new AtomicInteger(0);
        private boolean dropped = false;
        DropOnCloseDataSource(Connection delegate) {
            this.firstDelegate = delegate;
            String url;
            try { url = delegate.getMetaData().getURL(); } catch (SQLException e) { url = ""; }
            this.dbFile = derivePath(url);
        }
        void dropUnderlyingConnection() throws SQLException {
            dropped = true;
            firstDelegate.close();
        }
        int freshConnectionsIssued() {
            return Math.max(0, borrows.get() - 1);
        }
        @Override public Connection getConnection() throws SQLException {
            int n = borrows.incrementAndGet();
            if (n == 1 || !dropped) {
                return new CountingConnection(firstDelegate);
            }
            Connection fresh = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            return new CountingConnection(fresh);
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
        private static Path derivePath(String url) {
            String prefix = "jdbc:sqlite:";
            if (url.startsWith(prefix)) {
                return Path.of(url.substring(prefix.length()));
            }
            return Path.of("unknown.db");
        }
    }

    /**
     * DataSource that returns a CountingConnection wrapper per borrow. Tracks how many
     * distinct wrapper close() calls happened so the test can prove "no double-close" by
     * observing exactly one close per borrowed wrapper.
     */
    private static final class CloseCountingDataSource implements DataSource {
        private final Connection delegate;
        private final java.util.Set<CountingConnection> wrappers =
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        int distinctCloseCount;

        CloseCountingDataSource(Connection delegate) {
            this.delegate = delegate;
        }

        @Override public Connection getConnection() {
            CountingConnection wrapped = new CountingConnection(delegate) {
                @Override public void close() throws SQLException {
                    super.close();
                    distinctCloseCount++;
                }
            };
            wrappers.add(wrapped);
            return wrapped;
        }

        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    private static final class CloseCountingConnectionWrapper implements Connection {
        final Connection delegate;
        int closeCount;

        CloseCountingConnectionWrapper(Connection delegate) {
            this.delegate = delegate;
        }

        @Override public void close() throws SQLException {
            closeCount++;
            delegate.close();
        }

        @Override public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s) throws SQLException { return delegate.prepareStatement(s); }
        @Override public java.sql.CallableStatement prepareCall(String s) throws SQLException { return delegate.prepareCall(s); }
        @Override public String nativeSQL(String s) throws SQLException { return delegate.nativeSQL(s); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void setAutoCommit(boolean a) throws SQLException { delegate.setAutoCommit(a); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean r) throws SQLException { delegate.setReadOnly(r); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String c) throws SQLException { delegate.setCatalog(c); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int l) throws SQLException { delegate.setTransactionIsolation(l); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public java.sql.Statement createStatement(int a, int b) throws SQLException { return delegate.createStatement(a, b); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b) throws SQLException { return delegate.prepareStatement(s, a, b); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b) throws SQLException { return delegate.prepareCall(s, a, b); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(java.sql.Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(java.sql.Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public java.sql.Statement createStatement(int a, int b, int c) throws SQLException { return delegate.createStatement(a, b, c); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b, int c) throws SQLException { return delegate.prepareStatement(s, a, b, c); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b, int c) throws SQLException { return delegate.prepareCall(s, a, b, c); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int k) throws SQLException { return delegate.prepareStatement(s, k); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int[] k) throws SQLException { return delegate.prepareStatement(s, k); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, String[] k) throws SQLException { return delegate.prepareStatement(s, k); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String n, String v) throws java.sql.SQLClientInfoException { delegate.setClientInfo(n, v); }
        @Override public void setClientInfo(Properties p) throws java.sql.SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String t, Object[] e) throws SQLException { return delegate.createArrayOf(t, e); }
        @Override public java.sql.Struct createStruct(String t, Object[] a) throws SQLException { return delegate.createStruct(t, a); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor e) throws SQLException { delegate.abort(e); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor e, int m) throws SQLException { delegate.setNetworkTimeout(e, m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    private static final class CountingAutoCloseableDataSource implements DataSource, AutoCloseable {
        int closeCount = 0;
        boolean failOnGetConnection = false;
        @Override public Connection getConnection() throws SQLException {
            if (failOnGetConnection) {
                throw new SQLException("simulated init failure: getConnection");
            }
            return null;
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public void close() { closeCount++; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }
}
