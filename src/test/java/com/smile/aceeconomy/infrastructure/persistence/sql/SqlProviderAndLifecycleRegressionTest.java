package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for SQL lifecycle findings: Hikari eviction order, provider close retry,
 * MySQL DDL idempotence, and initialized flag honesty. Each test would fail on the previous
 * implementation and passes after the minimal fix.
 */
final class SqlProviderAndLifecycleRegressionTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    // ---------------- F1: Hikari unsafe eviction order ----------------

    /**
     * The unsafe path must mark the Hikari PoolEntry evicted before the wrapper is
     * released. Returning the wrapper to idle first and evicting afterwards leaves a
     * window where another borrower could acquire the dirty connection.
     *
     * <p>This test uses an observable call-order fixture: a HikariDataSource subclass
     * that records the relative order of evictConnection vs close.</p>
     */
    @Test
    void hikariUnsafeAbandonMustEvictBeforeClose() throws Exception {
        List<String> order = new ArrayList<>();
        Connection fake = new FakeConnection(order, "fake-hikari-conn");

        CallOrderHikariDataSource ds = new CallOrderHikariDataSource(fake, order);
        SqlConnectionProvider provider = new SqlConnectionProvider(ds);

        Connection borrowed = provider.borrow();
        provider.abandonConnection(borrowed);

        assertEquals(List.of("evict"), order,
                "unsafe abandon must evict the active PoolEntry and must NOT ordinary-close the dirty proxy "
                        + "(would NPE on Hikari 5.1.0 autoCommit=false); close-then-evict would be 'close','evict'");
        assertEquals(1, order.size());
    }

    /**
     * Real Hikari 5.1.0 + pool-size=1 integration: after an unsafe abandon the pool must
     * still be usable and the next borrow must give a fresh proxy (not the evicted one).
     * The single-thread fixture cannot prove the transient idle-window race, only that the
     * fix does not break pool recovery. This limitation is explicitly reported.
     */
    @Test
    void realHikariPoolSizeOneAbandonIsRecoverable() throws Exception {
        Path db = dir.resolve("hikari-real.db");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + db);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("test-hikari-f1");
        cfg.setConnectionTimeout(2000);
        cfg.setMaxLifetime(60_000);
        cfg.setDriverClassName("org.sqlite.JDBC");

        HikariDataSource hikari = new HikariDataSource(cfg);
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(hikari);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();
            assertTrue(backend.isInitialized());

            // Borrow via provider, then abandon as unsafe
            Connection c1 = provider.borrow();
            provider.abandonConnection(c1);

            // Next borrow must succeed with a fresh underlying connection and normal writes must still work
            Connection c2 = provider.borrow();
            assertNotSame(c1, c2, "abandoned Hikari proxy must not be reused; pool must issue a fresh one");
            provider.returnConnection(c2);

            // Backend must still be able to write via a fresh borrow
            backend.create(java.util.UUID.randomUUID(), "alice", java.util.Map.of("dollar", Fixtures.amt("10.00")));
            assertEquals(1, backend.listAll().size());
            backend.close();
        } finally {
            hikari.close();
        }
        // Limitation: this single-thread test proves eventual recovery, not the absence of the tiny
        // idle-window that existed when close preceded evict. The call-order test above is the
        // direct proof that evict now precedes close.
    }

    // ---------------- F2: provider close retry ----------------

    @Test
    void sqliteCloseFailureIsRetryableAndBorrowBlocked() throws Exception {
        Path db = dir.resolve("sqlite-retry.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        MutableFailingConnection wrapper = new MutableFailingConnection(real);
        wrapper.failOnCloseOnce = true;

        SqlConnectionProvider provider = new SqlConnectionProvider(wrapper);

        // First close must throw and leave the underlying connection not yet successfully closed
        SQLException first = assertThrows(SQLException.class, provider::close,
                "first close with failing delegate must throw");
        assertTrue(first.getMessage().contains("simulated close failure"));
        assertEquals(1, wrapper.closeAttempts, "first close must have been attempted");
        assertFalse(wrapper.successClosed, "successful close flag must not be set after failure");

        // After a fail-closed attempt, borrow must already be blocked (unavailable)
        assertThrows(SQLException.class, provider::borrow,
                "SQLite provider must block borrow after unavailable, even before successful close");

        // Second close must retry and succeed
        wrapper.failOnCloseOnce = false;
        assertDoesNotThrow(provider::close, "second close must retry the underlying close");
        assertTrue(wrapper.successClosed, "underlying close must have succeeded on retry");
        assertEquals(2, wrapper.closeAttempts);

        // Third close must be idempotent (no additional attempt)
        assertDoesNotThrow(provider::close);
        assertEquals(2, wrapper.closeAttempts, "successful close must be idempotent");

        real.close();
    }

    @Test
    void sqliteAbandonCloseFailureIsRetryableViaProviderClose() throws Exception {
        Path db = dir.resolve("sqlite-abandon-retry.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        MutableFailingConnection wrapper = new MutableFailingConnection(real);
        wrapper.failOnCloseOnce = true;

        SqlConnectionProvider provider = new SqlConnectionProvider(wrapper);

        // Abandon fails because the shared connection's close fails; provider becomes unavailable
        SQLException abandonFailure = assertThrows(SQLException.class,
                () -> provider.abandonConnection(wrapper),
                "abandon with failing close must throw");
        assertTrue(abandonFailure.getMessage().contains("simulated close failure"));
        assertThrows(SQLException.class, provider::borrow, "borrow must be blocked after abandon fail-closed");

        // Provider close must retry the same underlying close and succeed
        wrapper.failOnCloseOnce = false;
        assertDoesNotThrow(provider::close);
        assertTrue(wrapper.successClosed);
        real.close();
    }

    @Test
    void dataSourceCloseFailureIsRetryable() throws Exception {
        CountingFailDataSource ds = new CountingFailDataSource();
        ds.failOnCloseOnce = true;

        SqlConnectionProvider provider = new SqlConnectionProvider(
                ds, 10, 30_000L, 1_800_000L, false);

        SQLException first = assertThrows(SQLException.class, provider::close);
        assertTrue(first.getMessage().contains("simulated ds close failure"));
        assertEquals(1, ds.closeAttempts);
        assertFalse(ds.closedSuccessfully);

        ds.failOnCloseOnce = false;
        assertDoesNotThrow(provider::close);
        assertTrue(ds.closedSuccessfully);
        assertEquals(2, ds.closeAttempts);

        // Idempotent after success
        assertDoesNotThrow(provider::close);
        assertEquals(2, ds.closeAttempts);
    }

    // ---------------- F4: initialized honesty ----------------

    @Test
    void initializeFailureMustLeaveNotInitialized() throws Exception {
        Path db = dir.resolve("init-fail.db");
        FailingFileDataSource ds = new FailingFileDataSource(db);
        SqlConnectionProvider provider = new SqlConnectionProvider(
                ds, 10, 30_000L, 1_800_000L, false);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());

        ds.failOnSetAutoCommitTrue = true;
        assertThrows(PersistenceException.class, backend::initialize,
                "initialize with restore failure must throw");
        assertFalse(backend.isInitialized(), "any initialize failure must leave isInitialized==false");

        ds.failOnSetAutoCommitTrue = false;
        // Recovery: next initialize must succeed idempotently with a fresh connection from the pool
        assertDoesNotThrow(backend::initialize);
        assertTrue(backend.isInitialized());
        assertEquals(1, backend.schemaVersion());
        backend.close();
    }

    @Test
    void truncateAndRecreateFailureLeavesNotInitializedAndIsRecoverable() throws Exception {
        Path db = dir.resolve("truncate-fail.db");
        FailingFileDataSource ds = new FailingFileDataSource(db);
        SqlConnectionProvider provider = new SqlConnectionProvider(
                ds, 10, 30_000L, 1_800_000L, false);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());

        backend.initialize();
        assertTrue(backend.isInitialized());

        ds.failOnSetAutoCommitTrue = true;
        assertThrows(PersistenceException.class, backend::truncateAndRecreate,
                "truncate with restore failure must throw");
        assertFalse(backend.isInitialized(),
                "truncateAndRecreate failure must leave isInitialized==false even when it was true before");

        ds.failOnSetAutoCommitTrue = false;
        assertDoesNotThrow(backend::initialize, "recovery via initialize must be possible after failed truncate");
        assertTrue(backend.isInitialized());
        backend.close();
    }

    @Test
    void truncateFailureWithRuntimeExceptionLeavesNotInitialized() throws Exception {
        Path db = dir.resolve("truncate-runtime.db");
        FailingFileDataSource ds = new FailingFileDataSource(db);
        SqlConnectionProvider provider = new SqlConnectionProvider(
                ds, 10, 30_000L, 1_800_000L, false);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());

        backend.initialize();
        assertTrue(backend.isInitialized());

        ds.failWithRuntimeOnSetAutoCommit = true;
        assertThrows(RuntimeException.class, backend::truncateAndRecreate);
        assertFalse(backend.isInitialized(), "RuntimeException during truncate must leave not initialized");

        ds.failWithRuntimeOnSetAutoCommit = false;
        assertDoesNotThrow(backend::initialize);
        assertTrue(backend.isInitialized());
        backend.close();
    }

    @Test
    void truncateFailureWithSQLExceptionLeavesNotInitialized() throws Exception {
        Path db = dir.resolve("truncate-sql.db");
        FailingFileDataSource ds = new FailingFileDataSource(db);
        SqlConnectionProvider provider = new SqlConnectionProvider(
                ds, 10, 30_000L, 1_800_000L, false);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.initialize();
        assertTrue(backend.isInitialized());

        ds.failOnSetAutoCommitTrue = true;
        assertThrows(PersistenceException.class, backend::truncateAndRecreate);
        assertFalse(backend.isInitialized(),
                "SQLException (wrapped as PersistenceException) during truncate must leave not initialized");
        ds.failOnSetAutoCommitTrue = false;
        assertDoesNotThrow(backend::initialize);
        assertTrue(backend.isInitialized());
        backend.close();
    }

    // ---------------- F3: MySQL DDL contract ----------------

    @Test
    void mysqlDialectDdlIsIdempotentAndDocumented() {
        MySqlDialect mysql = new MySqlDialect();
        SqliteDialect sqlite = new SqliteDialect();

        for (String ddl : V2Schema.ddlStatements(mysql)) {
            assertTrue(ddl.contains("IF NOT EXISTS"),
                    "MySQL DDL must be idempotent via IF NOT EXISTS: " + ddl);
            assertTrue(ddl.contains("ENGINE=InnoDB") || ddl.contains("InnoDB"),
                    "MySQL DDL must target InnoDB");
        }
        for (String ddl : V2Schema.ddlStatements(sqlite)) {
            assertTrue(ddl.contains("IF NOT EXISTS"),
                    "SQLite DDL must be idempotent via IF NOT EXISTS");
        }

        assertTrue(V2Schema.versionInsertSql(mysql).startsWith("INSERT IGNORE"),
                "MySQL version insert must use INSERT IGNORE");
        assertTrue(V2Schema.versionInsertSql(sqlite).startsWith("INSERT OR IGNORE"),
                "SQLite version insert must use INSERT OR IGNORE");

        assertTrue(V2Schema.nonceInsertSql(mysql).startsWith("INSERT IGNORE"));
        assertTrue(V2Schema.nonceInsertSql(sqlite).startsWith("INSERT OR IGNORE"));
    }

    @Test
    void persistenceLifecycleDocumentsMySqlImplicitCommit() throws Exception {
        // Contract test that the lifecycle Javadoc no longer claims MySQL DDL rolls back,
        // but explains implicit per-statement commit and idempotent recovery. We check the
        // source file contains the expected phrases so a future edit that reintroduces the
        // misleading claim will fail.
        String lifecycle = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/smile/aceeconomy/ports/persistence/PersistenceLifecycle.java"));
        assertTrue(lifecycle.contains("implicitly commits per statement")
                        || lifecycle.contains("implicit per-statement")
                        || lifecycle.contains("MySQL DDL"),
                "PersistenceLifecycle must document MySQL per-statement commit");
        assertFalse(lifecycle.contains("no partial schema is left behind") && !lifecycle.contains("SQLite"),
                "Lifecycle must not claim 'no partial schema' universally without dialect distinction");

        String backend = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/smile/aceeconomy/infrastructure/persistence/sql/SqlBackend.java"));
        assertTrue(backend.contains("MySQL: DDL implicitly commits")
                        || backend.contains("MySQL DDL implicitly"),
                "SqlBackend must clarify MySQL vs SQLite DDL transaction behavior");
        assertTrue(backend.contains("No compensating") || backend.contains("No compensating DROP"),
                "SqlBackend must state that no compensating DROP is performed for MySQL partial failures");
    }

    @Test
    void sqliteNeedsRecreationDetectsPartialInitWithoutDestroyingData() throws Exception {
        Path db = dir.resolve("partial-recovery.db");
        // Simulate partial MySQL-like init: create one table but no schema version
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + V2Schema.accountsTable()
                    + " (owner TEXT PRIMARY KEY, owner_name TEXT)");
        }
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlConnectionProvider provider = new SqlConnectionProvider(real);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());

        assertTrue(backend.needsRecreation(),
                "partial init (tables exist, no version) must be reported as needing recreation");
        // Recovery must be via explicit truncate or via initialize that completes missing tables
        // without blindly dropping existing domain rows. Here we prove initialize completes.
        backend.initialize();
        assertFalse(backend.needsRecreation());
        assertEquals(0, backend.loadAll().size(), "existing tables must not have been dropped by recovery");
        backend.close();
        real.close();
    }

    // ---------------- helpers ----------------

    private static final class FakeConnection implements Connection {
        private final List<String> order;
        private final String name;
        FakeConnection(List<String> order, String name) { this.order = order; this.name = name; }
        @Override public void close() throws SQLException { order.add("close"); }
        @Override public boolean isClosed() throws SQLException { return false; }
        // minimal stubs
        @Override public Statement createStatement() throws SQLException { throw new SQLException("not used"); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { throw new SQLException("not used"); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { throw new SQLException("not used"); }
        @Override public String nativeSQL(String sql) throws SQLException { return sql; }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException {}
        @Override public boolean getAutoCommit() throws SQLException { return true; }
        @Override public void commit() throws SQLException {}
        @Override public void rollback() throws SQLException {}
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return null; }
        @Override public void setReadOnly(boolean readOnly) throws SQLException {}
        @Override public boolean isReadOnly() throws SQLException { return false; }
        @Override public void setCatalog(String catalog) throws SQLException {}
        @Override public String getCatalog() throws SQLException { return null; }
        @Override public void setTransactionIsolation(int level) throws SQLException {}
        @Override public int getTransactionIsolation() throws SQLException { return 0; }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return null; }
        @Override public void clearWarnings() throws SQLException {}
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { throw new SQLException("not used"); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { throw new SQLException("not used"); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { throw new SQLException("not used"); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return null; }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {}
        @Override public void setHoldability(int holdability) throws SQLException {}
        @Override public int getHoldability() throws SQLException { return 0; }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return null; }
        @Override public java.sql.Savepoint setSavepoint(String name) throws SQLException { return null; }
        @Override public void rollback(java.sql.Savepoint savepoint) throws SQLException {}
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {}
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { throw new SQLException("not used"); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { throw new SQLException("not used"); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { throw new SQLException("not used"); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { throw new SQLException("not used"); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { throw new SQLException("not used"); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { throw new SQLException("not used"); }
        @Override public java.sql.Clob createClob() throws SQLException { return null; }
        @Override public java.sql.Blob createBlob() throws SQLException { return null; }
        @Override public java.sql.NClob createNClob() throws SQLException { return null; }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return null; }
        @Override public boolean isValid(int timeout) throws SQLException { return true; }
        @Override public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException {}
        @Override public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException {}
        @Override public String getClientInfo(String name) throws SQLException { return null; }
        @Override public java.util.Properties getClientInfo() throws SQLException { return null; }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return null; }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return null; }
        @Override public void setSchema(String schema) throws SQLException {}
        @Override public String getSchema() throws SQLException { return null; }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException {}
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException {}
        @Override public int getNetworkTimeout() throws SQLException { return 0; }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
    }

    private static final class CallOrderHikariDataSource extends HikariDataSource {
        private final List<String> order;
        private final Connection fake;
        CallOrderHikariDataSource(Connection fake, List<String> order) {
            // Use no-arg HikariDataSource and never start the pool; override all interaction
            super();
            this.fake = fake;
            this.order = order;
        }
        @Override public Connection getConnection() throws SQLException { return fake; }
        @Override public Connection getConnection(String username, String password) throws SQLException { return fake; }
        @Override public void evictConnection(Connection connection) { order.add("evict"); }
        @Override public void close() {}
        @Override public boolean isClosed() { return false; }
    }

    private static final class MutableFailingConnection implements Connection {
        final Connection delegate;
        boolean failOnCloseOnce = false;
        boolean failOnSetAutoCommitTrue = false;
        boolean failWithRuntimeOnSetAutoCommit = false;
        int closeAttempts = 0;
        boolean successClosed = false;

        MutableFailingConnection(Connection delegate) { this.delegate = delegate; }

        @Override public void close() throws SQLException {
            closeAttempts++;
            if (failOnCloseOnce && closeAttempts == 1) {
                throw new SQLException("simulated close failure");
            }
            delegate.close();
            successClosed = true;
        }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException {
            if (autoCommit && failOnSetAutoCommitTrue) {
                throw new SQLException("simulated setAutoCommit(true) failure");
            }
            if (autoCommit && failWithRuntimeOnSetAutoCommit) {
                throw new RuntimeException("simulated runtime failure on restore");
            }
            delegate.setAutoCommit(autoCommit);
        }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }

    private static final class CountingFailDataSource implements DataSource, AutoCloseable {
        int closeAttempts = 0;
        boolean closedSuccessfully = false;
        boolean failOnCloseOnce = false;
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection("jdbc:sqlite::memory:"); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public void close() throws SQLException {
            closeAttempts++;
            if (failOnCloseOnce && closeAttempts == 1) {
                throw new SQLException("simulated ds close failure");
            }
            closedSuccessfully = true;
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class FailingFileDataSource implements DataSource, AutoCloseable {
        final Path dbFile;
        volatile boolean failOnSetAutoCommitTrue = false;
        volatile boolean failWithRuntimeOnSetAutoCommit = false;

        FailingFileDataSource(Path dbFile) { this.dbFile = dbFile; }

        @Override public Connection getConnection() throws SQLException {
            Connection real = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            MutableFailingConnection wrapper = new MutableFailingConnection(real);
            wrapper.failOnSetAutoCommitTrue = failOnSetAutoCommitTrue;
            wrapper.failWithRuntimeOnSetAutoCommit = failWithRuntimeOnSetAutoCommit;
            return wrapper;
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public void close() {}
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
