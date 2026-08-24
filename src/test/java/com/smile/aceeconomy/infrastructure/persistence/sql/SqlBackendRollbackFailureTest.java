package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the SQL restore connection-state contract: on failure the backend
 * must never flip {@code autoCommit} back on when the rollback failed (that would
 * implicitly commit a partially applied restore — the connection is closed instead), and
 * on success the committed transaction must be followed by restoring {@code autoCommit} so
 * the shared connection stays usable for ordinary repository writes.
 */
class SqlBackendRollbackFailureTest {

    @TempDir
    Path dir;

    private static final String VALID_SNAPSHOT =
            "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],\"nonces\":{}}";

    @Test
    void successfulRestoreRestoresAutoCommitOnTheSharedConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mock(java.sql.PreparedStatement.class));

        SqlBackend backend = new SqlBackend(connection, new SqliteDialect());
        backend.restore(new ByteArrayInputStream(
                VALID_SNAPSHOT.getBytes(StandardCharsets.UTF_8)));

        // Success path: exactly one manual-transaction cycle, ending back in autocommit.
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }

    @Test
    void successfulRestoreLeavesARealConnectionInAutocommitModeForOrdinaryWrites()
            throws Exception {
        Path db = dir.resolve("autocommit.db");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(connection, new SqliteDialect());
        backend.initialize();

        backend.restore(new ByteArrayInputStream(
                VALID_SNAPSHOT.getBytes(StandardCharsets.UTF_8)));

        assertTrue(connection.getAutoCommit(),
                "after a committed restore the shared connection must be back in autocommit");
        // Ordinary repository writes keep working right after the restore.
        UUID owner = UUID.randomUUID();
        backend.create(owner, "Bob", Map.of("dollar", Fixtures.amt("5.00")));
        assertTrue(backend.exists(owner));
        backend.close();
    }

    @Test
    void failedRollbackClosesConnectionAndNeverImplicitlyCommits() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        // The DML fails mid-transaction...
        when(statement.executeUpdate(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("dml-boom"));
        // ...and the rollback fails too.
        doThrow(new SQLException("rollback-boom")).when(connection).rollback();

        SqlBackend backend = new SqlBackend(connection, new SqliteDialect());

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(
                        VALID_SNAPSHOT.getBytes(StandardCharsets.UTF_8))));

        // Both causes are traceable: original DML failure as cause, rollback failure suppressed.
        assertNotNull(failure.getCause(), "the original SQL failure must be the cause");
        assertEquals("dml-boom", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length,
                "the rollback failure must be preserved as a suppressed cause");
        assertEquals("rollback-boom", failure.getCause().getSuppressed()[0].getMessage());
        assertTrue(failure.getMessage().contains("closed")
                        || failure.getMessage().contains("rollback"),
                "the operator guidance must name the fail-closed action: " + failure.getMessage());

        // Fail closed: the unsafe connection is closed and autoCommit is NEVER restored,
        // so a partially applied restore can never be committed implicitly.
        verify(connection).close();
        verify(connection, never()).setAutoCommit(true);
    }

    @Test
    void cleanRollbackStillReportsFailureWithoutClosingTheConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeUpdate(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("dml-boom"));
        // Rollback succeeds this time.

        SqlBackend backend = new SqlBackend(connection, new SqliteDialect());

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(
                        VALID_SNAPSHOT.getBytes(StandardCharsets.UTF_8))));
        assertEquals("dml-boom", failure.getCause().getMessage());
        assertEquals(0, failure.getCause().getSuppressed().length);

        // A cleanly rolled-back connection returns to autocommit for ordinary repository use.
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).close();
    }
}
