package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.MySqlDialect;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Builds the v2.0.0 persistence backend from a parsed {@link StorageConfig}.
 *
 * <p>The factory is the single seam where storage type → backend class mapping lives.
 * It is also the single place that owns JDBC {@link Connection} and {@link DataSource}
 * lifecycle: every acquired resource is registered with the {@link ResourceRegistry} on
 * success or released inline on failure, so a partial-startup exception never leaves an
 * open pool / connection / thread behind.</p>
 *
 * <h2>Why a {@link ResourceRegistry} instead of a direct {@code ResourceOwner}?</h2>
 * <p>{@code ResourceOwner} lives in {@code bootstrap} and owns the lifecycle ordering;
 * keeping the factory package-pure (no bootstrap dependency) makes it unit-testable with
 * a plain recorder. {@code CompositionRoot} adapts its {@code ResourceOwner} to the
 * registry interface at the call site.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Each backend instance is shared across the v2 io executor and the Folia-safe
 * scheduler. {@link JsonPersistenceBackend} guards its own state with a
 * {@code ReentrantLock}; {@link SqlBackend} serializes all public access by the
 * synchronized keyword (see its class javadoc). The factory adds no shared state of
 * its own and is therefore safe to invoke concurrently.</p>
 */
public final class PersistenceBackendFactory {

    /**
     * Adapter for {@code ResourceOwner.register(Runnable)} so the factory can be tested
     * without dragging in the bootstrap package.
     */
    @FunctionalInterface
    public interface ResourceRegistry {
        void register(Runnable cleanup);
    }

    /** Production seam: opens a SQLite JDBC connection. Test seam may inject fakes. */
    @FunctionalInterface
    public interface SqliteConnector {
        Connection open(Path databaseFile) throws SQLException;
    }

    /** Production seam: builds the MySQL {@link DataSource} (HikariCP). Tests inject fakes. */
    @FunctionalInterface
    public interface MysqlDataSourceFactory {
        DataSource build(String jdbcUrl, String username, String password,
                         int poolSize, long maxLifetimeMs) throws SQLException;
    }

    /**
     * Bundle of the three ports exposed by a v2.0.0 backend. All three are the SAME
     * instance — the wiring contract is that a single backend object simultaneously
     * satisfies {@link AccountRepository}, {@link TransactionRepository} and
     * {@link PersistenceLifecycle}, so reads via the repository ports always observe
     * the same persisted state as writes via the lifecycle port.
     */
    public record WiringResult(
            AccountRepository accounts,
            TransactionRepository transactions,
            PersistenceLifecycle lifecycle) {
    }

    private PersistenceBackendFactory() {
    }

