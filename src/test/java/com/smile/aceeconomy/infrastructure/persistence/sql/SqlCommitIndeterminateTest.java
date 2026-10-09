package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A commit whose response is lost must surface as indeterminate — never as a proven
 * rollback. The server may already have committed, so the backend must not let the
 * boundary mistake this for an ordinary retryable failure.
 */
class SqlCommitIndeterminateTest {

    /** Proxy that throws {@code commitFailure} from {@code commit()} only while armed. */
    private static Connection commitFailing(Connection delegate, AtomicBoolean armed,
                                            SQLException commitFailure) {
        return (Connection) Proxy.newProxyInstance(
                SqlCommitIndeterminateTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("commit") && (args == null || args.length == 0)
                            && armed.get()) {
                        throw commitFailure;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Proxy that throws {@code commitError} from {@code commit()} only while armed. */
    private static Connection commitErroring(Connection delegate, AtomicBoolean armed,
                                             Error commitError) {
        return (Connection) Proxy.newProxyInstance(
                SqlCommitIndeterminateTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("commit") && (args == null || args.length == 0)
                            && armed.get()) {
                        throw commitError;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * Proxy whose {@code commit()} really commits first and only then loses the
     * response. This reproduces a commit that has taken effect while its
     * confirmation never reaches the caller.
     */
    private static Connection commitThenLosingResponse(Connection delegate, AtomicBoolean armed,
                                                       SQLException responseFailure) {
        return (Connection) Proxy.newProxyInstance(
                SqlCommitIndeterminateTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("commit") && (args == null || args.length == 0)
                            && armed.get()) {
                        try {
                            method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        throw responseFailure;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * Proxy whose post-commit {@code setAutoCommit(true)} restore throws
     * {@code restoreError} only while armed. The commit itself succeeds, so the
     * data is durable and only the cleanup response is lost.
     */
    private static Connection restoreErroring(Connection delegate, AtomicBoolean armed,
                                              Error restoreError) {
        return (Connection) Proxy.newProxyInstance(
                SqlCommitIndeterminateTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && args != null && args.length == 1
                            && Boolean.TRUE.equals(args[0]) && armed.get()) {
                        throw restoreError;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static SqlBackend verifyBackend(Path db) throws Exception {
        Connection verifyConn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend verify = new SqlBackend(new SqlConnectionProvider(verifyConn), new SqliteDialect());
        verify.initialize();
        return verify;
    }

    @Test
    void commitThrowingErrorEscapesInsteadOfIndeterminate(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("commit-error.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        AssertionError commitError = new AssertionError("injected commit Error");
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(commitErroring(real, armed, commitError)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID alice = UUID.randomUUID();
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            Account before = backend.load(alice).orElseThrow();
            Account updated = before.deposit("dollar", Fixtures.amt("10.00"));

            armed.set(true);
            AssertionError escaped = assertThrows(AssertionError.class,
                    () -> backend.save(before, updated),
                    "an Error from commit must escape, never be wrapped as a storage failure");
            assertSame(commitError, escaped, "the original Error must escape untouched");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed commit abandoned the connection.
            }
        }
    }

    @Test
    void commitSucceedsButResponseLostStaysIndeterminateAndDurable(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("commit-durable.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        SQLException responseFailure = new SQLException("injected commit response lost after commit");
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(commitThenLosingResponse(real, armed, responseFailure)),
                new SqliteDialect());
        backend.initialize();
        UUID alice = UUID.randomUUID();
        try {
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            Account before = backend.load(alice).orElseThrow();
            Account updated = before.deposit("dollar", Fixtures.amt("10.00"));

            armed.set(true);
            IndeterminatePersistenceException ex = assertThrows(
                    IndeterminatePersistenceException.class,
                    () -> backend.save(before, updated),
                    "a commit that took effect while its response was lost must stay indeterminate");
            assertSame(responseFailure, ex.getCause(),
                    "the lost commit response must stay the cause for diagnosis");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed commit abandoned the connection.
            }
        }

        // The credit was really committed: a second connection must see the new balance.
        SqlBackend verify = verifyBackend(db);
        try {
            assertEquals(0, Fixtures.amt("1010.00")
                            .compareTo(verify.load(alice).orElseThrow().balanceOf("dollar")),
                    "a commit that took effect must be visible on another connection");
        } finally {
            verify.close();
        }
    }

    @Test
    void restoreThrowingErrorEscapesAfterDurableCommit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("restore-error.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        AssertionError restoreError = new AssertionError("injected restore Error");
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(restoreErroring(real, armed, restoreError)),
                new SqliteDialect());
        backend.initialize();
        UUID alice = UUID.randomUUID();
        try {
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            Account before = backend.load(alice).orElseThrow();
            Account updated = before.deposit("dollar", Fixtures.amt("10.00"));

            armed.set(true);
            AssertionError escaped = assertThrows(AssertionError.class,
                    () -> backend.save(before, updated),
                    "an Error from post-commit cleanup must escape, never become a committed receipt");
            assertSame(restoreError, escaped, "the original Error must escape untouched");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed restore abandoned the connection.
            }
        }

        // The data was committed before the cleanup failed: another connection must see it.
        SqlBackend verify = verifyBackend(db);
        try {
            assertEquals(0, Fixtures.amt("1010.00")
                            .compareTo(verify.load(alice).orElseThrow().balanceOf("dollar")),
                    "data committed before the cleanup failure must stay durable");
        } finally {
            verify.close();
        }
    }

    @Test
    void saveCommitResponseLostIsIndeterminateNotUncommitted(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("indeterminate-save.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        SQLException commitFailure = new SQLException("injected commit response lost");
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(commitFailing(real, armed, commitFailure)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID alice = UUID.randomUUID();
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            Account before = backend.load(alice).orElseThrow();
            Account updated = before.deposit("dollar", Fixtures.amt("10.00"));

            armed.set(true);
            IndeterminatePersistenceException ex = assertThrows(
                    IndeterminatePersistenceException.class,
                    () -> backend.save(before, updated),
                    "a commit whose response was lost must not surface as an ordinary failure");
            assertSame(commitFailure, ex.getCause(),
                    "the lost commit response must stay the cause for diagnosis");
            assertFalse(ex.isCommitted(),
                    "commitment was never confirmed; only the indeterminate type says so");
            assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("uncertain"),
                    "the message must say the result is uncertain, got: " + ex.getMessage());
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed commit abandoned the connection; durability was never the claim here.
            }
        }
    }

    @Test
    void createCommitResponseLostIsIndeterminateNotUncommitted(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("indeterminate-create.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        SQLException commitFailure = new SQLException("injected commit response lost");
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(commitFailing(real, armed, commitFailure)),
                new SqliteDialect());
        backend.initialize();
        try {
            armed.set(true);
            assertThrows(IndeterminatePersistenceException.class,
                    () -> backend.create(UUID.randomUUID(), "Newbie",
                            Map.of("dollar", Fixtures.amt("1000.00"))),
                    "a commit whose response was lost must not surface as an ordinary failure");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // See above: the failed commit abandoned the connection.
            }
        }
    }
}
