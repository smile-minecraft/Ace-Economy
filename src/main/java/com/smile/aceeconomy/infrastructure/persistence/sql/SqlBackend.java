package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.SnapshotPreflight;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import com.smile.aceeconomy.ports.persistence.AtomicTransferStore;
import com.smile.aceeconomy.ports.persistence.NonceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JDBC-backed v2 persistence backend. Implements the account, transaction and lifecycle ports for
 * both SQLite and MySQL through a single {@link SqlDialect}.
 *
 * <p>Transaction boundary and DDL recovery:</p>
 * <ul>
 *   <li>SQLite: DDL is transactional. Schema creation runs inside one transaction; on failure it
 *       rolls back and rethrows, leaving no partial schema. {@code CREATE TABLE IF NOT EXISTS}
 *       plus {@code INSERT IGNORE} make a restart idempotent.</li>
 *   <li>MySQL: DDL implicitly commits per statement (the surrounding
 *       {@code setAutoCommit(false)} does not make MySQL DDL rollback). No compensating
 *       {@code DROP} is performed — that would destroy existing domain data after a partial
 *       failure. Instead every DDL uses {@code CREATE TABLE IF NOT EXISTS} and the version
 *       row uses {@code INSERT IGNORE}, so a later {@link #initialize()} can resume and
 *       complete the missing tables without touching already-existing rows. {@link #needsRecreation()}
 *       reports a partial init (tables exist but no version row) so an operator can decide to
 *       {@link #truncateAndRecreate()} when a clean slate is intentionally requested. This
 *       MySQL per-statement commit behavior is documented from the MySQL manual and the
 *       dialect comment, not proven by a live MySQL integration test in this repository
 *       (tests use SQLite plus idempotence contract checks).</li>
 *   <li>{@link #appendBatch} writes every record inside one transaction: either all are committed
 *       or none are (all-or-none).</li>
 *   <li>{@link #markReverted} is the rollback marker write; it is idempotent and isolated.</li>
 *   <li>{@link #restore} parses and validates the snapshot fully before any live row is touched,
 *       so a corrupt backup cannot destroy existing data.</li>
 * </ul>
 *
 * <p>Thread safety (durable contract): a single {@link ReentrantReadWriteLock} gates every
 * public JDBC method on this backend. The lock is split along the same lines the JDBC layer
 * demands:</p>
 * <ul>
 *   <li><b>Lifecycle / write methods</b> ({@link #initialize}, {@link #close},
 *       {@link #schemaVersion}, {@link #needsRecreation}, {@link #truncateAndRecreate},
 *       {@link #backup}, {@link #restore}, {@link #runExclusive}) always take the
 *       <em>write</em> lock. They are mutually exclusive against each other and against
 *       ordinary operations. {@code runExclusive} is reentrant by the same thread so the
 *       {@code BackupRestoreService} safety backup / restore chain inside it keeps working.</li>
 *   <li><b>SQLite ordinary methods</b> ({@link #exists}, {@link #load}, {@link #listAll},
 *       {@link #loadAll}, {@link #save}, {@link #create}, {@link #append}, {@link #appendBatch},
 *       {@link #markReverted}, {@link #isReverted}, {@link #loadByAccount},
 *       {@link #applyReversal}, {@link #consume}, {@link #isConsumed},
 *       {@link #redeemPrepared}, {@link #redeem}) take the <em>write</em> lock: the backend
 *       owns a single shared {@link Connection} that cannot be safely used by two
 *       operations at once, so SQLite callers continue to see one-at-a-time semantics.</li>
 *   <li><b>MySQL ordinary methods</b> take the <em>read</em> lock. The MySQL pool is allowed
 *       to hand out distinct connections to concurrent callers; the previous
 *       {@code synchronized}-on-instance monitor that serialized every borrow (and so
 *       capped pool concurrency at one) is gone.</li>
 * </ul>
 *
 * <p>This matches {@link com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend},
 * which guards the in-memory model with a {@code ReentrantLock}, and keeps domain semantics
 * identical across backends (no hidden concurrent-write surprises when an operator switches
 * {@code storage.type}).</p>
 *
 * <p>Initialization guard: every public method that needs a usable schema throws
 * {@link PersistenceException} when {@code initialized == false}. Lifecycle entry points
 * ({@link #initialize}, {@link #truncateAndRecreate}) and diagnostic reads
 * ({@link #schemaVersion}, {@link #needsRecreation}) bypass the guard so an operator can
 * inspect state and recover. The lock provides happens-before visibility for the volatile
 * {@code initialized} flag, and the guard sits inside the lock acquisition so the
 * check-and-borrow pair is atomic against concurrent {@code close()} / {@code initialize()}
 * failures.</p>
 *
 * <p>Connection ownership: every public method that touches JDBC borrows exactly one
 * connection for the duration of the call via {@link SqlConnectionProvider#borrow()} wrapped
 * in a {@link BorrowedConnection}. Each atomic operation shares one {@link Connection} so
 * all statements run in the same JDBC transaction; nested borrows are impossible because
 * helpers like {@link #loadWithConnection} accept the borrowed connection as a parameter.
 * Safe borrows return the wrapper to the provider; unsafe borrows (where any cleanup step
 * failed) are routed to {@link SqlConnectionProvider#abandonConnection} so the next caller
 * gets a fresh connection instead of a wrapper in an unknown state.</p>
 *
 * <p>Only {@code java.sql} is used here; no vendor driver types leak into the port boundary.</p>
 */
public final class SqlBackend
        implements AccountRepository, TransactionRepository, PersistenceLifecycle,
        AtomicReversalStore, AtomicRedemptionStore, AtomicTransferStore, NonceStore {

    private final SqlConnectionProvider provider;
    private final SqlDialect dialect;
    private volatile boolean initialized = false;

    /**
     * Shared access gate for every public JDBC method. See the class Javadoc for the
     * read/write split. {@link ReentrantReadWriteLock} is reentrant for the same thread,
     * which keeps {@link #runExclusive}'s safety-backup / restore chain working when it
     * calls {@link #backup} / {@link #restore} on the same backend instance.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SqlBackend(SqlConnectionProvider provider, SqlDialect dialect) {
        this.provider = provider;
        this.dialect = dialect;
    }

    // Visible for testing: allow mock-based unit tests to set the initialized flag
    // without running DDL against a mock Connection. Production code always sets
    // this flag via initialize() / truncateAndRecreate() / close() under the write lock.
    void setInitializedForTest(boolean value) {
        this.initialized = value;
    }

    /**
     * Pick the access lock for an ordinary repository / nonce / atomic method:
     * <ul>
     *   <li>SQLite — the backend owns a single shared {@link Connection} that cannot be
     *       safely used by two ordinary operations at once, so the call takes the
     *       <em>write</em> lock. This preserves the previous serialization semantics and
     *       keeps every atomic transaction single-connection / single-transaction.</li>
     *   <li>MySQL — the pool is allowed to hand out distinct connections to concurrent
     *       callers, so the call takes the <em>read</em> lock. Multiple ordinary methods
     *       can hold borrowed connections simultaneously and still complete.</li>
     * </ul>
     * The choice is fixed at construction time by {@link SqlConnectionProvider#isSqlite()},
     * so the lock type does not change at runtime.
     */
    private Lock accessLock() {
        return provider.isSqlite() ? lock.writeLock() : lock.readLock();
    }

    /**
     * Throw a fail-closed {@link PersistenceException} when the schema has not been (or is
     * no longer) initialized. Lifecycle entry points ({@link #initialize()},
     * {@link #truncateAndRecreate()}) and diagnostic reads ({@link #schemaVersion()},
     * {@link #needsRecreation()}) bypass this guard. Every other public method calls this
     * helper inside the access lock so a concurrent {@code close()} / failed
     * {@code initialize()} is observed before any JDBC borrow happens.
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new PersistenceException(
                    "Persistence backend not initialized: call initialize() or "
                            + "truncateAndRecreate() first");
        }
    }

    private BorrowedConnection borrow() throws SQLException {
        return new BorrowedConnection(provider.borrow(), provider);
    }

    @FunctionalInterface
    private interface BorrowedOperation<R> {
        R run(BorrowedConnection borrowed, Connection conn) throws SQLException;
    }

    @FunctionalInterface
    private interface BorrowedIoOperation<R> {
        R run(BorrowedConnection borrowed, Connection conn) throws SQLException, IOException;
    }

    /**
     * Run one JDBC operation with one owned borrow. The explicit release lets a cleanup
     * failure be attached to the operation's primary exception before it is wrapped at the
     * persistence boundary; it also keeps the underlying connection's close owner in one
     * place.
     */
    private <R> R withBorrowed(BorrowedOperation<R> operation)
            throws SQLException {
        BorrowedConnection borrowed = borrow();
        Throwable primaryFailure = null;
        try {
            return operation.run(borrowed, borrowed.connection());
        } catch (SQLException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            borrowed.close(primaryFailure);
        }
    }

    private <R> R withBorrowedIo(BorrowedIoOperation<R> operation)
            throws SQLException, IOException {
        BorrowedConnection borrowed = borrow();
        Throwable primaryFailure = null;
        try {
            return operation.run(borrowed, borrowed.connection());
        } catch (SQLException | IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            borrowed.close(primaryFailure);
        }
    }

    /**
     * Roll back the open transaction and, on success, restore auto-commit so the borrowed
     * connection can be released back to the pool. Any failure here is recorded on the
     * original exception's suppressed chain and marks the borrow unsafe, so release routes
     * the wrapper to {@link SqlConnectionProvider#abandonConnection} instead of returning
     * an unsafe connection to the pool. The helper never closes the connection directly.
     */
    private void rollbackOrClose(BorrowedConnection borrowed, Connection conn, SQLException original) {
        rollbackForFailure(borrowed, conn, original, true);
    }

    /**
     * Generic rollback/restore for any failure type. When the transaction was already
     * entered, a rollback is attempted and, on success, auto-commit is restored.
     * Any cleanup failure is attached to the primary and marks the borrow unsafe.
     * When the transaction was never entered (begin failed), the borrow is fail-closed
     * regardless of whether cleanup appears to succeed, because the connection is in an
     * unknown auto-commit state.
     */
    private void rollbackForFailure(BorrowedConnection borrowed, Connection conn,
                                    Throwable primary, boolean enteredTransaction) {
        if (!enteredTransaction) {
            borrowed.markUnsafe();
            try {
                conn.rollback();
            } catch (Throwable rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
                borrowed.markUnsafe();
                return;
            }
            try {
                conn.setAutoCommit(true);
            } catch (Throwable autoCommitFailure) {
                primary.addSuppressed(autoCommitFailure);
                borrowed.markUnsafe();
            }
            return;
        }
        try {
            conn.rollback();
        } catch (Throwable rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
            borrowed.markUnsafe();
            return;
        }
        // The transaction is rolled back. Restoring auto-commit makes the wrapper safe
        // to lend again; if this step fails the connection is in an unknown auto-commit
        // state and must not return to the pool.
        try {
            conn.setAutoCommit(true);
        } catch (Throwable autoCommitFailure) {
            primary.addSuppressed(autoCommitFailure);
            borrowed.markUnsafe();
        }
    }

    /**
     * Restore auto-commit after a successful commit. On failure the borrow is marked unsafe
     * so the provider discards the wrapper, and the failure is rethrown so the caller can
     * surface it. Like {@link #rollbackOrClose}, this helper never touches
     * {@link Connection#close()} — the {@link BorrowedConnection} owns close timing.
     */
    private void restoreAutoCommitOrClose(BorrowedConnection borrowed, Connection conn)
            throws SQLException {
        try {
            conn.setAutoCommit(true);
        } catch (Throwable autoCommitFailure) {
            borrowed.markUnsafe();
            if (autoCommitFailure instanceof SQLException sql) {
                throw sql;
            }
            if (autoCommitFailure instanceof RuntimeException re) {
                throw re;
            }
            if (autoCommitFailure instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(autoCommitFailure);
        }
    }

    /**
     * Restore auto-commit after a successful {@link java.sql.Connection#commit commit}, then
     * surface any restore failure as a {@link PersistenceException} that makes it
     * unambiguous the data IS committed. Used by every transaction path so the operator
     * guidance is identical regardless of which API failed: a "committed successfully"
     * message means the storage state has moved, even though the borrowed connection had
     * to be discarded to keep the next caller safe.
     */
    private void restoreAutoCommitAfterCommit(BorrowedConnection borrowed, Connection conn,
                                              String operation) throws PersistenceException {
        try {
            restoreAutoCommitOrClose(borrowed, conn);
        } catch (SQLException | RuntimeException | Error autoCommitFailure) {
            throw new PersistenceException(
                    operation + " committed successfully, but restoring auto-commit on the SQL "
                            + "connection failed; the connection has been closed. The data is "
                            + "committed — do not treat this as a rollback.",
                    autoCommitFailure, true);
        }
    }

    // ---------------- lifecycle ----------------

    @Override
    public void initialize() throws PersistenceException {
        lock.writeLock().lock();
        try {
            try {
                withBorrowed((borrowed, conn) -> {
                    // Marker inspection must happen before any DDL: fail-closed on
                    // incompatible version (including 0) or corrupted multiple rows,
                    // before MySQL per-statement commits could make partial DDL permanent.
                    List<Integer> existing = readAllSchemaVersions(conn);
                    if (!existing.isEmpty()) {
                        if (existing.size() != 1 || !SchemaVersion.isCompatible(existing.get(0))) {
                            String detail = existing.size() > 1
                                    ? "multiple schema markers " + existing
                                    : "incompatible schema version " + existing.get(0);
                            throw new PersistenceException(
                                    "Incompatible or corrupted schema marker (" + detail + "); expected version "
                                            + SchemaVersion.CURRENT + ". Call truncateAndRecreate().");
                        }
                    }
                    createSchema(borrowed, conn);
                    return null;
                });
                initialized = true;
            } catch (SQLException e) {
                initialized = false;
                throw new PersistenceException("Failed to initialize v2 SQL schema", e);
            } catch (PersistenceException e) {
                initialized = false;
                throw e;
            } catch (RuntimeException | Error e) {
                initialized = false;
                throw e;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            try {
                provider.close();
            } catch (SQLException closeFailure) {
                throw new PersistenceException(
                        "Failed to close SQL persistence resources", closeFailure);
            } finally {
                initialized = false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public int schemaVersion() throws PersistenceException {
        lock.writeLock().lock();
        try {
            try {
                return withBorrowed((borrowed, conn) -> {
                    List<Integer> versions = readAllSchemaVersions(conn);
                    if (versions.isEmpty()) {
                        return 0;
                    }
                    if (versions.size() > 1) {
                        throw new PersistenceException(
                                "Corrupted schema marker: multiple rows " + versions + " in " + V2Schema.schemaTable());
                    }
                    return versions.get(0);
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to read schema version", e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean needsRecreation() throws PersistenceException {
        lock.writeLock().lock();
        try {
            try {
                return withBorrowed((borrowed, conn) -> {
                    if (!tableExists(V2Schema.schemaTable(), conn)) {
                        // Fresh only if no v2 table exists at all; otherwise a partial init left tables behind.
                        return tableExists(V2Schema.accountsTable(), conn)
                                || tableExists(V2Schema.balancesTable(), conn)
                                || tableExists(V2Schema.transactionsTable(), conn)
                                || tableExists(V2Schema.noncesTable(), conn);
                    }
                    List<Integer> versions = readAllSchemaVersions(conn);
                    if (versions.isEmpty()) {
                        // Schema table exists but contains no row: partial init or truncated marker.
                        return true;
                    }
                    if (versions.size() > 1) {
                        // Multiple rows mean corruption; must not silently use first row.
                        return true;
                    }
                    return !SchemaVersion.isCompatible(versions.get(0));
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to inspect schema state", e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void truncateAndRecreate() throws PersistenceException {
        lock.writeLock().lock();
        try {
            try {
                withBorrowed((borrowed, conn) -> {
                    dropSchema(borrowed, conn);
                    createSchema(borrowed, conn);
                    return null;
                });
                initialized = true;
            } catch (SQLException e) {
                initialized = false;
                throw new PersistenceException("Failed to recreate v2 SQL schema", e);
            } catch (PersistenceException e) {
                initialized = false;
                throw e;
            } catch (RuntimeException | Error e) {
                initialized = false;
                throw e;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void backup(OutputStream out) throws PersistenceException, IOException {
        lock.writeLock().lock();
        try {
            ensureInitialized();
            try {
                withBorrowedIo((borrowed, conn) -> {
                    JsonModel model = loadAllIntoModel(conn);
                    out.write(model.toJson().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return null;
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to backup", e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void restore(InputStream in) throws PersistenceException, IOException {
        lock.writeLock().lock();
        try {
            ensureInitialized();
            byte[] bytes = in.readAllBytes();
            JsonModel candidate = JsonModel.fromJson(new String(bytes, StandardCharsets.UTF_8));
            if (candidate.schemaVersion != JsonModel.SCHEMA_VERSION) {
                throw new PersistenceException(
                        "Backup schema version " + candidate.schemaVersion
                                + " incompatible with expected " + JsonModel.SCHEMA_VERSION);
            }
            // Materialize every record through the same domain conversions the backends use at load
            // time, before any live row is deleted. This prevents a corrupt snapshot from destroying
            // existing data and then failing mid-insert.
            SnapshotPreflight.validateRecords(candidate);
            try {
                withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        try (Statement st = conn.createStatement()) {
                            st.executeUpdate("DELETE FROM " + V2Schema.transactionsTable());
                            st.executeUpdate("DELETE FROM " + V2Schema.balancesTable());
                            st.executeUpdate("DELETE FROM " + V2Schema.accountsTable());
                            st.executeUpdate("DELETE FROM " + V2Schema.noncesTable());
                        }
                        for (JsonModel.JsonAccount a : candidate.accounts.values()) {
                            insertAccount(a, conn);
                        }
                        for (JsonModel.JsonTransaction t : candidate.transactions) {
                            insertTransaction(t, conn);
                        }
                        insertNonces(candidate, conn);
                        conn.commit();
                    } catch (Throwable e) {
                        // A failed restore may leave part of the transaction applied. The cleanup
                        // helper tries to roll back AND restore auto-commit; if either fails the
                        // borrow is marked unsafe so the provider discards the wrapper and the
                        // partial restore can never be committed implicitly. The primary
                        // exception always stays on the cause chain so an operator sees the
                        // original failure.
                        rollbackForFailure(borrowed, conn, e, entered);
                        if (borrowed.isUnsafe()) {
                            if (e instanceof SQLException sql) {
                                throw new PersistenceException(
                                        "Restore failed and its rollback/cleanup did not complete cleanly; "
                                                + "the SQL connection has been closed so a partial restore "
                                                + "cannot be committed implicitly.",
                                        sql);
                            }
                            // For non-SQL failures the primary itself is the cause; rethrow it
                            // after marking unsafe so the borrow is abandoned.
                            if (e instanceof RuntimeException re) {
                                throw re;
                            }
                            if (e instanceof Error err) {
                                throw err;
                            }
                            throw new RuntimeException(e);
                        }
                        if (e instanceof SQLException sql) {
                            throw new PersistenceException("Failed to restore from backup", sql);
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    // Success: data is committed. Restore auto-commit so ordinary repository
                    // writes do not stay inside a manual transaction. If restore fails the
                    // borrowed connection must NOT return to the pool — the data has already
                    // been committed and the typed error makes that unambiguous so the operator
                    // does not roll back.
                    restoreAutoCommitAfterCommit(borrowed, conn, "Restore");
                    return null;
                });
            } catch (SQLException borrowException) {
                throw new PersistenceException(
                        "Failed to restore from backup; rollback/cleanup was attempted",
                        borrowException);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Holds the write lock (the same exclusive boundary used by lifecycle methods) while
     * the composed operation runs, so ordinary writes cannot interleave inside an
     * exclusive window (for example a safety backup followed by a restore). Reentrant by
     * construction: {@link ReentrantReadWriteLock#writeLock()} is reentrant for the same
     * thread, so the safety backup / restore chain inside the window may safely re-enter
     * {@link #backup} / {@link #restore} on this same instance.
     */
    @Override
    public <R> R runExclusive(ExclusiveOperation<R> operation)
            throws PersistenceException, IOException {
        lock.writeLock().lock();
        try {
            ensureInitialized();
            return operation.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------- account repository ----------------

    @Override
    public boolean exists(UUID uuid) {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> existsWithConnection(uuid, conn));
            } catch (SQLException e) {
                throw new PersistenceException("Failed to check account " + uuid, e);
            }
        } finally {
            access.unlock();
        }
    }

    /**
     * Internal account-existence check that reuses a caller-supplied connection.
     * Used by atomic redemption so a single operation borrows exactly one connection
     * even when the pool size is 1.
     */
    private boolean existsWithConnection(UUID uuid, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM " + V2Schema.accountsTable() + " WHERE owner = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Optional<Account> load(UUID uuid) {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> loadWithConnection(uuid, conn));
            } catch (SQLException e) {
                throw new PersistenceException("Failed to load account " + uuid, e);
            }
        } finally {
            access.unlock();
        }
    }

    /**
     * Internal account-load that reuses a caller-supplied connection.
     * Used by atomic redemption so a single operation borrows exactly one connection
     * even when the pool size is 1.
     */
    private Optional<Account> loadWithConnection(UUID uuid, Connection conn) throws SQLException {
        return loadWithConnectionInternal(uuid, conn, false);
    }

    private Optional<Account> loadForUpdateWithConnection(UUID uuid, Connection conn) throws SQLException {
        return loadWithConnectionInternal(uuid, conn, true);
    }

    private Optional<Account> loadWithConnectionInternal(UUID uuid, Connection conn, boolean forUpdate)
            throws SQLException {
        String forUpdateClause = forUpdate ? dialect.forUpdateClause() : "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner_name FROM " + V2Schema.accountsTable() + " WHERE owner = ?" + forUpdateClause)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String ownerName = rs.getString(1);
                Map<String, Amount> balances = loadBalances(uuid, conn, forUpdate);
                return Optional.of(Account.create(uuid, ownerName, balances));
            }
        }
    }

    @Override
    public List<Account> listAll() {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT owner, owner_name FROM " + V2Schema.accountsTable() + " ORDER BY owner")) {
                        List<Account> result = new ArrayList<>();
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                UUID owner = UUID.fromString(rs.getString("owner"));
                                result.add(Account.create(owner, rs.getString("owner_name"), loadBalances(owner, conn)));
                            }
                        }
                        return List.copyOf(result);
                    }
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to list accounts", e);
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public void save(Account account) {
        saveInternal(null, account);
    }

    @Override
    public void save(Account expected, Account updated) {
        saveInternal(expected, updated);
    }

    /**
     * Compare and write in one transaction. A one-argument save is intentionally limited to
     * inserting a missing account or saving an identical snapshot; callers changing an existing
     * account must supply the snapshot they read so a second SQL connection cannot be overwritten
     * by stale data.
     */
    private void saveInternal(Account expected, Account account) {
        if (account == null || (expected != null && !expected.owner().equals(account.owner()))) {
            throw new PersistenceException("Invalid expected account snapshot");
        }
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        Optional<Account> current = loadForUpdateWithConnection(account.owner(), conn);
                        if (expected == null) {
                            if (current.isPresent()) {
                                if (!sameAccount(current.get(), account)) {
                                    throw optimisticConflict(account.owner());
                                }
                            } else {
                                insertAccountRows(account, conn);
                            }
                        } else {
                            if (current.isEmpty() || !sameAccount(current.get(), expected)) {
                                throw optimisticConflict(account.owner());
                            }
                            saveAccountRows(account, conn);
                        }
                        conn.commit();
                    } catch (Throwable e) {
                        rollbackForFailure(borrowed, conn, e, entered);
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Save");
                    return null;
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to save account " + account.owner(), e);
            }
        } finally {
            access.unlock();
        }
    }

    private static PersistenceException optimisticConflict(UUID owner) {
        return new PersistenceException("Optimistic account conflict for " + owner
                + "; the caller snapshot is stale");
    }

    private static boolean sameAccount(Account left, Account right) {
        if (!left.owner().equals(right.owner()) || !left.ownerName().equals(right.ownerName())
                || !left.balances().keySet().equals(right.balances().keySet())) {
            return false;
        }
        for (String currency : left.balances().keySet()) {
            if (left.balances().get(currency).compareTo(right.balances().get(currency)) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Persist one account's rows assuming an OPEN transaction (no commit / no autocommit
     * handling). Shared by {@link #save} and the atomic reversal path so both write exactly
     * the same rows in exactly the same order.
     */
    private void saveAccountRows(Account account, Connection conn) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM " + V2Schema.balancesTable() + " WHERE owner = ?")) {
            del.setString(1, account.owner().toString());
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "REPLACE INTO " + V2Schema.accountsTable() + " (owner, owner_name) VALUES (?, ?)")) {
            ins.setString(1, account.owner().toString());
            ins.setString(2, account.ownerName());
            ins.executeUpdate();
        }
        try (PreparedStatement bal = conn.prepareStatement(
                "REPLACE INTO " + V2Schema.balancesTable()
                        + " (owner, currency_id, amount) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Amount> e : account.balances().entrySet()) {
                bal.setString(1, account.owner().toString());
                bal.setString(2, e.getKey());
                bal.setString(3, amountToString(e.getValue()));
                bal.addBatch();
            }
            bal.executeBatch();
        }
    }

    /** Insert rows for a new account without replacement, so a create race cannot overwrite data. */
    private void insertAccountRows(Account account, Connection conn) throws SQLException {
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO " + V2Schema.accountsTable() + " (owner, owner_name) VALUES (?, ?)")) {
            ins.setString(1, account.owner().toString());
            ins.setString(2, account.ownerName());
            ins.executeUpdate();
        }
        try (PreparedStatement bal = conn.prepareStatement(
                "INSERT INTO " + V2Schema.balancesTable()
                        + " (owner, currency_id, amount) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Amount> e : account.balances().entrySet()) {
                bal.setString(1, account.owner().toString());
                bal.setString(2, e.getKey());
                bal.setString(3, amountToString(e.getValue()));
                bal.addBatch();
            }
            bal.executeBatch();
        }
    }

    @Override
    public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    Optional<Account> existing = loadWithConnection(uuid, conn);
                    if (existing.isPresent()) {
                        return existing.get(); // safe: never overwrite an existing account
                    }
                    Account account = Account.create(uuid, ownerName, initialBalances);
                    boolean entered = false;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        saveAccountRows(account, conn);
                        conn.commit();
                    } catch (Throwable e) {
                        rollbackForFailure(borrowed, conn, e, entered);
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Create account");
                    return account;
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to create account " + uuid, e);
            }
        } finally {
            access.unlock();
        }
    }

    // ---------------- transaction repository ----------------

    @Override
    public void append(Transaction transaction) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                withBorrowed((borrowed, conn) -> {
                    insertTransactionRow(transaction, false, conn);
                    return null;
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to append transaction " + transaction.id(), e);
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public void appendBatch(List<Transaction> transactions) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            if (transactions.isEmpty()) {
                return;
            }
            try {
                withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        try (PreparedStatement ps = conn.prepareStatement(transactionInsertSql())) {
                            for (Transaction t : transactions) {
                                bindTransaction(ps, t, false);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                        conn.commit();
                    } catch (Throwable e) {
                        rollbackForFailure(borrowed, conn, e, entered);
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Append transaction batch");
                    return null;
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to append transaction batch", e);
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public void markReverted(UUID transactionId) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                withBorrowed((borrowed, conn) -> {
                    if (markRevertedRow(transactionId, conn) == 0) {
                        throw new PersistenceException(
                                "Cannot mark unknown transaction reverted: " + transactionId);
                    }
                    return null;
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to mark transaction reverted " + transactionId, e);
            }
        } finally {
            access.unlock();
        }
    }

    /**
     * Set the reverted flag on one row assuming an OPEN transaction. Returns the affected
     * row count so callers can distinguish an unknown id from a successful (idempotent) mark.
     */
    private int markRevertedRow(UUID transactionId, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + V2Schema.transactionsTable() + " SET reverted = ? WHERE id = ?")) {
            ps.setBoolean(1, true);
            ps.setString(2, transactionId.toString());
            return ps.executeUpdate();
        }
    }

    @Override
    public boolean isReverted(UUID transactionId) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT reverted FROM " + V2Schema.transactionsTable() + " WHERE id = ?")) {
                        ps.setString(1, transactionId.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() && rs.getBoolean(1);
                        }
                    }
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to read reverted flag " + transactionId, e);
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public List<Transaction> loadByAccount(UUID accountId) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT * FROM " + V2Schema.transactionsTable()
                                    + " WHERE account_id = ? ORDER BY timestamp")) {
                        ps.setString(1, accountId.toString());
                        return readTransactions(ps.executeQuery());
                    }
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to load transactions for " + accountId, e);
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public List<Transaction> loadAll() throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT * FROM " + V2Schema.transactionsTable() + " ORDER BY timestamp")) {
                        return readTransactions(ps.executeQuery());
                    }
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to load transactions", e);
            }
        } finally {
            access.unlock();
        }
    }

    // ---------------- atomic reversal store ----------------

    @Override
    public void applyReversal(List<Account> updatedAccounts,
                              List<Transaction> reversalRecords,
                              List<UUID> revertMarkerIds) throws PersistenceException {
        // Keep the original port shape source-compatible, but never use its caller snapshots as
        // the write source. The audit records carry the signed reversal intent and the transaction
        // below recalculates every balance from live, locked rows.
        applyReversalDeltas(reversalRecords, revertMarkerIds);
    }

    private void applyReversalDeltas(List<Transaction> reversalRecords,
                                     List<UUID> revertMarkerIds) throws PersistenceException {
        // One JDBC transaction carries balances + reversal records + markers: any failure
        // rolls back every statement, so no half-applied reversal is ever committed.
        Lock access = accessLock();
        access.lock();
        java.util.concurrent.atomic.AtomicBoolean committedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            ensureInitialized();
            try {
                withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        // Lock all accounts in a stable order before calculating any delta. This
                        // prevents two multi-account reversals from acquiring row locks in opposite
                        // order while still making the database the cross-process authority.
                        java.util.Set<UUID> ids = new java.util.TreeSet<>();
                        for (Transaction record : reversalRecords) {
                            ids.add(record.accountId());
                        }
                        Map<UUID, Account> live = new LinkedHashMap<>();
                        for (UUID id : ids) {
                            Optional<Account> account = loadForUpdateWithConnection(id, conn);
                            if (account.isEmpty()) {
                                throw new SQLException("account not found for reversal: " + id);
                            }
                            live.put(id, account.get());
                        }
                        // Idempotency: locking read on revert markers after account locks.
                        // Using FOR UPDATE ensures a concurrent MySQL reversal blocks on the same
                        // marker row until the first transaction commits, then sees reverted=true
                        // and returns idempotently instead of double-debiting.
                        // Marker idempotency with DB row lock. Account locks (TreeSet order) are
                        // already held above; marker SELECT ... FOR UPDATE is issued inside the same
                        // JDBC transaction so concurrent MySQL reversals serialize on the marker row.
                        // Semantics: all reverted => idempotent no-op; none reverted => full apply;
                        // partial (some reverted, some not) => reject to avoid re-applying the
                        // already-committed leg with a duplicate audit record.
                        boolean alreadyApplied = !revertMarkerIds.isEmpty();
                        boolean anyReverted = false;
                        boolean anyNotReverted = false;
                        if (alreadyApplied) {
                            java.util.List<UUID> sortedMarkers = new java.util.ArrayList<>(revertMarkerIds);
                            java.util.Collections.sort(sortedMarkers);
                            String forUpdate = dialect.forUpdateClause();
                            for (UUID markerId : sortedMarkers) {
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "SELECT reverted FROM " + V2Schema.transactionsTable() + " WHERE id = ?" + forUpdate)) {
                                    ps.setString(1, markerId.toString());
                                    try (ResultSet rs = ps.executeQuery()) {
                                        if (!rs.next()) {
                                            throw new SQLException(
                                                    "unknown transaction for revert marker: " + markerId);
                                        }
                                        if (rs.getBoolean(1)) {
                                            anyReverted = true;
                                        } else {
                                            anyNotReverted = true;
                                        }
                                    }
                                }
                            }
                            if (anyReverted && anyNotReverted) {
                                throw new PersistenceException(
                                        "partial reversal markers already reverted: markers=" + revertMarkerIds);
                            }
                            alreadyApplied = anyReverted && !anyNotReverted;
                        }
                        if (alreadyApplied) {
                            conn.commit();
                            committedFlag.set(true);
                        } else {

                            List<Transaction> authoritativeRecords = new ArrayList<>(reversalRecords.size());
                            for (Transaction record : reversalRecords) {
                                Account base = live.get(record.accountId());
                                String currencyId = Currency.normalizeId(record.currencyId());
                                Amount delta = reversalDelta(record);
                                Amount before = base.balanceOf(currencyId);
                                if (before == null) {
                                    before = Amount.zero(delta.scale());
                                }
                                Account next = delta.isNegative()
                                        ? base.withdraw(currencyId, delta.abs())
                                        : base.deposit(currencyId, delta);
                                Amount after = next.balanceOf(currencyId);
                                live.put(record.accountId(), next);
                                authoritativeRecords.add(authoritativeReversal(record, before, after));
                            }

                            for (Account account : live.values()) {
                                saveAccountRows(account, conn);
                            }
                            for (Transaction record : authoritativeRecords) {
                                insertTransactionRow(record, false, conn);
                            }
                            for (UUID markerId : revertMarkerIds) {
                                if (markRevertedRow(markerId, conn) == 0) {
                                    throw new SQLException(
                                            "unknown transaction for revert marker: " + markerId);
                                }
                            }
                            conn.commit();
                            committedFlag.set(true);
                        }
                    } catch (Throwable e) {
                        rollbackForFailure(borrowed, conn, e, entered);
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Apply reversal");
                    return null;
                });
            } catch (PersistenceException e) {
                if (e.isCommitted() || committedFlag.get()) {
                    if (!e.isCommitted()) {
                        throw new PersistenceException(e.getMessage(), e.getCause() != null ? e.getCause() : e, true);
                    }
                    throw e;
                }
                throw e;
            } catch (SQLException e) {
                if (committedFlag.get()) {
                    throw new PersistenceException(
                            "Apply reversal committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            e, true);
                }
                throw new PersistenceException("Failed to apply reversal atomically", e);
            } catch (RuntimeException | Error e) {
                if (committedFlag.get()) {
                    Throwable cause = e.getCause();
                    Throwable committedCause = cause != null ? cause : e;
                    if (e instanceof PersistenceException pe && pe.isCommitted()) {
                        throw pe;
                    }
                    // BorrowedConnection.close failure after commit is wrapped as SQLException above,
                    // but RuntimeException/Error post-commit must still be marked committed.
                    throw new PersistenceException(
                            "Apply reversal committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            committedCause, true);
                }
                throw e;
            }
        } finally {
            access.unlock();
        }
    }

    private static Amount reversalDelta(Transaction record) {
        return switch (record.type()) {
            case DEPOSIT, TRANSFER_IN -> record.amount();
            case WITHDRAW, TRANSFER_OUT -> record.amount().negate();
            case SET -> {
                if (record.balanceBefore() == null || record.balanceAfter() == null) {
                    throw new PersistenceException("SET reversal requires balanceBefore and balanceAfter");
                }
                yield record.balanceAfter().subtract(record.balanceBefore());
            }
        };
    }

    private static Transaction authoritativeReversal(Transaction record, Amount before, Amount after) {
        Amount auditAmount = record.type() == TransactionType.SET ? after : record.amount().abs();
        return new Transaction(record.id(), record.accountId(), record.counterparty(),
                Currency.normalizeId(record.currencyId()), auditAmount, record.type(), before, after,
                record.timestamp(), record.reason());
    }

    // ---------------- nonce store ----------------

    @Override
    public boolean consume(UUID nonce) throws PersistenceException {
        // INSERT IGNORE / INSERT OR IGNORE against the primary key makes the decision
        // atomic at the storage level: affected rows 1 = first writer, 0 = already consumed.
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    try (PreparedStatement ps = conn.prepareStatement(V2Schema.nonceInsertSql(dialect))) {
                        ps.setString(1, nonce.toString());
                        ps.setString(2, Instant.now().toString());
                        return ps.executeUpdate() == 1;
                    }
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to consume nonce " + nonce, e);
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public boolean isConsumed(UUID nonce) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT nonce FROM " + V2Schema.noncesTable() + " WHERE nonce = ?")) {
                        ps.setString(1, nonce.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next();
                        }
                    }
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to read nonce " + nonce, e);
            }
        } finally {
            access.unlock();
        }
    }

    // ---------------- atomic redemption ----------------

    @Override
    public RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction)
            throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        java.util.concurrent.atomic.AtomicBoolean committedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    boolean replayPath = false;
                    boolean committed = false;
                    Amount beforeHolder = null;
                    Amount afterHolder = null;
                    try {
                        // The caller's Account and balance fields are only a prepared snapshot. Begin
                        // before the read, lock the live account row, and derive the credit from the
                        // transaction amount so another process cannot be overwritten by stale data.
                        conn.setAutoCommit(false);
                        entered = true;
                        Optional<Account> existing = loadForUpdateWithConnection(transaction.accountId(), conn);
                        if (existing.isEmpty()) {
                            cleanupMissingAccount(borrowed, conn);
                            return RedemptionResult.accountMissing();
                        }
                        Amount current = existing.get().balanceOf(transaction.currencyId());
                        Amount before = current == null
                                ? Amount.zero(transaction.amount().scale()) : current;
                        Account updated = existing.get().deposit(transaction.currencyId(), transaction.amount());
                        Amount after = updated.balanceOf(transaction.currencyId());
                        Transaction authoritative = new Transaction(transaction.id(), transaction.accountId(),
                                transaction.counterparty(), Currency.normalizeId(transaction.currencyId()),
                                transaction.amount(), TransactionType.DEPOSIT, before, after,
                                transaction.timestamp(), transaction.reason());
                        beforeHolder = before;
                        afterHolder = after;
                        saveAccountRows(updated, conn);
                        insertTransactionRow(authoritative, false, conn);
                        try (PreparedStatement ps = conn.prepareStatement(
                                V2Schema.nonceInsertSql(dialect))) {
                            ps.setString(1, nonce.toString());
                            ps.setString(2, Instant.now().toString());
                            if (ps.executeUpdate() != 1) {
                                replayPath = true;
                                cleanupReplay(borrowed, conn);
                                return RedemptionResult.replay();
                            }
                        }
                        conn.commit();
                        committed = true;
                        committedFlag.set(true);
                    } catch (Throwable e) {
                        if (!replayPath && !committed) {
                            rollbackForFailure(borrowed, conn, e, entered);
                        }
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Redeem banknote");
                    return RedemptionResult.committed(beforeHolder, afterHolder, transaction.id());
                });
            } catch (PersistenceException e) {
                if (e.isCommitted() || committedFlag.get()) {
                    if (!e.isCommitted()) {
                        throw new PersistenceException(e.getMessage(), e.getCause() != null ? e.getCause() : e, true);
                    }
                    throw e;
                }
                throw e;
            } catch (SQLException e) {
                if (committedFlag.get()) {
                    throw new PersistenceException(
                            "Redeem banknote committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            e, true);
                }
                throw new PersistenceException("Failed to redeem banknote " + nonce, e);
            } catch (RuntimeException | Error e) {
                if (committedFlag.get()) {
                    if (e instanceof PersistenceException pe && pe.isCommitted()) {
                        throw pe;
                    }
                    Throwable cause = e.getCause();
                    Throwable committedCause = cause != null ? cause : e;
                    throw new PersistenceException(
                            "Redeem banknote committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            committedCause, true);
                }
                throw e;
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction,
                                           DebtPolicy debtPolicy) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        java.util.concurrent.atomic.AtomicBoolean committedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    boolean replayPath = false;
                    boolean committed = false;
                    Amount beforeHolder = null;
                    Amount afterHolder = null;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        Optional<Account> existing = loadForUpdateWithConnection(transaction.accountId(), conn);
                        if (existing.isEmpty()) {
                            cleanupMissingAccount(borrowed, conn);
                            return RedemptionResult.accountMissing();
                        }
                        Amount current = existing.get().balanceOf(transaction.currencyId());
                        Amount before = current == null
                                ? Amount.zero(transaction.amount().scale()) : current;
                        Account updated = existing.get().deposit(transaction.currencyId(), transaction.amount());
                        Amount after = updated.balanceOf(transaction.currencyId());
                        if (debtPolicy != null && !debtPolicy.allows(after)) {
                            cleanupMissingAccount(borrowed, conn);
                            return RedemptionResult.debtLimitExceeded();
                        }
                        Transaction authoritative = new Transaction(transaction.id(), transaction.accountId(),
                                transaction.counterparty(), Currency.normalizeId(transaction.currencyId()),
                                transaction.amount(), TransactionType.DEPOSIT, before, after,
                                transaction.timestamp(), transaction.reason());
                        beforeHolder = before;
                        afterHolder = after;
                        saveAccountRows(updated, conn);
                        insertTransactionRow(authoritative, false, conn);
                        try (PreparedStatement ps = conn.prepareStatement(
                                V2Schema.nonceInsertSql(dialect))) {
                            ps.setString(1, nonce.toString());
                            ps.setString(2, Instant.now().toString());
                            if (ps.executeUpdate() != 1) {
                                replayPath = true;
                                cleanupReplay(borrowed, conn);
                                return RedemptionResult.replay();
                            }
                        }
                        conn.commit();
                        committed = true;
                        committedFlag.set(true);
                    } catch (Throwable e) {
                        if (!replayPath && !committed) {
                            rollbackForFailure(borrowed, conn, e, entered);
                        }
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Redeem banknote");
                    return RedemptionResult.committed(beforeHolder, afterHolder, transaction.id());
                });
            } catch (PersistenceException e) {
                if (e.isCommitted() || committedFlag.get()) {
                    if (!e.isCommitted()) {
                        throw new PersistenceException(e.getMessage(), e.getCause() != null ? e.getCause() : e, true);
                    }
                    throw e;
                }
                throw e;
            } catch (SQLException e) {
                if (committedFlag.get()) {
                    throw new PersistenceException(
                            "Redeem banknote committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            e, true);
                }
                throw new PersistenceException("Failed to redeem banknote " + nonce, e);
            } catch (RuntimeException | Error e) {
                if (committedFlag.get()) {
                    if (e instanceof PersistenceException pe && pe.isCommitted()) {
                        throw pe;
                    }
                    Throwable cause = e.getCause();
                    Throwable committedCause = cause != null ? cause : e;
                    throw new PersistenceException(
                            "Redeem banknote committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            committedCause, true);
                }
                throw e;
            }
        } finally {
            access.unlock();
        }
    }

    @Override
    public TransferResult transfer(UUID from, UUID to, String currencyId, Amount amount,
                                   DebtPolicy debtPolicy) throws PersistenceException {
        String cid = Currency.normalizeId(currencyId);
        Lock access = accessLock();
        access.lock();
        java.util.concurrent.atomic.AtomicBoolean committedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    boolean entered = false;
                    boolean committed = false;
                    Amount fromAfterHolder = null;
                    Amount toAfterHolder = null;
                    UUID outIdHolder = null;
                    UUID inIdHolder = null;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        // lock both accounts in deterministic order
                        java.util.Set<UUID> ordered = new java.util.TreeSet<>();
                        ordered.add(from);
                        ordered.add(to);
                        java.util.Map<UUID, Account> live = new java.util.LinkedHashMap<>();
                        for (UUID id : ordered) {
                            Optional<Account> acc = loadForUpdateWithConnection(id, conn);
                            if (acc.isEmpty()) {
                                conn.rollback();
                                try { conn.setAutoCommit(true); } catch (Throwable t) { borrowed.markUnsafe(); }
                                throw new PersistenceException("account not found for transfer: " + id);
                            }
                            live.put(id, acc.get());
                        }
                        Account fromAcc = live.get(from);
                        Account toAcc = live.get(to);
                        Amount fromBefore = fromAcc.balanceOf(cid);
                        if (fromBefore == null) fromBefore = Amount.zero(amount.scale());
                        Amount toBefore = toAcc.balanceOf(cid);
                        if (toBefore == null) toBefore = Amount.zero(amount.scale());
                        Amount fromAfter = fromBefore.subtract(amount);
                        if (debtPolicy != null && !debtPolicy.allows(fromAfter)) {
                            conn.rollback();
                            try { conn.setAutoCommit(true); } catch (Throwable t) { borrowed.markUnsafe(); }
                            throw new AtomicTransferStore.DebtLimitExceededException("debt limit exceeded");
                        }
                        Amount toAfter = toBefore.add(amount);
                        Account updatedFrom = fromAcc.withdraw(cid, amount);
                        Account updatedTo = toAcc.deposit(cid, amount);
                        saveAccountRows(updatedFrom, conn);
                        saveAccountRows(updatedTo, conn);
                        UUID outId = UUID.randomUUID();
                        UUID inId = UUID.randomUUID();
                        Instant now = Instant.now();
                        Transaction outTx = new Transaction(outId, from, to, cid, amount,
                                TransactionType.TRANSFER_OUT, fromBefore, fromAfter, now, "transfer-out");
                        Transaction inTx = new Transaction(inId, to, from, cid, amount,
                                TransactionType.TRANSFER_IN, toBefore, toAfter, now, "transfer-in");
                        insertTransactionRow(outTx, false, conn);
                        insertTransactionRow(inTx, false, conn);
                        conn.commit();
                        committed = true;
                        committedFlag.set(true);
                        fromAfterHolder = fromAfter;
                        toAfterHolder = toAfter;
                        outIdHolder = outId;
                        inIdHolder = inId;
                    } catch (Throwable e) {
                        if (!committed) {
                            rollbackForFailure(borrowed, conn, e, entered);
                        }
                        if (e instanceof SQLException sql) throw sql;
                        if (e instanceof RuntimeException re) throw re;
                        if (e instanceof Error err) throw err;
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Transfer");
                    return new TransferResult(from, to, fromAfterHolder, toAfterHolder, outIdHolder, inIdHolder);
                });
            } catch (PersistenceException e) {
                if (e.isCommitted() || committedFlag.get()) {
                    if (!e.isCommitted()) {
                        throw new PersistenceException(e.getMessage(), e.getCause() != null ? e.getCause() : e, true);
                    }
                    throw e;
                }
                throw e;
            } catch (SQLException e) {
                if (committedFlag.get()) {
                    throw new PersistenceException(
                            "Transfer committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            e, true);
                }
                throw new PersistenceException("Failed to transfer", e);
            } catch (RuntimeException | Error e) {
                if (committedFlag.get()) {
                    if (e instanceof PersistenceException pe && pe.isCommitted()) {
                        throw pe;
                    }
                    Throwable cause = e.getCause();
                    Throwable committedCause = cause != null ? cause : e;
                    throw new PersistenceException(
                            "Transfer committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                            committedCause, true);
                }
                throw e;
            }
        } finally {
            access.unlock();
        }
    }

    private void cleanupMissingAccount(BorrowedConnection borrowed, Connection conn) throws SQLException {
        try {
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (Throwable cleanupFailure) {
            borrowed.markUnsafe();
            if (cleanupFailure instanceof SQLException sql) {
                throw sql;
            }
            if (cleanupFailure instanceof RuntimeException re) {
                throw re;
            }
            if (cleanupFailure instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cleanupFailure);
        }
    }

    @Override
    public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId,
                                    Amount amount) throws PersistenceException {
        Lock access = accessLock();
        access.lock();
        try {
            ensureInitialized();
            try {
                return withBorrowed((borrowed, conn) -> {
                    // The read-modify-write must be atomic under MySQL: start the
                    // transaction BEFORE loading the account and use SELECT ... FOR UPDATE
                    // (via dialect.forUpdateClause()) so concurrent MySQL connections
                    // serialize on the row lock instead of reading the same stale balance
                    // and overwriting each other. SQLite returns an empty clause and
                    // remains serialized by the write lock. The whole load/lock/update/
                    // audit/nonce/commit chain uses the SAME borrowed connection so a
                    // pool size of 1 never deadlocks.
                    boolean replayPath = false;
                    boolean entered = false;
                    Amount beforeHolder = null;
                    Amount afterHolder = null;
                    UUID creditIdHolder = null;
                    try {
                        conn.setAutoCommit(false);
                        entered = true;
                        Optional<Account> existing = loadWithConnectionInternal(accountId, conn, true);
                        if (existing.isEmpty()) {
                            // Unknown account must not consume the nonce. Roll back the
                            // empty transaction and restore auto-commit before returning.
                            try {
                                conn.rollback();
                            } catch (Throwable rollbackFailure) {
                                borrowed.markUnsafe();
                                if (rollbackFailure instanceof SQLException sql) {
                                    throw sql;
                                }
                                if (rollbackFailure instanceof RuntimeException re) {
                                    throw re;
                                }
                                if (rollbackFailure instanceof Error err) {
                                    throw err;
                                }
                                throw new RuntimeException(rollbackFailure);
                            }
                            try {
                                conn.setAutoCommit(true);
                            } catch (Throwable autoCommitFailure) {
                                borrowed.markUnsafe();
                                if (autoCommitFailure instanceof SQLException sql) {
                                    throw sql;
                                }
                                if (autoCommitFailure instanceof RuntimeException re) {
                                    throw re;
                                }
                                if (autoCommitFailure instanceof Error err) {
                                    throw err;
                                }
                                throw new RuntimeException(autoCommitFailure);
                            }
                            return RedemptionResult.accountMissing();
                        }
                        Amount current = existing.get().balanceOf(currencyId);
                        Amount before = current == null ? Amount.zero(amount.scale()) : current;
                        Account updated = existing.get().deposit(currencyId, amount);
                        Amount after = updated.balanceOf(currencyId);
                        Transaction credit = new Transaction(UUID.randomUUID(), accountId, null,
                                Currency.normalizeId(currencyId), amount, TransactionType.DEPOSIT,
                                before, after, Instant.now(), "banknote-deposit");
                        beforeHolder = before;
                        afterHolder = after;
                        creditIdHolder = credit.id();

                        // One JDBC transaction carries the balance update, the audit record and the nonce
                        // insert. The nonce insert uses INSERT IGNORE / INSERT OR IGNORE against the primary
                        // key, so first-writer-wins is decided at the storage level even across processes:
                        // 0 affected rows means another writer consumed the nonce first and the whole
                        // transaction rolls back with nothing credited.
                        saveAccountRows(updated, conn);
                        insertTransactionRow(credit, false, conn);
                        try (PreparedStatement ps = conn.prepareStatement(
                                V2Schema.nonceInsertSql(dialect))) {
                            ps.setString(1, nonce.toString());
                            ps.setString(2, Instant.now().toString());
                            if (ps.executeUpdate() != 1) {
                                replayPath = true;
                                cleanupReplay(borrowed, conn);
                                return RedemptionResult.replay();
                            }
                        }
                        conn.commit();
                    } catch (Throwable e) {
                        if (!replayPath) {
                            rollbackForFailure(borrowed, conn, e, entered);
                        }
                        if (e instanceof SQLException sql) {
                            throw sql;
                        }
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        if (e instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(e);
                    }
                    restoreAutoCommitAfterCommit(borrowed, conn, "Redeem banknote");
                    return RedemptionResult.committed(beforeHolder, afterHolder, creditIdHolder);
                });
            } catch (SQLException e) {
                throw new PersistenceException("Failed to redeem banknote " + nonce, e);
            }
        } finally {
            access.unlock();
        }
    }

    /**
     * Run the replay-path cleanup: roll back the open transaction and restore auto-commit.
     * A cleanup failure is surfaced as the primary failure for this operation; the borrow
     * remains unsafe so release cannot return it to the pool. The caller must not attempt a
     * second rollback after this method fails.
     */
    private void cleanupReplay(BorrowedConnection borrowed, Connection conn) throws SQLException {
        try {
            conn.rollback();
        } catch (Throwable rollbackFailure) {
            borrowed.markUnsafe();
            if (rollbackFailure instanceof SQLException sql) {
                throw sql;
            }
            if (rollbackFailure instanceof RuntimeException re) {
                throw re;
            }
            if (rollbackFailure instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(rollbackFailure);
        }
        try {
            conn.setAutoCommit(true);
        } catch (Throwable autoCommitFailure) {
            borrowed.markUnsafe();
            if (autoCommitFailure instanceof SQLException sql) {
                throw sql;
            }
            if (autoCommitFailure instanceof RuntimeException re) {
                throw re;
            }
            if (autoCommitFailure instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(autoCommitFailure);
        }
    }

    // ---------------- internals ----------------

    private void createSchema(BorrowedConnection borrowed, Connection conn) throws SQLException {
        boolean entered = false;
        try {
            conn.setAutoCommit(false);
            entered = true;
            for (String ddl : V2Schema.ddlStatements(dialect)) {
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(V2Schema.versionInsertSql(dialect))) {
                ps.setString(1, Instant.now().toString());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Throwable e) {
            rollbackForFailure(borrowed, conn, e, entered);
            if (e instanceof SQLException sql) {
                throw sql;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            if (e instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(e);
        }
        restoreAutoCommitAfterCommit(borrowed, conn, "Create schema");
    }

    private void dropSchema(BorrowedConnection borrowed, Connection conn) throws SQLException {
        boolean entered = false;
        try {
            conn.setAutoCommit(false);
            entered = true;
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.transactionsTable());
                st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.balancesTable());
                st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.accountsTable());
                st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.noncesTable());
                st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.schemaTable());
            }
            conn.commit();
        } catch (Throwable e) {
            rollbackForFailure(borrowed, conn, e, entered);
            if (e instanceof SQLException sql) {
                throw sql;
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            if (e instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(e);
        }
        restoreAutoCommitAfterCommit(borrowed, conn, "Drop schema");
    }

    private Map<String, Amount> loadBalances(UUID uuid, Connection conn) throws SQLException {
        return loadBalances(uuid, conn, false);
    }

    private Map<String, Amount> loadBalances(UUID uuid, Connection conn, boolean forUpdate)
            throws SQLException {
        String forUpdateClause = forUpdate ? dialect.forUpdateClause() : "";
        Map<String, Amount> balances = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT currency_id, amount FROM " + V2Schema.balancesTable() + " WHERE owner = ?"
                        + forUpdateClause)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(rs.getString(1), stringToAmount(rs.getString(2)));
                }
            }
        }
        return balances;
    }

    private void insertAccount(JsonModel.JsonAccount a, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "REPLACE INTO " + V2Schema.accountsTable() + " (owner, owner_name) VALUES (?, ?)")) {
            ps.setString(1, a.owner);
            ps.setString(2, a.ownerName);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "REPLACE INTO " + V2Schema.balancesTable()
                        + " (owner, currency_id, amount) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, String> e : a.balances.entrySet()) {
                ps.setString(1, a.owner);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTransaction(JsonModel.JsonTransaction t, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(transactionInsertSql())) {
            bindTransaction(ps, t, t.reverted);
            ps.executeUpdate();
        }
    }

    /** Reinsert the snapshot's consumed nonces, assuming an OPEN transaction. */
    private void insertNonces(JsonModel candidate, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "REPLACE INTO " + V2Schema.noncesTable() + " (nonce, consumed_at) VALUES (?, ?)")) {
            for (Map.Entry<String, String> e : candidate.nonces.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTransactionRow(Transaction t, boolean reverted, Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(transactionInsertSql())) {
            bindTransaction(ps, t, reverted);
            ps.executeUpdate();
        }
    }

    private String transactionInsertSql() {
        // Plain INSERT (no IGNORE): a duplicate id or a null amount must fail loudly so the
        // caller's transaction boundary rolls back instead of silently dropping a record.
        return "INSERT INTO " + V2Schema.transactionsTable()
                + " (id, account_id, counterparty, currency_id, amount, type,"
                + " balance_before, balance_after, timestamp, reason, reverted)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private void bindTransaction(PreparedStatement ps, Transaction t, boolean reverted) throws SQLException {
        ps.setString(1, t.id().toString());
        ps.setString(2, t.accountId().toString());
        ps.setString(3, t.counterparty() == null ? null : t.counterparty().toString());
        ps.setString(4, t.currencyId());
        ps.setString(5, amountToString(t.amount()));
        ps.setString(6, t.type().name());
        ps.setString(7, t.balanceBefore() == null ? null : amountToString(t.balanceBefore()));
        ps.setString(8, t.balanceAfter() == null ? null : amountToString(t.balanceAfter()));
        ps.setString(9, t.timestamp().toString());
        ps.setString(10, t.reason());
        ps.setBoolean(11, reverted);
    }

    private void bindTransaction(PreparedStatement ps, JsonModel.JsonTransaction t, boolean reverted)
            throws SQLException {
        ps.setString(1, t.id);
        ps.setString(2, t.accountId);
        ps.setString(3, t.counterparty);
        ps.setString(4, t.currencyId);
        ps.setString(5, t.amount);
        ps.setString(6, t.type);
        ps.setString(7, t.balanceBefore);
        ps.setString(8, t.balanceAfter);
        ps.setString(9, t.timestamp);
        ps.setString(10, t.reason);
        ps.setBoolean(11, reverted);
    }

    private List<Transaction> readTransactions(ResultSet rs) throws SQLException {
        List<Transaction> result = new ArrayList<>();
        while (rs.next()) {
            result.add(new Transaction(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("account_id")),
                    rs.getString("counterparty") == null ? null : UUID.fromString(rs.getString("counterparty")),
                    rs.getString("currency_id"),
                    stringToAmount(rs.getString("amount")),
                    TransactionType.valueOf(rs.getString("type")),
                    rs.getString("balance_before") == null ? null : stringToAmount(rs.getString("balance_before")),
                    rs.getString("balance_after") == null ? null : stringToAmount(rs.getString("balance_after")),
                    Instant.parse(rs.getString("timestamp")),
                    rs.getString("reason")));
        }
        return result;
    }

    private JsonModel loadAllIntoModel(Connection conn) throws PersistenceException {
        JsonModel model = new JsonModel();
        try (PreparedStatement acc = conn.prepareStatement(
                "SELECT owner, owner_name FROM " + V2Schema.accountsTable());
             ResultSet ars = acc.executeQuery()) {
            while (ars.next()) {
                JsonModel.JsonAccount a = new JsonModel.JsonAccount();
                a.owner = ars.getString("owner");
                a.ownerName = ars.getString("owner_name");
                model.accounts.put(a.owner, a);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read accounts for backup", e);
        }
        try (PreparedStatement bal = conn.prepareStatement(
                "SELECT owner, currency_id, amount FROM " + V2Schema.balancesTable());
             ResultSet brs = bal.executeQuery()) {
            while (brs.next()) {
                String owner = brs.getString("owner");
                JsonModel.JsonAccount a = model.accounts.get(owner);
                if (a != null) {
                    a.balances.put(brs.getString("currency_id"), brs.getString("amount"));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read balances for backup", e);
        }
        try (PreparedStatement tx = conn.prepareStatement(
                "SELECT * FROM " + V2Schema.transactionsTable());
             ResultSet trs = tx.executeQuery()) {
            while (trs.next()) {
                JsonModel.JsonTransaction t = new JsonModel.JsonTransaction();
                t.id = trs.getString("id");
                t.accountId = trs.getString("account_id");
                t.counterparty = trs.getString("counterparty");
                t.currencyId = trs.getString("currency_id");
                t.amount = trs.getString("amount");
                t.type = trs.getString("type");
                t.balanceBefore = trs.getString("balance_before");
                t.balanceAfter = trs.getString("balance_after");
                t.timestamp = trs.getString("timestamp");
                t.reason = trs.getString("reason");
                t.reverted = trs.getBoolean("reverted");
                model.transactions.add(t);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read transactions for backup", e);
        }
        try (PreparedStatement nc = conn.prepareStatement(
                "SELECT nonce, consumed_at FROM " + V2Schema.noncesTable());
             ResultSet nrs = nc.executeQuery()) {
            while (nrs.next()) {
                model.nonces.put(nrs.getString(1), nrs.getString(2));
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read nonces for backup", e);
        }
        return model;
    }

    private boolean tableExists(String tableName, Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        if (meta == null) {
            return false;
        }
        try (ResultSet rs = meta.getTables(null, null, tableName, null)) {
            return rs != null && rs.next();
        }
    }

    /**
     * Reads all marker versions. Marker states:
     * - no marker: schema table absent or empty (allows partial-init recovery)
     * - single current: exactly one row with version == CURRENT (restart is idempotent)
     * - single incompatible: exactly one row with version != CURRENT including 0 (must fail-closed)
     * - multiple: more than one row (corruption, must not hide by first-row)
     * Stored 0 is an explicit marker and is never treated as absence, so an
     * invalid 0 never gets silently upgraded by inserting 1.
     */
    private List<Integer> readAllSchemaVersions(Connection conn) throws SQLException {
        if (!tableExists(V2Schema.schemaTable(), conn)) {
            return List.of();
        }
        List<Integer> versions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM " + V2Schema.schemaTable())) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.add(rs.getInt(1));
                }
            }
        }
        return versions;
    }

    private int readSchemaVersion(Connection conn) throws SQLException {
        List<Integer> versions = readAllSchemaVersions(conn);
        if (versions.isEmpty()) {
            return 0;
        }
        if (versions.size() > 1) {
            throw new PersistenceException(
                    "Corrupted schema marker: multiple rows " + versions + " in " + V2Schema.schemaTable());
        }
        return versions.get(0);
    }

    private static String amountToString(Amount a) {
        return a == null ? null : a.value().toPlainString();
    }

    private static Amount stringToAmount(String s) {
        BigDecimal bd = new BigDecimal(s);
        return Amount.of(bd, bd.scale());
    }
}
