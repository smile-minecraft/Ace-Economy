package com.smile.aceeconomy.infrastructure.persistence.sql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A small provider-owned pool. Physical connections stay open while idle; a borrower only
 * receives a proxy whose close is routed back through this pool. Reset is completed before the
 * physical connection can become idle, and a reset failure removes that slot first.
 */
final class StrictSqlConnectionPool {
    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    interface ConnectionDisposer {
        void dispose(Connection connection) throws Throwable;
    }

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final long DEFAULT_MAX_LIFETIME_MILLIS = 1_800_000L;
    private static final int DEFAULT_POOL_SIZE = 10;

    private final ConnectionFactory factory;
    private final ConnectionDisposer managedDisposer;
    private final ConnectionDisposer externalReturnDisposer;
    private final ConnectionDisposer externalAbandonDisposer;
    private final ConnectionDisposer externalClosedDisposer;
    private final int maximumPoolSize;
    private final long connectionTimeoutMillis;
    private final long maxLifetimeMillis;
    private final boolean reuseIdleConnections;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition stateChanged = stateLock.newCondition();
    private final Deque<Slot> idle = new ArrayDeque<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<Slot> pendingDisposals = new ArrayList<>();
    private final Map<Connection, Slot> physicalSlots = new IdentityHashMap<>();
    private int creating;
    private boolean closed;

