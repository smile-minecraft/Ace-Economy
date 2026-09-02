package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

final class SqlReturnConnectionFailClosedRegressionTest {

    static {
        try { Class.forName("org.sqlite.JDBC"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---- returnConnection: close fails, eviction fails -> abort, diagnostics preserved ----

    @Test
    void returnConnectionCloseAndEvictionFailureAttemptsAbortAndPreservesDiagnostics() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("simulated close failure");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("simulated eviction failure")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);

        SQLException thrown = assertThrows(SQLException.class, () -> provider.returnConnection(tracking));
        assertEquals("simulated close failure", thrown.getMessage(), "primary must be close failure");
        verify(hikari).evictConnection(tracking);
        assertTrue(tracking.abortCalled, "fail-closed must attempt abort when close and eviction both fail");
        assertEquals(1, tracking.closeCount, "poisoned connection must not be ordinary-closed a second time after abort");
        boolean foundEvict = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
        }
        assertTrue(foundEvict, "eviction failure must be on suppressed chain");
    }

    @Test
    void returnConnectionCloseAndEvictionFailureAbortFailureIsSuppressed() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("close-boom");
        tracking.failAbortWith = new SQLException("abort-boom");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-boom")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);

        SQLException thrown = assertThrows(SQLException.class, () -> provider.returnConnection(tracking));
        assertEquals("close-boom", thrown.getMessage());
        assertTrue(tracking.abortCalled);
        boolean foundEvict = false, foundAbort = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
            if (t.getMessage() != null && t.getMessage().contains("abort-boom")) foundAbort = true;
        }
        assertTrue(foundEvict, "eviction diagnostic must be preserved");
        assertTrue(foundAbort, "abort diagnostic must be preserved");
    }

    @Test
    void borrowedConnectionSafeClosePropagatesAbortDiagnostics() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("close-fail");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        SQLException thrown = assertThrows(SQLException.class, borrowed::close);
        assertTrue(tracking.abortCalled);
        assertEquals("close-fail", thrown.getMessage());
    }

    @Test
    void borrowedConnectionSafeCloseWithPrimaryAttachesDiagnosticsToPrimary() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("close-fail");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        SQLException primary = new SQLException("primary-op");
        borrowed.close(primary);
        assertTrue(tracking.abortCalled);
        boolean found = false;
        for (Throwable t : primary.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("close-fail")) found = true;
        }
        assertTrue(found, "release failure must be suppressed on primary");
        Throwable release = primary.getSuppressed()[0];
        boolean evictNested = false;
        for (Throwable t : release.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) evictNested = true;
        }
        assertTrue(evictNested);
    }

    // ---- abandon: successful eviction must NOT ordinary-close the supplied connection ----

    @Test
    void returnEvictionSuccessDoesNotOrdinaryCloseDirtyHikariProxy() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("ordinary-close-must-not-run");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doNothing().when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        try {
            assertDoesNotThrow(() -> provider.returnConnection(tracking));
            assertEquals(0, tracking.closeCount,
                    "successful Hikari eviction must own disposal without ordinary close");
            assertFalse(tracking.abortCalled, "abort is not needed when eviction succeeds");
            verify(hikari).evictConnection(tracking);
        } finally {
            tracking.delegate.close();
        }
    }

    @Test
    void hikariSlotCreationFailureEvictsWithoutOrdinaryClose() throws Exception {
        Connection physical = mock(Connection.class);
        SQLException captureFailure = new SQLException("connection-state-capture-failure");
        when(physical.getAutoCommit()).thenThrow(captureFailure);
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(physical);
        doNothing().when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari, 1, 2_000L, 60_000L);

        try {
            SQLException thrown = assertThrows(SQLException.class, provider::borrow);
            assertSame(captureFailure, thrown, "slot creation must preserve the capture failure");
            verify(hikari).evictConnection(physical);
            verify(physical, never()).close();
        } finally {
            provider.close();
        }
    }

    @Test
    void abandonEvictionSuccessDoesNotOrdinaryCloseConnection() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        HikariDataSource hikari = mock(HikariDataSource.class);
        doNothing().when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        provider.abandonConnection(tracking);
        assertEquals(0, tracking.closeCount, "successful eviction must own disposal without ordinary close");
        assertFalse(tracking.abortCalled, "abort not needed when eviction succeeded");
        verify(hikari).evictConnection(tracking);
    }

    @Test
    void abandonEvictionFailureAttemptsAbortAndDoesNotOrdinaryClose() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-boom")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.abandonConnection(tracking));
        assertTrue(thrown.getMessage().contains("Failed to evict"));
        assertEquals(0, tracking.closeCount, "fail-closed must not ordinary-close after eviction failure");
        assertTrue(tracking.abortCalled);
    }

    // ---- non-Hikari unsafe: close failure must fallback to abort ----

    @Test
    void nonHikariAbandonCloseFailureAttemptsAbortAndPreservesDiagnostics() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("non-hikari-close-boom");
        DataSource plain = new SingleConnDataSource(tracking);
        SqlConnectionProvider provider = new SqlConnectionProvider(plain);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.abandonConnection(tracking));
        assertEquals("non-hikari-close-boom", thrown.getMessage(), "primary must remain close failure");
        assertTrue(tracking.abortCalled, "non-Hikari must attempt abort when close fails");
        assertEquals(1, tracking.closeCount);
    }

    @Test
    void nonHikariAbandonCloseAndAbortFailureBothSuppressed() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("close-boom");
        tracking.failAbortWith = new SQLException("abort-boom");
        DataSource plain = new SingleConnDataSource(tracking);
        SqlConnectionProvider provider = new SqlConnectionProvider(plain);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.abandonConnection(tracking));
        assertEquals("close-boom", thrown.getMessage());
        boolean foundAbort = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("abort-boom")) foundAbort = true;
        }
        assertTrue(foundAbort, "abort diagnostic must be suppressed on close primary");
    }

    @Test
    void nonHikariReturnCloseFailureAttemptsAbort() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("return-close-boom");
        DataSource plain = mock(DataSource.class);
        SqlConnectionProvider provider = new SqlConnectionProvider(plain);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.returnConnection(tracking));
        assertEquals("return-close-boom", thrown.getMessage());
        assertTrue(tracking.abortCalled, "non-Hikari return close failure must attempt abort");
    }

    // ---- unchecked cleanup failures are preserved ----

    @Test
    void returnConnectionUncheckedCloseFailurePreservesEvictAndAbortDiagnostics() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWithRuntime = new RuntimeException("unchecked-close-boom");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-boom")).when(hikari).evictConnection(any());
        tracking.failAbortWith = new SQLException("abort-boom");
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> provider.returnConnection(tracking));
        assertEquals("unchecked-close-boom", thrown.getMessage());
        boolean foundEvict = false, foundAbort = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
            if (t.getMessage() != null && t.getMessage().contains("abort-boom")) foundAbort = true;
        }
        assertTrue(foundEvict, "unchecked close primary must still have evict diagnostic suppressed");
        assertTrue(foundAbort, "abort diagnostic must be preserved even with unchecked primary");
    }

    @Test
    void abandonUncheckedEvictionFailurePreservesAbortDiagnostic() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failAbortWithRuntime = new RuntimeException("unchecked-abort-boom");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-boom")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.abandonConnection(tracking));
        boolean foundAbort = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (containsDeep(t, "unchecked-abort-boom")) foundAbort = true;
        }
        assertTrue(foundAbort, "unchecked abort diagnostic must be preserved");
        assertEquals(0, tracking.closeCount);
    }

    @Test
    void returnConnectionErrorCloseFailurePreservesDiagnostics() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWithError = new AssertionError("error-close-boom");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-boom")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        AssertionError thrown = assertThrows(AssertionError.class, () -> provider.returnConnection(tracking));
        assertEquals("error-close-boom", thrown.getMessage());
        boolean foundEvict = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
        }
        assertTrue(foundEvict);
        assertTrue(tracking.abortCalled, "Error cleanup must still attempt abort after eviction failure");
        // error must not be converted to SQLException
        assertTrue(thrown instanceof Error, "Error failure must remain Error type");
    }

    private static boolean containsDeep(Throwable t, String needle) {
        Throwable cur = t;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && m.contains(needle)) return true;
            for (Throwable sup : cur.getSuppressed()) {
                if (containsDeep(sup, needle)) return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Test
    void borrowedConnectionUncheckedReleaseFailureSuppressedOnPrimary() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWithRuntime = new RuntimeException("unchecked-close");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        SQLException primary = new SQLException("primary-op");
        borrowed.close(primary);
        boolean found = false;
        for (Throwable t : primary.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("unchecked-close")) found = true;
        }
        assertTrue(found, "unchecked release failure must be suppressed on primary");
        // primary must remain the thrown type conceptually (no exception thrown when primary present)
        assertEquals("primary-op", primary.getMessage(), "primary message must be unchanged");
    }

    @Test
    void borrowedConnectionUncheckedReleaseFailurePropagatedWhenNoPrimary() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWithRuntime = new RuntimeException("unchecked-close-no-primary");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        RuntimeException thrown = assertThrows(RuntimeException.class, borrowed::close);
        assertEquals("unchecked-close-no-primary", thrown.getMessage());
        boolean foundEvict = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
        }
        assertTrue(foundEvict);
    }

    // ---- BorrowedConnection direct coverage: idempotence & Error ----

    @Test
    void borrowedConnectionSafeSecondCloseIsNoOp() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        HikariDataSource hikari = mock(HikariDataSource.class);
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        borrowed.close();
        assertEquals(0, tracking.closeCount, "successful Hikari eviction must not ordinary-close");
        verify(hikari, times(1)).evictConnection(tracking);
        borrowed.close();
        assertEquals(0, tracking.closeCount, "second close must be idempotent and not delegate again");
        // also verify with primary present, second close remains no-op
        borrowed.close(new SQLException("primary-ignored-due-to-closed"));
        assertEquals(0, tracking.closeCount, "closed borrow must ignore primary on second close");
        tracking.delegate.close();
    }

    @Test
    void borrowedConnectionUnsafeSecondCloseIsNoOp() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        HikariDataSource hikari = mock(HikariDataSource.class);
        doNothing().when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        borrowed.markUnsafe();
        borrowed.close();
        verify(hikari, times(1)).evictConnection(tracking);
        borrowed.close();
        verify(hikari, times(1)).evictConnection(tracking);
        assertEquals(0, tracking.closeCount, "unsafe abandon must not ordinary-close");
    }

    @Test
    void strictBorrowedProxyHonorsWrapperContractAndClosedState() throws Exception {
        Connection physical = mock(Connection.class);
        DriverConnection driverConnection = mock(DriverConnection.class);
        when(physical.getAutoCommit()).thenReturn(true);
        when(physical.isReadOnly()).thenReturn(false);
        when(physical.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(physical.getNetworkTimeout()).thenReturn(0);
        when(physical.unwrap(DriverConnection.class)).thenReturn(driverConnection);
        when(physical.isWrapperFor(DriverConnection.class)).thenReturn(true);

        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(physical);
        SqlConnectionProvider provider = new SqlConnectionProvider(dataSource, 1, 2_000L, 60_000L);
        Connection borrowed = provider.borrow();

        assertSame(borrowed, borrowed.unwrap(Connection.class),
                "the strict proxy must unwrap itself as Connection");
        assertTrue(borrowed.isWrapperFor(Connection.class),
                "the strict proxy must report itself as a Connection wrapper");
        assertTrue(borrowed.isWrapperFor(DriverConnection.class),
                "driver wrapper support must be delegated");
        assertSame(driverConnection, borrowed.unwrap(DriverConnection.class),
                "driver unwrap must be delegated");

        borrowed.close();
        assertTrue(borrowed.isClosed(), "a released proxy must report closed");
        assertThrows(SQLException.class, () -> borrowed.unwrap(DriverConnection.class));
        assertThrows(SQLException.class, () -> borrowed.isWrapperFor(DriverConnection.class));
        assertDoesNotThrow(borrowed::close, "double-close must remain idempotent");
        provider.close();
    }

    @Test
    void borrowedConnectionNoPrimaryCleanupErrorIsPropagatedAsError() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWithError = new AssertionError("error-no-primary");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        AssertionError thrown = assertThrows(AssertionError.class, borrowed::close);
        assertEquals("error-no-primary", thrown.getMessage());
        boolean foundEvict = false;
        for (Throwable t : thrown.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
        }
        assertTrue(foundEvict, "Error primary must still carry evict diagnostic as suppressed");
    }

    @Test
    void borrowedConnectionWithPrimaryCleanupErrorIsSuppressedNotReplacingPrimary() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWithError = new AssertionError("error-cleanup");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        SQLException primary = new SQLException("primary-op");
        borrowed.close(primary);
        // primary must remain primary, error must be suppressed on its cause chain
        assertEquals("primary-op", primary.getMessage());
        boolean foundError = false;
        for (Throwable t : primary.getSuppressed()) {
            if (t instanceof AssertionError ae && "error-cleanup".equals(ae.getMessage())) foundError = true;
        }
        assertTrue(foundError, "cleanup Error must be suppressed on primary, not replace it");
        // BorrowedConnection must have captured the error as suppressed failure, not thrown
        assertDoesNotThrow(() -> borrowed.close(), "second close after error must be no-op");
    }

    @Test
    void borrowedConnectionWithPrimaryCleanupFailureIsSuppressedOnPrimaryCauseWhenPersistenceException() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("close-fail-for-nested");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        SQLException cause = new SQLException("sql-cause");
        com.smile.aceeconomy.ports.persistence.PersistenceException primary =
                new com.smile.aceeconomy.ports.persistence.PersistenceException("op-failed", cause);
        borrowed.close(primary);
        // When primary is PersistenceException with cause, suppression goes to cause
        boolean foundOnCause = false, foundOnWrapper = false;
        for (Throwable t : cause.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("close-fail-for-nested")) foundOnCause = true;
        }
        for (Throwable t : primary.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("close-fail-for-nested")) foundOnWrapper = true;
        }
        assertTrue(foundOnCause, "release failure must be suppressed on PersistenceException cause");
        assertFalse(foundOnWrapper, "release failure must not be suppressed on wrapper when cause present");
    }

    // ---- primary suppression stays primary (checked) ----

    @Test
    void returnConnectionDoubleFailurePrimaryRemainsCloseFailure() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failCloseWith = new SQLException("primary-close");
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("evict-fail")).when(hikari).evictConnection(any());
        tracking.failAbortWith = new SQLException("abort-fail");
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.returnConnection(tracking));
        assertEquals("primary-close", thrown.getMessage(), "primary close failure must remain primary");
        assertTrue(thrown.getSuppressed().length >= 2, "both evict and abort must be suppressed, not replace primary");
        // non-tautological: verify neither suppressed equals primary
        for (Throwable sup : thrown.getSuppressed()) {
            assertNotEquals(thrown.getMessage(), sup.getMessage(), "suppressed must not equal primary");
        }
    }

    @Test
    void unsafeDisposalFailureIsRetriedByProviderCloseExactlyOnce() throws Exception {
        Connection real = DriverManager.getConnection("jdbc:sqlite::memory:");
        CountingConnection physical = new CountingConnection(real);
        physical.failCloseWith = new SQLException("first disposal close failure");
        physical.failAbortWith = new SQLException("first disposal abort failure");
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(physical);
        SqlConnectionProvider provider = new SqlConnectionProvider(dataSource, 1, 2_000L, 60_000L);
        try {
            Connection borrowed = provider.borrow();
            assertThrows(SQLException.class, () -> provider.abandonConnection(borrowed));
            assertEquals(1, physical.closeCount);
            assertEquals(1, physical.abortCount);

            physical.failCloseWith = null;
            physical.failAbortWith = null;
            assertDoesNotThrow(provider::close);
            assertEquals(2, physical.closeCount,
                    "provider.close must retry the failed unsafe disposal");
            assertEquals(1, physical.abortCount,
                    "the successful close-then-abort retry must not abort twice");

            assertDoesNotThrow(provider::close);
            assertEquals(2, physical.closeCount, "successful retry must be idempotent");
            assertEquals(1, physical.abortCount, "successful retry must be idempotent");
        } finally {
            real.close();
        }
    }

    @Test
    void hikariUnsafeDisposalFailureIsRetriedWithoutDoubleDispose() throws Exception {
        CountingConnection physical = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        physical.failAbortWith = new SQLException("first abort failure");
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getConnection()).thenReturn(physical);
        doThrow(new RuntimeException("first eviction failure"))
                .doNothing().when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari, 1, 2_000L, 60_000L);
        try {
            Connection borrowed = provider.borrow();
            assertThrows(SQLException.class, () -> provider.abandonConnection(borrowed));
            assertTrue(physical.abortCalled);
            assertEquals(0, physical.closeCount,
                    "failed Hikari eviction must not ordinary-close the dirty proxy");

            physical.failAbortWith = null;
            assertDoesNotThrow(provider::close);
            verify(hikari, times(2)).evictConnection(physical);
            assertEquals(0, physical.closeCount,
                    "successful retry eviction must not ordinary-close the proxy");

            assertDoesNotThrow(provider::close);
            verify(hikari, times(2)).evictConnection(physical);
        } finally {
            physical.delegate.close();
        }
    }

    @Test
    void externalDisposalFailureIsRetriedByProviderCloseExactlyOnce() throws Exception {
        Connection real = DriverManager.getConnection("jdbc:sqlite::memory:");
        CountingConnection physical = new CountingConnection(real);
        physical.failAbortWith = new SQLException("first external abort failure");
        physical.failCloseWith = new SQLException("first external close failure");
        DataSource dataSource = mock(DataSource.class);
        SqlConnectionProvider provider = new SqlConnectionProvider(dataSource, 1, 2_000L, 60_000L);
        try {
            assertThrows(SQLException.class, () -> provider.returnConnection(physical));
            assertEquals(1, physical.closeCount);
            assertEquals(1, physical.abortCount);

            physical.failAbortWith = null;
            physical.failCloseWith = null;
            assertDoesNotThrow(provider::close);
            assertEquals(2, physical.closeCount,
                    "provider.close must retry an external disposal failure");
            assertEquals(1, physical.abortCount,
                    "a successful close-first retry must not abort twice");

            assertDoesNotThrow(provider::close);
            assertEquals(2, physical.closeCount, "successful retry must be idempotent");
            assertEquals(1, physical.abortCount, "successful retry must be idempotent");
        } finally {
            real.close();
        }
    }

    @Test
    void resetDisposalFailureIsRetriedByProviderCloseExactlyOnce() throws Exception {
        Connection real = DriverManager.getConnection("jdbc:sqlite::memory:");
        CountingConnection physical = new CountingConnection(real);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(physical);
        SqlConnectionProvider provider = new SqlConnectionProvider(dataSource, 1, 2_000L, 60_000L);
        try {
            Connection borrowed = provider.borrow();
            borrowed.setAutoCommit(false);
            physical.failOnRollback = true;
            physical.failCloseWith = new SQLException("first disposal close failure");
            physical.failAbortWith = new SQLException("first disposal abort failure");

            SQLException resetFailure = assertThrows(SQLException.class,
                    () -> provider.returnConnection(borrowed));
            assertEquals("simulated reset rollback failure", resetFailure.getMessage());
            assertEquals(1, physical.closeCount);
            assertEquals(1, physical.abortCount);

            physical.failOnRollback = false;
            physical.failCloseWith = null;
            physical.failAbortWith = null;
            assertDoesNotThrow(provider::close);
            assertEquals(2, physical.closeCount,
                    "provider.close must retry disposal after reset failure");
            assertEquals(2, physical.abortCount,
                    "provider.close must retry the managed disposer");

            assertDoesNotThrow(provider::close);
            assertEquals(2, physical.closeCount, "successful retry must be idempotent");
            assertEquals(2, physical.abortCount, "successful retry must be idempotent");
        } finally {
            real.close();
        }
    }

    // ---- DataSource close / borrow-after-close / return-after-close ----

    @Test
    void borrowAfterDataSourceCloseThrows() throws Exception {
        HikariDataSource hikari = mock(HikariDataSource.class);
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        provider.close();
        assertThrows(SQLException.class, provider::borrow, "borrow after close must throw");
        verify(hikari).close();
    }

    @Test
    void returnAfterDataSourceCloseAttemptsAbortAndDoesNotSwallowAbortFailure() throws Exception {
        CountingConnection tracking = new CountingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        tracking.failAbortWith = new SQLException("abort-after-close-boom");
        HikariDataSource hikari = mock(HikariDataSource.class);
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        provider.close();
        SQLException thrown = assertThrows(SQLException.class, () -> provider.returnConnection(tracking));
        assertTrue(thrown.getMessage().contains("abort-after-close-boom"), "abort failure after close must not be swallowed");
        assertTrue(tracking.abortCalled);
    }

    @Test
    void dataSourceCloseFailurePreservesDiagnosticsAndIsRetryable() throws Exception {
        CountingHikari ds = new CountingHikari();
        ds.failOnCloseOnce = true;
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) ds);
        SQLException first = assertThrows(SQLException.class, provider::close);
        assertTrue(first.getMessage().contains("Failed to close SQL data source"));
        assertNotNull(first.getCause(), "DataSource close failure must remain as the cause");
        assertEquals("simulated ds close failure", first.getCause().getMessage());
        assertEquals(1, ds.closeAttempts);
        ds.failOnCloseOnce = false;
        assertDoesNotThrow(provider::close);
        assertEquals(2, ds.closeAttempts);
        // borrow must still be blocked after successful close
        assertThrows(SQLException.class, provider::borrow);
    }

    // ---- pool saturation: borrower wait must not block return ----

    @Test
    void saturatedPoolBorrowDoesNotBlockReturn(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("saturated-pool.db");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + db);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(2500);
        cfg.setPoolName("saturation-test");
        cfg.setDriverClassName("org.sqlite.JDBC");
        HikariDataSource hikari = new HikariDataSource(cfg);
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(hikari);
            // Ensure pool initialized
            Connection init = provider.borrow();
            provider.returnConnection(init);
            Connection held = provider.borrow(); // occupies the sole slot
            CountDownLatch borrowerDone = new CountDownLatch(1);
            AtomicReference<Connection> borrowedByWaiter = new AtomicReference<>();
            AtomicReference<Throwable> waiterEx = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    Connection c = provider.borrow();
                    borrowedByWaiter.set(c);
                } catch (Throwable t) {
                    waiterEx.set(t);
                } finally {
                    borrowerDone.countDown();
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            awaitProviderWaiter(waiter);
            // Run return separately so a provider-lock deadlock becomes a bounded test failure.
            CountDownLatch returnDone = new CountDownLatch(1);
            AtomicReference<Throwable> returnEx = new AtomicReference<>();
            Thread returner = new Thread(() -> {
                try {
                    provider.returnConnection(held);
                } catch (Throwable t) {
                    returnEx.set(t);
                } finally {
                    returnDone.countDown();
                }
            });
            returner.setDaemon(true);
            returner.start();
            assertTrue(returnDone.await(2, TimeUnit.SECONDS),
                    "healthy return must complete while a borrower is queued");
            assertNull(returnEx.get(), "healthy return must not fail: " + returnEx.get());
            assertTrue(borrowerDone.await(3, TimeUnit.SECONDS), "waiter must acquire connection after return");
            assertNull(waiterEx.get(), "waiter borrow must succeed: " + waiterEx.get());
            assertNotNull(borrowedByWaiter.get(), "waiter must have borrowed a connection");
            // [VERIFY:P2] 驗證新連線可用
            try (var ps = borrowedByWaiter.get().prepareStatement("SELECT 1")) {
                assertTrue(ps.execute(), "fresh connection must be usable");
            }
            provider.returnConnection(borrowedByWaiter.get());
            provider.close();
        } finally {
            hikari.close();
        }
    }

    // ---- real Hikari close-failure + waiter: return must not deadlock and waiter gets fresh connection ----

    @Test
    void realHikariCloseFailureDoesNotBlockWaiterAndWaiterGetsFreshConnection(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("real-hikari-close-fail.db");
        // The first physical connection fails only when Hikari resets auto-commit on proxy close.
        OneShotFailingDataSource underlying = new OneShotFailingDataSource(db);
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(underlying);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(2500);
        cfg.setPoolName("real-fail-close-waiter");
        cfg.setInitializationFailTimeout(-1);
        cfg.setMaxLifetime(60_000);
        cfg.setConnectionTestQuery("SELECT 1");
        HikariDataSource hikari = new HikariDataSource(cfg);
        try {
            SqlConnectionProvider provider = new SqlConnectionProvider(hikari);
            // Warm pool without closing the same proxy through two ownership paths.
            Connection init = provider.borrow();
            provider.returnConnection(init);
            assertEquals(1, underlying.creationCount(),
                    "the first borrow must be the one-shot failing physical connection");

            // Make the first Hikari proxy dirty so its close invokes the underlying reset.
            Connection held = provider.borrow();
            held.setAutoCommit(false);
            assertFalse(held.getAutoCommit(), "held must be dirty before return");
            assertEquals(0, underlying.resetAttemptCount(), "reset must not happen before return");

            CountDownLatch waiterDone = new CountDownLatch(1);
            AtomicReference<Connection> waiterConn = new AtomicReference<>();
            AtomicReference<Throwable> waiterEx = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    Connection c = provider.borrow();
                    waiterConn.set(c);
                } catch (Throwable t) {
                    waiterEx.set(t);
                } finally {
                    waiterDone.countDown();
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            awaitProviderWaiter(waiter);

            // Return on a separate thread so a provider-lock deadlock becomes a bounded failure.
            CountDownLatch returnDone = new CountDownLatch(1);
            AtomicReference<Throwable> returnEx = new AtomicReference<>();
            Thread returner = new Thread(() -> {
                try {
                    provider.returnConnection(held);
                } catch (Throwable t) {
                    returnEx.set(t);
                } finally {
                    returnDone.countDown();
                }
            });
            returner.setDaemon(true);
            returner.start();
            assertTrue(returnDone.await(2, TimeUnit.SECONDS),
                    "failing return must complete while a borrower is queued");
            Throwable failure = returnEx.get();
            assertInstanceOf(SQLException.class, failure, "Hikari reset failure must be reported");
            SQLException thrown = (SQLException) failure;
            assertEquals("reset-failure: simulated setAutoCommit(true) failure", thrown.getMessage());
            assertEquals("42000", thrown.getSQLState(), "fixture must exercise an unclassified SQLState");
            assertEquals(1, underlying.resetAttemptCount(),
                    "the reset failure must occur during the first borrowed proxy close");

            assertTrue(waiterDone.await(3, TimeUnit.SECONDS), "waiter must acquire after failing return");
            assertNull(waiterEx.get(), "waiter must succeed not fail: " + waiterEx.get());
            assertNotNull(waiterConn.get(), "waiter must have connection");
            assertNotSame(held, waiterConn.get(), "waiter must not get the poisoned proxy");
            assertTrue(underlying.creationCount() >= 2,
                    "Hikari must create a replacement physical connection after eviction");
            // Fresh connection must be usable
            try (var ps = waiterConn.get().prepareStatement("SELECT 1");
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "fresh connection must be queryable");
            }
            provider.returnConnection(waiterConn.get());

            // A healthy return must recycle the replacement rather than create another physical connection.
            int creationsAfterReplacement = underlying.creationCount();
            Connection reuse = provider.borrow();
            assertNotNull(reuse);
            assertNotSame(held, reuse, "reused connection must not be the poisoned one");
            assertEquals(creationsAfterReplacement, underlying.creationCount(),
                    "healthy Hikari return must reuse the replacement physical connection");
            provider.returnConnection(reuse);
            provider.close();
        } finally {
            hikari.close();
        }
    }

    // ---- helpers ----

    private static void awaitProviderWaiter(Thread waiter) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (waiter.getState() != Thread.State.WAITING
                && waiter.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(waiter.getState() == Thread.State.WAITING
                        || waiter.getState() == Thread.State.TIMED_WAITING,
                "provider must report a borrower waiting; state=" + waiter.getState());
    }

    private static final class SingleConnDataSource implements DataSource {
        private final Connection c;
        SingleConnDataSource(Connection c) { this.c = c; }
        @Override public Connection getConnection() throws SQLException { return c; }
        @Override public Connection getConnection(String u, String p) throws SQLException { return c; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class CountingHikari extends HikariDataSource {
        int closeAttempts = 0;
        boolean failOnCloseOnce = false;
        @Override public void close() {
            closeAttempts++;
            if (failOnCloseOnce && closeAttempts == 1) throw new RuntimeException("simulated ds close failure");
            super.close();
        }
    }

    // Underlying DataSource that fails setAutoCommit(true) on first physical connection only
    private static final class OneShotFailingDataSource implements DataSource {
        private final Path file;
        private int count = 0;
        private int resetAttempts = 0;
        OneShotFailingDataSource(Path file) { this.file = file; }
        synchronized int creationCount() { return count; }
        synchronized int resetAttemptCount() { return resetAttempts; }
        private synchronized void recordResetAttempt() { resetAttempts++; }
        @Override public synchronized Connection getConnection() throws SQLException {
            count++;
            Connection real = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            if (count == 1) {
                return new ResetFailingConnection(real, this);
            }
            return real;
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class ResetFailingConnection implements Connection {
        private final Connection delegate;
        private final OneShotFailingDataSource owner;
        ResetFailingConnection(Connection d, OneShotFailingDataSource owner) {
            this.delegate = d;
            this.owner = owner;
        }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException {
            if (autoCommit) {
                owner.recordResetAttempt();
                throw new SQLException("reset-failure: simulated setAutoCommit(true) failure", "42000", 17002);
            }
            delegate.setAutoCommit(autoCommit);
        }
        @Override public void close() throws SQLException { delegate.close(); }
        @Override public void abort(Executor e) throws SQLException { try { delegate.abort(e); } catch (Exception ex) { delegate.close(); } }
        @Override public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
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
        @Override public java.sql.Statement createStatement(int a,int b) throws SQLException { return delegate.createStatement(a,b); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int b,int c) throws SQLException { return delegate.prepareStatement(a,b,c); }
        @Override public java.sql.CallableStatement prepareCall(String a,int b,int c) throws SQLException { return delegate.prepareCall(a,b,c); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(java.sql.Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(java.sql.Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public java.sql.Statement createStatement(int a,int b,int c) throws SQLException { return delegate.createStatement(a,b,c); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int b,int c,int d) throws SQLException { return delegate.prepareStatement(a,b,c,d); }
        @Override public java.sql.CallableStatement prepareCall(String a,int b,int c,int d) throws SQLException { return delegate.prepareCall(a,b,c,d); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,String[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int t) throws SQLException { return delegate.isValid(t); }
        @Override public void setClientInfo(String n,String v) throws java.sql.SQLClientInfoException { delegate.setClientInfo(n,v); }
        @Override public void setClientInfo(java.util.Properties p) throws java.sql.SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String a,Object[] b) throws SQLException { return delegate.createArrayOf(a,b); }
        @Override public java.sql.Struct createStruct(String a,Object[] b) throws SQLException { return delegate.createStruct(a,b); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void setNetworkTimeout(Executor e,int m) throws SQLException { delegate.setNetworkTimeout(e,m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    static class CountingConnection implements Connection {
        final Connection delegate;
        int closeCount = 0;
        int abortCount = 0;
        boolean abortCalled = false;
        boolean failOnRollback = false;
        SQLException failCloseWith = null;
        RuntimeException failCloseWithRuntime = null;
        Error failCloseWithError = null;
        SQLException failAbortWith = null;
        RuntimeException failAbortWithRuntime = null;

        CountingConnection(Connection d) { this.delegate = d; }

        @Override public void close() throws SQLException {
            closeCount++;
            if (failCloseWithError != null) throw failCloseWithError;
            if (failCloseWithRuntime != null) throw failCloseWithRuntime;
            if (failCloseWith != null) throw failCloseWith;
            delegate.close();
        }
        @Override public void abort(Executor e) throws SQLException {
            abortCount++;
            abortCalled = true;
            if (failAbortWithRuntime != null) throw failAbortWithRuntime;
            if (failAbortWith != null) throw failAbortWith;
            try { delegate.abort(e); } catch (Exception ex) { /* ignore */ }
        }
        @Override public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void setAutoCommit(boolean a) throws SQLException { delegate.setAutoCommit(a); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException {
            if (failOnRollback) throw new SQLException("simulated reset rollback failure");
            delegate.rollback();
        }
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
        @Override public java.sql.Statement createStatement(int a,int b) throws SQLException { return delegate.createStatement(a,b); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int b,int c) throws SQLException { return delegate.prepareStatement(a,b,c); }
        @Override public java.sql.CallableStatement prepareCall(String a,int b,int c) throws SQLException { return delegate.prepareCall(a,b,c); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(java.sql.Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(java.sql.Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public java.sql.Statement createStatement(int a,int b,int c) throws SQLException { return delegate.createStatement(a,b,c); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int b,int c,int d) throws SQLException { return delegate.prepareStatement(a,b,c,d); }
        @Override public java.sql.CallableStatement prepareCall(String a,int b,int c,int d) throws SQLException { return delegate.prepareCall(a,b,c,d); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,int[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public java.sql.PreparedStatement prepareStatement(String a,String[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int t) throws SQLException { return delegate.isValid(t); }
        @Override public void setClientInfo(String n,String v) throws java.sql.SQLClientInfoException { delegate.setClientInfo(n,v); }
        @Override public void setClientInfo(java.util.Properties p) throws java.sql.SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String a,Object[] b) throws SQLException { return delegate.createArrayOf(a,b); }
        @Override public java.sql.Struct createStruct(String a,Object[] b) throws SQLException { return delegate.createStruct(a,b); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void setNetworkTimeout(Executor e,int m) throws SQLException { delegate.setNetworkTimeout(e,m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    private interface DriverConnection extends Connection {
    }

}
