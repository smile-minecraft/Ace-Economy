package com.smile.aceeconomy.infrastructure.persistence.sql;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A borrowed JDBC connection returned from {@link SqlConnectionProvider#borrow()}.
 *
 * <p>This wrapper exists for one reason: a single transaction cleanup must be able to
 * tell the provider, before release, whether the borrowed wrapper is still safe to lend
 * again. Calling code paths flag the borrow via
 * {@link #markUnsafe()} when any cleanup step (rollback, restore-auto-commit) failed;
 * the {@link #close()} method then routes the wrapper to
 * {@link SqlConnectionProvider#abandonConnection} instead of the safe release path.
 * Without this split, cleanup helpers would either (a) close the connection directly
 * and double-close it on release, or (b) silently return an unsafe connection to the
 * pool.</p>
 *
 * <p>The unsafe flag is one-way: once set it cannot be cleared, so a borrow that
 * encountered any cleanup failure is abandoned exactly once.</p>
 */
public final class BorrowedConnection implements AutoCloseable {

    private final Connection connection;
    private final SqlConnectionProvider provider;
    private volatile boolean unsafe;
    private boolean closed;

    public BorrowedConnection(Connection connection, SqlConnectionProvider provider) {
        this.connection = connection;
        this.provider = provider;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Flag this borrow as unsafe to return to the pool. Idempotent and one-way: only the
     * first call matters, subsequent calls are no-ops. The {@link #close()} method must
     * be the sole place that touches the underlying {@link Connection}.
     */
    public void markUnsafe() {
        this.unsafe = true;
    }

    public boolean isUnsafe() {
        return unsafe;
    }

    /**
     * Release the borrow to the provider. Safe borrows are released back to the pool;
     * borrows that were {@link #markUnsafe() marked unsafe} are discarded by the
     * provider so the next caller gets a fresh connection. {@link #close()} is
     * idempotent so accidental double-close from upstream cleanup paths cannot corrupt
     * the provider state.
     */
    @Override
    public void close() throws SQLException {
        close(null);
    }

    /**
     * Release this borrow once, attaching a release failure to an operation failure when
     * one already exists. The operation failure's cause is the JDBC primary in the
     * persistence boundary, so cleanup failures are attached there rather than hidden on
     * a wrapper exception. Both checked and unchecked cleanup failures are preserved:
     * if there is no primary the cleanup failure is propagated with its original type;
     * if there is a primary the cleanup failure is added to the suppressed chain and
     * the primary remains the thrown exception.
     */
    synchronized void close(Throwable primaryFailure) throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (unsafe) {
                provider.abandonConnection(connection);
            } else {
                provider.returnConnection(connection);
            }
        } catch (Throwable releaseFailure) {
            if (primaryFailure == null) {
                if (releaseFailure instanceof SQLException) {
                    throw (SQLException) releaseFailure;
                }
                if (releaseFailure instanceof RuntimeException) {
                    throw (RuntimeException) releaseFailure;
                }
                if (releaseFailure instanceof Error) {
                    throw (Error) releaseFailure;
                }
                throw new SQLException("Failed to release SQL connection", releaseFailure);
            }
            Throwable suppressionTarget = primaryFailure;
            if (primaryFailure instanceof com.smile.aceeconomy.ports.persistence.PersistenceException
                    && primaryFailure.getCause() != null) {
                suppressionTarget = primaryFailure.getCause();
            }
            suppressionTarget.addSuppressed(releaseFailure);
        }
    }
}