    /**
     * Production entry point: SQLite via {@link DriverManager}, MySQL via HikariCP.
     * Same wiring rules as the test seam below; same cleanup guarantees.
     */
    public static WiringResult create(StorageConfig config, ResourceRegistry resources)
            throws Exception {
        return create(config, resources,
                file -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()),
                PersistenceBackendFactory::defaultMysqlDataSource);
    }

    /**
     * Injection point: callers pass custom connection providers so cleanup order can be
     * verified deterministically without requiring a live MySQL server.
     *
     * <p>Providers that are not relevant to the selected {@link StorageConfig kind} may be
     * {@code null} — only the provider actually used is dereferenced. This lets a caller
     * focus on a single kind without inventing a no-op for the other.</p>
     */
    public static WiringResult create(
            StorageConfig config,
            ResourceRegistry resources,
            SqliteConnector sqliteConnector,
            MysqlDataSourceFactory mysqlDataSourceFactory) throws Exception {

        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resources, "resources");

        return switch (config) {
            case StorageConfig.Json j -> createJson(j, resources);
            case StorageConfig.Sqlite s -> {
                Objects.requireNonNull(sqliteConnector, "sqliteConnector");
                yield createSqlite(s, resources, sqliteConnector);
            }
            case StorageConfig.Mysql m -> {
                Objects.requireNonNull(mysqlDataSourceFactory, "mysqlDataSourceFactory");
                yield createMysql(m, resources, mysqlDataSourceFactory);
            }
        };
    }

    // ---------- per-kind builders ----------

    private static WiringResult createJson(StorageConfig.Json json, ResourceRegistry resources) {
        // JsonPersistenceBackend.initialize throws PersistenceException, which is a
        // RuntimeException, so no extra catch plumbing is needed here.
        JsonPersistenceBackend backend = new JsonPersistenceBackend(json.dataFile());
        backend.initialize();
        resources.register(backend::close);
        return new WiringResult(backend, backend, backend);
    }

    private static WiringResult createSqlite(
            StorageConfig.Sqlite config,
            ResourceRegistry resources,
            SqliteConnector sqliteConnector) throws Exception {
        // Use a single-slot array so the lambda below can read the captured Connection
        // while remaining effectively-final. The slot is written exactly once before the
        // lambda is registered; reads are guarded by the ResourceOwner.close() contract
        // (cleanup callbacks run after the start() phase has fully returned).
        final Connection[] connRef = new Connection[1];
        try {
            Connection conn = sqliteConnector.open(config.databaseFile());
            connRef[0] = conn;
            SqlBackend backend = new SqlBackend(conn, new SqliteDialect());
            backend.initialize();
            resources.register(() -> {
                // Order: backend first (closes its Connection), then a defensive
                // closeQuietly on the captured reference (idempotent).
                backend.close();
                closeQuietly(connRef[0]);
            });
            return new WiringResult(backend, backend, backend);
        } catch (RuntimeException | SQLException e) {
            // open() or initialize() failed: release whatever we managed to acquire so no
            // JDBC connection leaks.
            closeQuietly(connRef[0]);
            throw e;
        }
    }

    private static WiringResult createMysql(
            StorageConfig.Mysql config,
            ResourceRegistry resources,
            MysqlDataSourceFactory mysqlDataSourceFactory) throws Exception {
        // Same capture pattern as the SQLite branch: DataSource and Connection are both
        // written into single-slot arrays so the cleanup lambda can reach them.
        final Connection[] connRef = new Connection[1];
        final DataSource[] dsRef = new DataSource[1];
        try {
            DataSource ds = mysqlDataSourceFactory.build(
                    config.jdbcUrl(), config.username(), config.password(),
                    config.poolSize(), config.maxLifetimeMs());
            dsRef[0] = ds;
            Connection conn = ds.getConnection();
            connRef[0] = conn;
            SqlBackend backend = new SqlBackend(conn, new MySqlDialect());
            backend.initialize();
            resources.register(() -> {
                backend.close();          // closes the borrowed Connection
                closeQuietly(connRef[0]); // defensive (idempotent if already closed)
                closeDataSource(dsRef[0]); // closes the Hikari pool / DataSource
            });
            return new WiringResult(backend, backend, backend);
        } catch (RuntimeException | SQLException e) {
            // Failed at any step: release the connection (if borrowed) and the DataSource
            // (if built). Both are best-effort and idempotent.
            closeQuietly(connRef[0]);
            closeDataSource(dsRef[0]);
            throw e;
        }
    }

    // ---------- production providers ----------

    private static DataSource defaultMysqlDataSource(
            String jdbcUrl, String username, String password,
            int poolSize, long maxLifetimeMs) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(poolSize);
        hc.setMaxLifetime(maxLifetimeMs);
        hc.setPoolName("aceeconomy-v2-mysql");
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new HikariDataSource(hc);
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignore) {
            // best-effort; resource release is always best-effort on shutdown
        }
    }

    /**
     * Close a {@link DataSource} without throwing. {@code DataSource} on this JDK does not
     * extend {@link AutoCloseable} nor declare a {@code close()} method (verified via
     * {@code javap}), so we close via the {@link AutoCloseable} interface when the
     * implementation supports it. HikariCP, the JDK reference pool and most production
     * pools all implement {@link AutoCloseable}, so production close paths are exercised;
     * a {@link DataSource} that does not implement {@link AutoCloseable} is left as-is.
     */
    private static void closeDataSource(DataSource ds) {
        if (ds instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }
}