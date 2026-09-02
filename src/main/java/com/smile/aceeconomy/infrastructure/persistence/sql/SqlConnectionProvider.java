package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the JDBC resources used by {@link SqlBackend}.
 *
 * <p>SQLite uses one shared connection. MySQL uses a provider-owned strict pool. A pooled
 * borrow is a proxy: its {@code close()} is routed back to this provider, so a healthy return
 * can be reset and placed idle without invoking a third-party pool's proxy close. If reset
 * fails, the slot is removed before disposal and cannot be handed to another borrower.</p>
 */
public final class SqlConnectionProvider {
    private static final long DEFAULT_TIMEOUT_MILLIS = StrictSqlConnectionPool.defaultTimeoutMillis();
    private static final long DEFAULT_MAX_LIFETIME_MILLIS = StrictSqlConnectionPool.defaultMaxLifetimeMillis();

    private final Connection sqliteConnection;
    private final StrictSqlConnectionPool mysqlPool;
    private final AutoCloseable mysqlOwner;
    private final ReentrantLock sqliteLock = new ReentrantLock();
    private boolean sqliteUnavailable;
    private boolean sqliteClosed;
    private boolean mysqlOwnerClosed;

    /** Creates the single-connection SQLite provider. */
    public SqlConnectionProvider(Connection sqliteConnection) {
        this.sqliteConnection = Objects.requireNonNull(sqliteConnection, "sqliteConnection");
        this.mysqlPool = null;
        this.mysqlOwner = null;
    }

    /**
     * Adapts an existing DataSource with provider-owned pooling. The DataSource is closed by
     * this provider when it implements {@link AutoCloseable}; its own connection close is never
     * used as the healthy return operation.
     */
    public SqlConnectionProvider(DataSource dataSource) {
        this(dataSource,
                dataSourcePoolSize(dataSource),
                dataSourceTimeout(dataSource),
                dataSourceMaxLifetime(dataSource),
                true);
    }

    /** Adapts a DataSource with explicit strict-pool settings. */
    public SqlConnectionProvider(DataSource dataSource, int poolSize, long maxLifetimeMillis) {
        this(dataSource, poolSize, DEFAULT_TIMEOUT_MILLIS, maxLifetimeMillis, true);
    }

    /** Adapts a DataSource with explicit strict-pool and wait settings. */
    public SqlConnectionProvider(
            DataSource dataSource,
            int poolSize,
            long connectionTimeoutMillis,
            long maxLifetimeMillis) {
        this(dataSource, poolSize, connectionTimeoutMillis, maxLifetimeMillis, true);
    }