    StrictSqlConnectionPool(
            ConnectionFactory factory,
            ConnectionDisposer managedDisposer,
            ConnectionDisposer externalReturnDisposer,
            ConnectionDisposer externalAbandonDisposer,
            ConnectionDisposer externalClosedDisposer,
            int maximumPoolSize,
            long connectionTimeoutMillis,
            long maxLifetimeMillis,
            boolean reuseIdleConnections) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.managedDisposer = Objects.requireNonNull(managedDisposer, "managedDisposer");
        this.externalReturnDisposer = Objects.requireNonNull(externalReturnDisposer, "externalReturnDisposer");
        this.externalAbandonDisposer = Objects.requireNonNull(externalAbandonDisposer, "externalAbandonDisposer");
        this.externalClosedDisposer = Objects.requireNonNull(externalClosedDisposer, "externalClosedDisposer");
        if (maximumPoolSize <= 0) {
            throw new IllegalArgumentException("maximumPoolSize must be positive");
        }
        if (connectionTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectionTimeoutMillis must be positive");
        }
        if (maxLifetimeMillis <= 0) {
            throw new IllegalArgumentException("maxLifetimeMillis must be positive");
        }
        this.maximumPoolSize = maximumPoolSize;
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.maxLifetimeMillis = maxLifetimeMillis;
        this.reuseIdleConnections = reuseIdleConnections;
    }

    static int defaultPoolSize() {
        return DEFAULT_POOL_SIZE;
    }

    static long defaultTimeoutMillis() {
        return DEFAULT_TIMEOUT_MILLIS;
    }

    static long defaultMaxLifetimeMillis() {
        return DEFAULT_MAX_LIFETIME_MILLIS;
    }

    Connection borrow() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMillis);
        for (;;) {
            Slot expired = null;
            boolean reserveCreation = false;
            stateLock.lock();
            try {
                ensureOpen();
                while (reuseIdleConnections && !idle.isEmpty()) {
                    Slot candidate = idle.removeFirst();
                    if (isExpired(candidate, System.nanoTime()) || isClosed(candidate.physical)) {
                        removeSlotLocked(candidate);
                        expired = candidate;
                        break;
                    }
                    BorrowHandle handle = new BorrowHandle(candidate);
                    candidate.active = handle;
                    return handle.proxy;
                }
                if (expired == null && slots.size() + creating < maximumPoolSize) {
                    creating++;
                    reserveCreation = true;
                }
                if (!reserveCreation && expired == null) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new SQLTimeoutException("Timed out waiting for a SQL connection");
                    }
                    try {
                        stateChanged.awaitNanos(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted while waiting for a SQL connection", interrupted);
                    }
                }
            } finally {
                stateLock.unlock();
            }

            if (expired != null) {
                try {
                    disposeSlot(expired);
                } catch (Throwable disposalFailure) {
                    rethrow(disposalFailure, "Failed to recycle expired SQL connection");
                }
                continue;
            }
            if (reserveCreation) {
                return createSlot();
            }
        }
    }

    void returnConnection(Connection connection) throws SQLException {
        BorrowHandle handle = handleFor(connection);
        if (handle != null) {
            release(handle, false);
            return;
        }
        Slot slot = physicalSlotFor(connection);
        if (slot != null && slot.active != null) {
            release(slot.active, false);
            return;
        }
        disposeExternal(connection, isClosed() ? externalClosedDisposer : externalReturnDisposer);
    }

    void abandonConnection(Connection connection) throws SQLException {
        BorrowHandle handle = handleFor(connection);
        if (handle != null) {
            release(handle, true);
            return;
        }
        Slot slot = physicalSlotFor(connection);
        if (slot != null && slot.active != null) {
            release(slot.active, true);
            return;
        }
        disposeExternal(connection, isClosed() ? externalClosedDisposer : externalAbandonDisposer);
    }

    void close() throws SQLException {
        List<Slot> toDispose;
        stateLock.lock();
        try {
            if (closed && pendingDisposals.isEmpty()) {
                return;
            }
            closed = true;
            toDispose = new ArrayList<>(slots);
            for (Slot pending : pendingDisposals) {
                if (!toDispose.contains(pending)) {
                    toDispose.add(pending);
                }
            }
            idle.clear();
            slots.clear();
            physicalSlots.clear();
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }

        Throwable failure = null;
        for (Slot slot : toDispose) {
            try {
                disposeSlot(slot);
            } catch (Throwable disposalFailure) {
                failure = appendFailure(failure, disposalFailure);
            }
        }
        rethrow(failure, "Failed to close strict SQL connection pool");
    }

    private Connection createSlot() throws SQLException {
        Connection physical = null;
        Slot createdSlot = null;
        boolean creationReserved = true;
        try {
            physical = factory.open();
            if (physical == null) {
                throw new SQLException("SQL connection factory returned null");
            }
            ConnectionState baseline = ConnectionState.capture(physical);
            Slot slot = new Slot(physical, baseline, System.nanoTime());
            createdSlot = slot;
            stateLock.lock();
            try {
                creating--;
                creationReserved = false;
                if (closed) {
                    stateChanged.signalAll();
                } else {
                    slots.add(slot);
                    physicalSlots.put(physical, slot);
                    BorrowHandle handle = new BorrowHandle(slot);
                    slot.active = handle;
                    stateChanged.signalAll();
                    return handle.proxy;
                }
            } finally {
                stateLock.unlock();
            }
            disposeSlot(slot);
            throw new SQLException("SQL connection pool was closed while opening a connection");
        } catch (Throwable failure) {
            if (creationReserved) {
                stateLock.lock();
                try {
                    creating--;
                    stateChanged.signalAll();
                } finally {
                    stateLock.unlock();
                }
            }
            if (physical != null && createdSlot == null) {
                try {
                    disposeExternal(physical, externalReturnDisposer);
                } catch (Throwable disposalFailure) {
                    failure.addSuppressed(disposalFailure);
                }
            }
            rethrow(failure, "Failed to open SQL connection");
            throw new AssertionError("unreachable");
        }
    }

    private void release(BorrowHandle handle, boolean unsafe) throws SQLException {
        if (!handle.released.compareAndSet(false, true)) {
            return;
        }
        Slot slot = handle.slot;
        stateLock.lock();
        try {
            if (slot.active == handle) {
                slot.active = null;
            }
            if (!slots.contains(slot)) {
                stateChanged.signalAll();
            }
        } finally {
            stateLock.unlock();
        }

        Throwable failure = null;
        slot.lifecycle.lock();
        try {
            if (slot.disposed.get()) {
                return;
            }
            if (unsafe || isClosed() || !reuseIdleConnections) {
                removeSlot(slot);
                ConnectionDisposer disposer = unsafe || isClosed()
                        ? externalAbandonDisposer
                        : externalReturnDisposer;
                disposeSlotLocked(slot, disposer);
                return;
            }
            try {
                slot.baseline.reset(slot.physical);
            } catch (Throwable resetFailure) {
                removeSlot(slot);
                try {
                    disposeSlotLocked(slot, managedDisposer);
                } catch (Throwable disposalFailure) {
                    resetFailure.addSuppressed(disposalFailure);
                }
                failure = resetFailure;
            }
            if (failure == null) {
                stateLock.lock();
                try {
                    if (closed || slot.disposed.get() || !slots.contains(slot)) {
                        // Shutdown won the race while reset was running.
                    } else {
                        idle.addLast(slot);
                        stateChanged.signal();
                        return;
                    }
                } finally {
                    stateLock.unlock();
                }
                disposeSlotLocked(slot, managedDisposer);
            }
        } finally {
            slot.lifecycle.unlock();
        }
        rethrow(failure, "Failed to release SQL connection");
    }

    private BorrowHandle handleFor(Connection connection) {
        if (connection == null || !Proxy.isProxyClass(connection.getClass())) {
            return null;
        }
        InvocationHandler handler = Proxy.getInvocationHandler(connection);
        if (handler instanceof BorrowHandle candidate && candidate.pool == this) {
            return candidate;
        }
        return null;
    }

    private Slot physicalSlotFor(Connection connection) {
        stateLock.lock();
        try {
            return physicalSlots.get(connection);
        } finally {
            stateLock.unlock();
        }
    }

    private void removeSlot(Slot slot) {
        stateLock.lock();
        try {
            removeSlotLocked(slot);
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private void removeSlotLocked(Slot slot) {
        idle.remove(slot);
        slots.remove(slot);
        physicalSlots.remove(slot.physical);
    }

    private boolean containsPhysical(Connection physical) {
        stateLock.lock();
        try {
            return physicalSlots.containsKey(physical);
        } finally {
            stateLock.unlock();
        }
    }

    private void disposeExternal(Connection connection, ConnectionDisposer disposer) throws SQLException {
        Slot pending = pendingSlotFor(connection, disposer);
        pending.lifecycle.lock();
        try {
            disposeSlotLocked(pending, pending.pendingDisposer);
        } finally {
            pending.lifecycle.unlock();
        }
    }

    private Slot pendingSlotFor(Connection connection, ConnectionDisposer disposer) {
        stateLock.lock();
        try {
            for (Slot pending : pendingDisposals) {
                if (pending.physical == connection) {
                    return pending;
                }
            }
            Slot pending = new Slot(connection, null, System.nanoTime());
            pending.closed = true;
            pending.pendingDisposer = disposer;
            pendingDisposals.add(pending);
            stateChanged.signalAll();
            return pending;
        } finally {
            stateLock.unlock();
        }
    }

    private void disposeSlot(Slot slot) throws SQLException {
        slot.lifecycle.lock();
        try {
            ConnectionDisposer disposer = slot.pendingDisposer == null
                    ? managedDisposer
                    : slot.pendingDisposer;
            disposeSlotLocked(slot, disposer);
        } finally {
            slot.lifecycle.unlock();
        }
    }

    private void disposeSlotLocked(Slot slot) throws SQLException {
        disposeSlotLocked(slot, managedDisposer);
    }

    private void disposeSlotLocked(Slot slot, ConnectionDisposer disposer) throws SQLException {
        if (slot.disposed.get()) {
            return;
        }
        slot.closed = true;
        try {
            disposer.dispose(slot.physical);
        } catch (Throwable failure) {
            slot.pendingDisposer = disposer;
            trackPendingDisposal(slot);
            rethrow(failure, "Failed to dispose managed SQL connection");
        }
        slot.pendingDisposer = null;
        slot.disposed.set(true);
        removePendingDisposal(slot);
    }

    private void trackPendingDisposal(Slot slot) {
        stateLock.lock();
        try {
            if (!pendingDisposals.contains(slot)) {
                pendingDisposals.add(slot);
            }
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private void removePendingDisposal(Slot slot) {
        stateLock.lock();
        try {
            pendingDisposals.remove(slot);
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private boolean isExpired(Slot slot, long now) {
        return now - slot.createdAt >= TimeUnit.MILLISECONDS.toNanos(maxLifetimeMillis);
    }

    private static boolean isClosed(Connection connection) {
        try {
            return connection.isClosed();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isClosed() {
        stateLock.lock();
        try {
            return closed;
        } finally {
            stateLock.unlock();
        }
    }

    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("SQL connection pool is closed");
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
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

    private final class Slot {
        private final Connection physical;
        private final ConnectionState baseline;
        private final long createdAt;
        private final ReentrantLock lifecycle = new ReentrantLock();
        private final AtomicBoolean disposed = new AtomicBoolean();
        private volatile BorrowHandle active;
        private volatile boolean closed;
        private ConnectionDisposer pendingDisposer;

        private Slot(Connection physical, ConnectionState baseline, long createdAt) {
            this.physical = physical;
            this.baseline = baseline;
            this.createdAt = createdAt;
        }
    }

    private final class BorrowHandle implements InvocationHandler {
        private final StrictSqlConnectionPool pool = StrictSqlConnectionPool.this;
        private final Slot slot;
        private final AtomicBoolean released = new AtomicBoolean();
        private final Connection proxy;

        private BorrowHandle(Slot slot) {
            this.slot = slot;
            this.proxy = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    this);
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> ignored == arguments[0];
                    case "hashCode" -> System.identityHashCode(ignored);
                    case "toString" -> "StrictSqlConnection[" + Integer.toHexString(System.identityHashCode(ignored)) + "]";
                    default -> method.invoke(this, arguments);
                };
            }
            if (name.equals("close") && method.getParameterCount() == 0) {
                release(this, false);
                return null;
            }
            if (name.equals("isClosed") && method.getParameterCount() == 0) {
                return released.get() || slot.closed || slot.physical.isClosed();
            }
            if (released.get() || slot.closed) {
                throw new SQLException("SQL connection is closed");
            }
            if (name.equals("unwrap") && arguments != null && arguments.length == 1) {
                Class<?> requested = (Class<?>) arguments[0];
                if (requested == null) {
                    throw new SQLException("Wrapper interface must not be null");
                }
                if (requested.isInstance(proxy)) {
                    return requested.cast(proxy);
                }
            }
            if (name.equals("isWrapperFor") && arguments != null && arguments.length == 1) {
                Class<?> requested = (Class<?>) arguments[0];
                if (requested == null) {
                    throw new SQLException("Wrapper interface must not be null");
                }
                if (requested.isInstance(proxy)) {
                    return true;
                }
            }
            try {
                return method.invoke(slot.physical, arguments);
            } catch (InvocationTargetException invocationFailure) {
                throw invocationFailure.getCause();
            }
        }
    }

    private static final class ConnectionState {
        private final boolean autoCommit;
        private final Setting<Boolean> readOnly;
        private final Setting<Integer> isolation;
        private final Setting<String> catalog;
        private final Setting<String> schema;
        private final Setting<Integer> holdability;
        private final Setting<Integer> networkTimeout;

        private ConnectionState(
                boolean autoCommit,
                Setting<Boolean> readOnly,
                Setting<Integer> isolation,
                Setting<String> catalog,
                Setting<String> schema,
                Setting<Integer> holdability,
                Setting<Integer> networkTimeout) {
            this.autoCommit = autoCommit;
            this.readOnly = readOnly;
            this.isolation = isolation;
            this.catalog = catalog;
            this.schema = schema;
            this.holdability = holdability;
            this.networkTimeout = networkTimeout;
        }

        private static ConnectionState capture(Connection connection) throws SQLException {
            return new ConnectionState(
                    connection.getAutoCommit(),
                    captureOptional(connection::isReadOnly),
                    captureOptional(connection::getTransactionIsolation),
                    captureOptional(connection::getCatalog),
                    captureOptional(connection::getSchema),
                    captureOptional(connection::getHoldability),
                    captureOptional(connection::getNetworkTimeout));
        }

        private void reset(Connection connection) throws SQLException {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            restore(connection, readOnly, connection.isReadOnly(), connection::setReadOnly);
            restore(connection, isolation, connection.getTransactionIsolation(), connection::setTransactionIsolation);
            restore(connection, catalog, connection.getCatalog(), connection::setCatalog);
            restore(connection, schema, connection.getSchema(), connection::setSchema);
            restore(connection, holdability, connection.getHoldability(), connection::setHoldability);
            if (networkTimeout.supported) {
                int current = connection.getNetworkTimeout();
                if (!Objects.equals(current, networkTimeout.value)) {
                    connection.setNetworkTimeout(Runnable::run, networkTimeout.value);
                }
            }
            if (connection.getAutoCommit() != autoCommit) {
                connection.setAutoCommit(autoCommit);
            }
            connection.clearWarnings();
        }

        private static <T> Setting<T> captureOptional(CheckedSupplier<T> supplier) throws SQLException {
            try {
                return new Setting<>(true, supplier.get());
            } catch (SQLFeatureNotSupportedException unsupported) {
                return new Setting<>(false, null);
            }
        }

        private static <T> void restore(
                Connection connection,
                Setting<T> baseline,
                T current,
                CheckedConsumer<T> setter) throws SQLException {
            if (baseline.supported && !Objects.equals(current, baseline.value)) {
                setter.accept(baseline.value);
            }
        }

        @FunctionalInterface
        private interface CheckedSupplier<T> {
            T get() throws SQLException;
        }

        @FunctionalInterface
        private interface CheckedConsumer<T> {
            void accept(T value) throws SQLException;
        }
    }

    private static final class Setting<T> {
        private final boolean supported;
        private final T value;

        private Setting(boolean supported, T value) {
            this.supported = supported;
            this.value = value;
        }
    }
}
