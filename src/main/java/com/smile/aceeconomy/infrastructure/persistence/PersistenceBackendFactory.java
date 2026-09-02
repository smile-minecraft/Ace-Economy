package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.MySqlDialect;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import com.smile.aceeconomy.ports.persistence.NonceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

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

    /** Test/integration seam: supplies a MySQL DataSource to the strict provider. */
    @FunctionalInterface
    public interface MysqlDataSourceFactory {
        DataSource build(String jdbcUrl, String username, String password,
                         int poolSize, long maxLifetimeMs) throws SQLException;
    }

    /**
     * Bundle of the ports exposed by a v2.0.0 backend. All six are the SAME
     * instance — the wiring contract is that a single backend object simultaneously
     * satisfies {@link AccountRepository}, {@link TransactionRepository},
     * {@link PersistenceLifecycle}, {@link AtomicReversalStore},
     * {@link AtomicRedemptionStore} and {@link NonceStore},
     * so reads via the repository ports always observe the same persisted state as writes
     * via the lifecycle port, and rollback / redemption / nonce operations share one storage
     * transaction boundary with ordinary reads and writes.
     */
    public record WiringResult(
            AccountRepository accounts,
            TransactionRepository transactions,
            PersistenceLifecycle lifecycle,
            AtomicReversalStore reversals,
            AtomicRedemptionStore redemptions,
            NonceStore nonces) {

        public WiringResult {
            if (accounts != transactions || transactions != lifecycle
                    || lifecycle != reversals || reversals != redemptions
                    || redemptions != nonces) {
                throw new IllegalArgumentException(
                        "wiring contract requires one shared backend instance for all ports");
            }
        }
    }

    private PersistenceBackendFactory() {
    }

    /**
     * Production entry point: SQLite via {@link DriverManager}, MySQL via the provider-owned
     * strict pool. Same wiring rules as the test seam below; same cleanup guarantees.
     */
    public static WiringResult create(StorageConfig config, ResourceRegistry resources)
            throws Exception {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resources, "resources");
        return switch (config) {
            case StorageConfig.Json j -> createJson(j, resources);
            case StorageConfig.Sqlite s -> createSqlite(
                    s,
                    resources,
                    file -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()));
            case StorageConfig.Mysql m -> createMysql(m, resources);
        };
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
        return new WiringResult(backend, backend, backend, backend, backend, backend);
    }

    private static WiringResult createSqlite(
            StorageConfig.Sqlite config,
            ResourceRegistry resources,
            SqliteConnector sqliteConnector) throws Exception {
        // Single ownership: backend.close() is the registered shutdown owner and it
        // releases the provider (and therefore the SQLite {@link Connection}). On init
        // failure the backend was never registered, so we close the provider directly
        // here to avoid leaving an orphan JDBC connection.
        final SqlConnectionProvider[] providerRef = new SqlConnectionProvider[1];
        try {
            Connection conn = sqliteConnector.open(config.databaseFile());
            SqlConnectionProvider provider = new SqlConnectionProvider(conn);
            providerRef[0] = provider;
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();
            resources.register(backend::close);
            return new WiringResult(backend, backend, backend, backend, backend, backend);
        } catch (SQLException | RuntimeException | Error e) {
            SqlConnectionProvider provider = providerRef[0];
            if (provider != null) {
                closeProviderAfterFailure(provider, e);
            }
            throw e;
        }
    }

    private static WiringResult createMysql(
            StorageConfig.Mysql config,
            ResourceRegistry resources,
            MysqlDataSourceFactory mysqlDataSourceFactory) throws Exception {
        // Single ownership: backend.close() releases the {@link SqlConnectionProvider},
        // which owns the injected {@link DataSource} and its borrowed connections. There is
        // no defensive close on the raw DataSource reference outside that shutdown owner.
        final SqlConnectionProvider[] providerRef = new SqlConnectionProvider[1];
        try {
            DataSource ds = mysqlDataSourceFactory.build(
                    config.jdbcUrl(), config.username(), config.password(),
                    config.poolSize(), config.maxLifetimeMs());
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    ds, config.poolSize(), config.maxLifetimeMs());
            providerRef[0] = provider;
            SqlBackend backend = new SqlBackend(provider, new MySqlDialect());
            backend.initialize();
            resources.register(backend::close);
            return new WiringResult(backend, backend, backend, backend, backend, backend);
        } catch (SQLException | RuntimeException | Error e) {
            SqlConnectionProvider provider = providerRef[0];
            if (provider != null) {
                closeProviderAfterFailure(provider, e);
            }
            throw e;
        }
    }

    private static WiringResult createMysql(
            StorageConfig.Mysql config,
            ResourceRegistry resources) throws Exception {
        final SqlConnectionProvider[] providerRef = new SqlConnectionProvider[1];
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(
                    config.jdbcUrl(),
                    config.username(),
                    config.password(),
                    config.poolSize(),
                    config.maxLifetimeMs());
            providerRef[0] = provider;
            SqlBackend backend = new SqlBackend(provider, new MySqlDialect());
            backend.initialize();
            resources.register(backend::close);
            return new WiringResult(backend, backend, backend, backend, backend, backend);
        } catch (RuntimeException | Error e) {
            SqlConnectionProvider provider = providerRef[0];
            if (provider != null) {
                closeProviderAfterFailure(provider, e);
            }
            throw e;
        }
    }

    private static void closeProviderAfterFailure(SqlConnectionProvider provider, Throwable primary) {
        try {
            provider.close();
        } catch (Throwable closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

}