    SqlConnectionProvider(
            DataSource dataSource,
            int poolSize,
            long connectionTimeoutMillis,
            long maxLifetimeMillis,
            boolean reuseIdleConnections) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.sqliteConnection = null;
        this.mysqlOwner = dataSource instanceof AutoCloseable closeable ? closeable : null;
        StrictSqlConnectionPool.ConnectionDisposer managedDisposer = managedDisposer(dataSource);
        this.mysqlPool = new StrictSqlConnectionPool(
                dataSource::getConnection,
                managedDisposer,
                unmanagedReturnDisposer(dataSource),
                unmanagedAbandonDisposer(dataSource),
                SqlConnectionProvider::abortThenClose,
                poolSize,
                connectionTimeoutMillis,
                maxLifetimeMillis,
                reuseIdleConnections);
    }

    /** Creates the production MySQL provider without an intermediate connection pool. */
    public SqlConnectionProvider(
            String jdbcUrl,
            String username,
            String password,
            int poolSize,
            long maxLifetimeMillis) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        this.sqliteConnection = null;
        this.mysqlOwner = null;
        this.mysqlPool = new StrictSqlConnectionPool(
                () -> DriverManager.getConnection(jdbcUrl, username, password),
                SqlConnectionProvider::abortThenClose,
                SqlConnectionProvider::abortThenClose,
                SqlConnectionProvider::abortThenClose,
                SqlConnectionProvider::abortThenClose,
                poolSize,
                DEFAULT_TIMEOUT_MILLIS,
                maxLifetimeMillis,
                true);
    }

    public Connection borrow() throws SQLException {
        if (sqliteConnection != null) {
            sqliteLock.lock();
            try {
                if (sqliteUnavailable) {
                    throw new SQLException("SQLite connection provider is closed");
                }
                return sqliteConnection;
            } finally {
                sqliteLock.unlock();
            }
        }
        return mysqlPool.borrow();
    }

    /** Returns a healthy borrow after a provider-owned reset. */
    public void returnConnection(Connection connection) throws SQLException {
        if (sqliteConnection != null) {
            return;
        }
        if (mysqlPool != null && connection != null) {
            mysqlPool.returnConnection(connection);
        }
    }

    /** Removes an unsafe borrow without allowing it to pass through the idle path. */
    public void abandonConnection(Connection connection) throws SQLException {
        if (sqliteConnection != null) {
            sqliteLock.lock();
            try {
                if (sqliteClosed) {
                    return;
                }
                sqliteUnavailable = true;
            } finally {
                sqliteLock.unlock();
            }
            try {
                sqliteConnection.close();
                sqliteLock.lock();
                try {
                    sqliteClosed = true;
                } finally {
                    sqliteLock.unlock();
                }
            } catch (Throwable closeFailure) {
                rethrow(closeFailure, "Failed to close unsafe SQLite connection");
            }
            return;
        }
        if (mysqlPool != null && connection != null) {
            mysqlPool.abandonConnection(connection);
        }
    }

    /** Closes all idle and active resources, and retries a failed owner close on a later call. */
    public void close() throws SQLException {
        if (sqliteConnection != null) {
            closeSqlite();
            return;
        }
        Throwable failure = null;
        try {
            mysqlPool.close();
        } catch (Throwable poolFailure) {
            failure = poolFailure;
        }
        if (mysqlOwner != null && !mysqlOwnerClosed) {
            try {
                mysqlOwner.close();
                mysqlOwnerClosed = true;
            } catch (Throwable ownerFailure) {
                failure = appendFailure(failure, ownerCloseFailure(ownerFailure));
            }
        }
        rethrow(failure, "Failed to close SQL connection provider");
    }

    public boolean isSqlite() {
        return sqliteConnection != null;
    }

    private void closeSqlite() throws SQLException {
        sqliteLock.lock();
        try {
            if (sqliteClosed) {
                return;
            }
            sqliteUnavailable = true;
        } finally {
            sqliteLock.unlock();
        }
        try {
            sqliteConnection.close();
            sqliteLock.lock();
            try {
                sqliteClosed = true;
            } finally {
                sqliteLock.unlock();
            }
        } catch (Throwable closeFailure) {
            rethrow(closeFailure, "Failed to close SQLite connection");
        }
    }

    private static StrictSqlConnectionPool.ConnectionDisposer managedDisposer(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            // The provider removes the slot before this call. Hikari's public eviction API is
            // therefore used before any proxy close and no close-then-evict sequence exists.
            return connection -> {
                try {
                    hikari.evictConnection(connection);
                } catch (Throwable evictionFailure) {
                    try {
                        abortThenClose(connection);
                    } catch (Throwable fallbackFailure) {
                        evictionFailure.addSuppressed(fallbackFailure);
                    }
                    rethrow(evictionFailure, "Failed to evict managed SQL connection");
                }
            };
        }
        return SqlConnectionProvider::abortThenClose;
    }

    private static StrictSqlConnectionPool.ConnectionDisposer unmanagedReturnDisposer(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return connection -> {
                try {
                    hikari.evictConnection(connection);
                } catch (Throwable evictionFailure) {
                    SQLException evictionDiagnostic = new SQLException("Failed to evict SQL connection", evictionFailure);
                    try {
                        connection.close();
                    } catch (Throwable closeFailure) {
                        closeFailure.addSuppressed(evictionDiagnostic);
                        try {
                            connection.abort(Runnable::run);
                        } catch (Throwable abortFailure) {
                            closeFailure.addSuppressed(abortFailure);
                        }
                        throw closeFailure;
                    }
                    throw evictionDiagnostic;
                }
            };
        }
        return SqlConnectionProvider::closeThenAbort;
    }

    private static StrictSqlConnectionPool.ConnectionDisposer unmanagedAbandonDisposer(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return connection -> {
                try {
                    hikari.evictConnection(connection);
                    return;
                } catch (Throwable evictionFailure) {
                    try {
                        connection.abort(Runnable::run);
                    } catch (Throwable abortFailure) {
                        SQLException wrapped = new SQLException(
                                "Failed to evict unsafe SQL connection", evictionFailure);
                        wrapped.addSuppressed(abortFailure);
                        throw wrapped;
                    }
                    throw new SQLException("Failed to evict unsafe SQL connection", evictionFailure);
                }
            };
        }
        return SqlConnectionProvider::closeThenAbort;
    }

    private static void abortThenClose(Connection connection) throws Throwable {
        Throwable failure = null;
        try {
            connection.abort(Runnable::run);
        } catch (Throwable abortFailure) {
            failure = abortFailure;
        }
        try {
            connection.close();
        } catch (Throwable closeFailure) {
            failure = appendFailure(failure, closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeThenAbort(Connection connection) throws Throwable {
        try {
            connection.close();
            return;
        } catch (Throwable closeFailure) {
            try {
                connection.abort(Runnable::run);
            } catch (Throwable abortFailure) {
                closeFailure.addSuppressed(abortFailure);
            }
            throw closeFailure;
        }
    }

    private static int dataSourcePoolSize(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && hikari.getMaximumPoolSize() > 0) {
            return hikari.getMaximumPoolSize();
        }
        return StrictSqlConnectionPool.defaultPoolSize();
    }

    private static long dataSourceTimeout(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && hikari.getConnectionTimeout() > 0) {
            return hikari.getConnectionTimeout();
        }
        return DEFAULT_TIMEOUT_MILLIS;
    }

    private static long dataSourceMaxLifetime(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && hikari.getMaxLifetime() > 0) {
            return hikari.getMaxLifetime();
        }
        return DEFAULT_MAX_LIFETIME_MILLIS;
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static Throwable ownerCloseFailure(Throwable failure) {
        if (failure instanceof SQLException || failure instanceof Error) {
            return failure;
        }
        return new SQLException("Failed to close SQL data source", failure);
    }

    private static void rethrow(Throwable failure, String message) throws SQLException {
        if (failure == null) {
            return;
        }
        if (failure instanceof SQLException sql) {
            throw sql;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new SQLException(message, failure);
    }
}
