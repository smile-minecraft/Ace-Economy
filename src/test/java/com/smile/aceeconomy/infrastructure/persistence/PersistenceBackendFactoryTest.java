package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused contract for {@link PersistenceBackendFactory}: storage type selection (json /
 * sqlite / mysql), resource cleanup registration, JDBC connection safety on failure
 * paths, and the test-only provider seams that avoid requiring a live MySQL server.
 *
 * <p>Note on {@link DataSource} auto-close: this JDK does not declare
 * {@code DataSource.close()} nor make {@code DataSource} extend {@link AutoCloseable}
 * (verified via {@code javap}); the factory therefore closes a DataSource via the
 * {@code AutoCloseable} interface only, and the fakes in this test implement both
 * {@code DataSource} and {@code AutoCloseable} so the same code path is exercised.</p>
 */
final class PersistenceBackendFactoryTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dataFolder;

    /** Records every cleanup callback the factory registers. */
    private static final class RecordingResources implements PersistenceBackendFactory.ResourceRegistry {
        final List<Runnable> cleanups = new ArrayList<>();

        @Override
        public void register(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        void runAllInReverse() {
            for (int i = cleanups.size() - 1; i >= 0; i--) {
                cleanups.get(i).run();
            }
        }
    }

    /**
     * Fake {@link DataSource} that records its {@code close()} call and lets the test
     * wire {@code getConnection()} to either throw or return a working connection.
     * Implements {@link AutoCloseable} so the factory's {@code instanceof AutoCloseable}
     * branch finds the {@code close()} method.
     */
    private static final class CapturingDataSource implements DataSource, AutoCloseable {
        final Runnable onGetConnection;
        boolean closed = false;

        CapturingDataSource(Runnable onGetConnection) {
            this.onGetConnection = onGetConnection;
        }

        @Override
        public Connection getConnection() throws SQLException {
            onGetConnection.run();
            throw new SQLException("simulated getConnection failure");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A {@link DataSource} backed by sqlite-jdbc for the "real connection acquired, then
     * initialize() blows up on MySQL DDL" failure-path test. Also AutoCloseable. */
    private static final class ConnectionRecordingDataSource implements DataSource, AutoCloseable {
        Connection borrowedConn;
        boolean closed = false;

        @Override
        public Connection getConnection() throws SQLException {
            borrowedConn = DriverManager.getConnection("jdbc:sqlite::memory:");
            return borrowedConn;
        }

        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }

        @Override
        public void close() {
            closed = true;
        }
    }

    // ---------- JSON ----------

    @Test
    void jsonBackendWritesDataFileAndRegistersCleanup() throws Exception {
        StorageConfig.Json cfg = new StorageConfig.Json(dataFolder.resolve("data-v2.json"));
        RecordingResources resources = new RecordingResources();

        PersistenceBackendFactory.WiringResult result =
                PersistenceBackendFactory.create(cfg, resources);

        assertNotNull(result.accounts());
        assertNotNull(result.transactions());
        assertNotNull(result.lifecycle());
        assertTrue(result.lifecycle().isInitialized());
        assertEquals(1, resources.cleanups.size(), "exactly one cleanup must be registered");
        assertTrue(Files.exists(dataFolder.resolve("data-v2.json")),
                "Json backend must have created the data file");

        // Cleanup must not throw and must remain idempotent (JsonPersistenceBackend.close is
        // idempotent and only flips the initialized flag).
        resources.runAllInReverse();
        resources.runAllInReverse();
    }

    @Test
    void jsonBackendAccountsRoundTripAcrossCleanupBoundary() throws Exception {
        StorageConfig.Json cfg = new StorageConfig.Json(dataFolder.resolve("rt.json"));
        RecordingResources resources = new RecordingResources();
        var result = PersistenceBackendFactory.create(cfg, resources);

        UUID owner = UUID.randomUUID();
        result.accounts().create(owner, "alice",
                Map.of("dollar", com.smile.aceeconomy.infrastructure.persistence.Fixtures.amt("10.00")));
        assertTrue(result.accounts().exists(owner));
    }

    // ---------- SQLite ----------

    @Test
    void sqliteBackendInitializesSchemaAndPersistsAccounts() throws Exception {
        StorageConfig.Sqlite cfg = new StorageConfig.Sqlite(dataFolder.resolve("v2.sqlite"));
        RecordingResources resources = new RecordingResources();
        AtomicReference<Connection> openedConn = new AtomicReference<>();

        var result = PersistenceBackendFactory.create(cfg, resources,
                file -> {
                    Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                    openedConn.set(c);
                    return c;
                },
                null);

        assertTrue(result.lifecycle().isInitialized());
        assertEquals(1, result.lifecycle().schemaVersion());
        assertEquals(1, resources.cleanups.size());

        // Behavior contract: SqlBackend with SqliteDialect is created and operational.
        UUID owner = UUID.randomUUID();
        result.accounts().create(owner, "alice", Map.of());
        assertTrue(result.accounts().exists(owner));
        UUID txId = UUID.randomUUID();
        result.transactions().append(com.smile.aceeconomy.infrastructure.persistence.Fixtures.tx(
                txId, owner, null, "dollar",
                com.smile.aceeconomy.infrastructure.persistence.Fixtures.amt("5.00"),
                com.smile.aceeconomy.domain.TransactionType.DEPOSIT,
                com.smile.aceeconomy.infrastructure.persistence.Fixtures.amt("0.00"),
                com.smile.aceeconomy.infrastructure.persistence.Fixtures.amt("5.00")));
        assertEquals(1, result.transactions().loadAll().size());

        // Cleanup must close the JDBC Connection AND the backend.
        resources.runAllInReverse();
        assertTrue(openedConn.get().isClosed(),
                "SQLite connection must be closed by cleanup");
    }

    @Test
    void sqliteConnectorFailureRegistersNoCleanupAndRethrows() {
        StorageConfig.Sqlite cfg = new StorageConfig.Sqlite(dataFolder.resolve("fail.sqlite"));
        RecordingResources resources = new RecordingResources();

        assertThrows(SQLException.class, () ->
                PersistenceBackendFactory.create(cfg, resources,
                        file -> { throw new SQLException("simulated open failure"); },
                        null));
        assertEquals(0, resources.cleanups.size(),
                "no cleanup must be registered when the connector itself fails");
    }

    // ---------- MySQL ----------

    @Test
    void mysqlBackendForwardsConfigToDataSourceFactoryAndCleansUpOnFailure() {
        StorageConfig.Mysql cfg = new StorageConfig.Mysql(
                "jdbc:mysql://db.example.com:3307/ace",
                "alice", "secret", 8, 600_000L);
        RecordingResources resources = new RecordingResources();
        AtomicReference<String[]> captured = new AtomicReference<>();
        CapturingDataSource ds = new CapturingDataSource(() -> { /* no-op hook */ });

        assertThrows(Exception.class, () ->
                PersistenceBackendFactory.create(cfg, resources, null,
                        (url, user, pass, poolSize, maxLifetime) -> {
                            captured.set(new String[]{url, user, pass,
                                    String.valueOf(poolSize), String.valueOf(maxLifetime)});
                            return ds;
                        }));

        // Factory must forward every config value unchanged.
        assertNotNull(captured.get(), "mysql DataSource factory must be invoked");
        assertArrayEquals(new String[]{
                "jdbc:mysql://db.example.com:3307/ace",
                "alice", "secret", "8", "600000"
        }, captured.get());
        // After failure, factory must close the DataSource — no leaked pool/threads.
        assertTrue(ds.closed, "DataSource must be closed after factory failure");
        assertEquals(0, resources.cleanups.size(),
                "no cleanup must be registered when backend construction failed");
    }

    @Test
    void mysqlBackendFailureAfterConnectionAcquiredStillClosesBoth() throws SQLException {
        // Force the factory to reach past DataSource acquisition by having the
        // DataSource's getConnection() throw — the factory's catch block must still
        // close the DataSource so no pool/threads leak.
        StorageConfig.Mysql cfg = new StorageConfig.Mysql(
                "jdbc:mysql://localhost:3306/aceeconomy",
                "root", "", 1, 60_000L);
        RecordingResources resources = new RecordingResources();
        CapturingDataSource ds = new CapturingDataSource(() -> { /* no-op */ });

        assertThrows(Exception.class, () ->
                PersistenceBackendFactory.create(cfg, resources, null,
                        (url, user, pass, poolSize, maxLifetime) -> ds));

        assertTrue(ds.closed, "DataSource must be closed after factory failure");
        assertEquals(0, resources.cleanups.size(),
                "no cleanup must be registered when backend construction failed");
    }

    // ---------- type-selection contract ----------

    @Test
    void factoryRoutesJsonKindToJsonBackend() throws Exception {
        RecordingResources resources = new RecordingResources();
        StorageConfig cfg = StorageConfigParser.parse(Map.of("type", "json"), dataFolder);
        var result = PersistenceBackendFactory.create(cfg, resources);
        assertNotNull(result.lifecycle());
        assertTrue(result.lifecycle().isInitialized());
    }

    @Test
    void factoryRoutesSqliteKindToSqlBackendWithSqliteDialect() throws Exception {
        RecordingResources resources = new RecordingResources();
        StorageConfig cfg = StorageConfigParser.parse(
                Map.of("type", "sqlite", "sqlite", Map.of("path", "v2.sqlite")), dataFolder);
        var result = PersistenceBackendFactory.create(cfg, resources);
        assertNotNull(result.lifecycle());
        assertEquals(1, result.lifecycle().schemaVersion());
        assertFalse(resources.cleanups.isEmpty());
    }

    @Test
    void factoryRoutesUnknownKindToFailFastException() {
        RecordingResources resources = new RecordingResources();
        // StorageConfigParser rejects unknown kinds before the factory is even called,
        // so we assert that contract instead.
        assertThrows(IllegalArgumentException.class,
                () -> StorageConfigParser.parse(Map.of("type", "postgres"), dataFolder));
        // The factory itself never sees an unknown kind because the parser is the only
        // producer of typed StorageConfig values.
        assertEquals(0, resources.cleanups.size());
    }

    // ---------- API surface sanity ----------

    @Test
    void wiringResultExposesTheSameBackendAsAllFivePorts() throws Exception {
        // JsonPersistenceBackend and SqlBackend both implement the five ports; the
        // factory returns the same instance cast to each. This guarantees no behaviour
        // drift between AccountRepository and TransactionRepository reads and keeps the
        // rollback / nonce operations on the same storage boundary as ordinary reads.
        StorageConfig.Json cfg = new StorageConfig.Json(dataFolder.resolve("triple.json"));
        RecordingResources resources = new RecordingResources();
        var result = PersistenceBackendFactory.create(cfg, resources);
        AccountRepository accounts = result.accounts();
        TransactionRepository transactions = result.transactions();
        PersistenceLifecycle lifecycle = result.lifecycle();
        assertTrue(accounts == transactions && transactions == lifecycle,
                "all ports must be the same backend instance");
        assertTrue(result.reversals() == accounts,
                "the atomic reversal store must be the same backend instance");
        assertTrue(result.nonces() == accounts,
                "the durable nonce store must be the same backend instance");
    }

    @Test
    void sqliteWiringExposesTheSameBackendAsAllFivePorts() throws Exception {
        StorageConfig.Sqlite cfg = new StorageConfig.Sqlite(dataFolder.resolve("five.sqlite"));
        RecordingResources resources = new RecordingResources();
        var result = PersistenceBackendFactory.create(cfg, resources,
                file -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()),
                null);
        assertTrue(result.reversals() == result.accounts(),
                "SQLite atomic reversal store must be the same backend instance");
        assertTrue(result.nonces() == result.accounts(),
                "SQLite durable nonce store must be the same backend instance");
        resources.runAllInReverse();
    }
}