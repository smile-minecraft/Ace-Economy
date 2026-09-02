package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for lifecycle safety: initialize must fail-closed on
 * incompatible or corrupted schema markers before any DDL, and
 * schema inspection must not hide corruption by taking the first row.
 */
final class SqlSchemaMarkerLifecycleRegressionTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    private SqlBackend backendFor(Path dbFile) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        return new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
    }

    private List<Integer> readMarkerVersions(Path dbFile) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
              PreparedStatement ps = c.prepareStatement("SELECT version FROM " + V2Schema.schemaTable() + " ORDER BY version");
              ResultSet rs = ps.executeQuery()) {
            List<Integer> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getInt(1));
            return out;
        }
    }

    private boolean schemaTableExists(Path dbFile) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            return c.getMetaData().getTables(null, null, V2Schema.schemaTable(), null).next();
        }
    }

    @Test
    void initializeMustFailOnSingleIncompatibleMarker99() throws Exception {
        Path db = dir.resolve("incompat99.db");
        // Manually create marker table with version 99 before backend initialize
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
              Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + V2Schema.schemaTable()
                    + " (version INTEGER PRIMARY KEY, updated_at TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + V2Schema.schemaTable() + " (version, updated_at) VALUES (?, ?)")) {
                ps.setInt(1, 99);
                ps.setString(2, "2026-01-01T00:00:00Z");
                ps.executeUpdate();
            }
        }

        SqlBackend backend = backendFor(db);
        assertThrows(PersistenceException.class, backend::initialize,
                "initialize must reject single incompatible marker 99 before any DDL");
        assertFalse(backend.isInitialized(), "failed initialize must leave isInitialized false");

        // DDL must not have inserted version 1 alongside 99
        List<Integer> versions = readMarkerVersions(db);
        assertEquals(List.of(99), versions, "marker must remain only 99, not augmented with 1");
        // inspection must not report compatible: production returns the stored incompatible value
        // and needsRecreation is true. This locks the fail-closed inspection contract.
        assertEquals(99, backend.schemaVersion(),
                "single incompatible marker 99 must be reported as 99, not silently as current");
        assertTrue(backend.needsRecreation(),
                "incompatible marker 99 must need recreation");
        backend.close();
    }

    @Test
    void initializeMustFailOnMultipleMarkers() throws Exception {
        Path db = dir.resolve("multiple.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
              Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + V2Schema.schemaTable()
                    + " (version INTEGER PRIMARY KEY, updated_at TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + V2Schema.schemaTable() + " (version, updated_at) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "2026-01-01T00:00:00Z");
                ps.executeUpdate();
                ps.setInt(1, 99);
                ps.setString(2, "2026-01-01T00:00:01Z");
                ps.executeUpdate();
            }
        }

        SqlBackend backend = backendFor(db);
        assertThrows(PersistenceException.class, backend::initialize,
                "initialize must reject multiple markers before any DDL");
        assertFalse(backend.isInitialized());

        List<Integer> versions = readMarkerVersions(db);
        assertEquals(2, versions.size(), "multiple markers must remain, not be repaired");
        assertTrue(versions.contains(1) && versions.contains(99));

        // schemaVersion must not silently return first row (1) as compatible: production throws
        assertThrows(PersistenceException.class, backend::schemaVersion,
                "schemaVersion must throw on multiple markers, not hide corruption by returning first row");
        // needsRecreation must report true for corrupted multiple markers
        assertTrue(backend.needsRecreation(),
                "multiple markers must be reported as needing recreation, not compatible");
        backend.close();
    }

    @Test
    void initializeMustFailOnVersionZeroMarker() throws Exception {
        Path db = dir.resolve("version0.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
              Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + V2Schema.schemaTable()
                    + " (version INTEGER PRIMARY KEY, updated_at TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + V2Schema.schemaTable() + " (version, updated_at) VALUES (?, ?)")) {
                ps.setInt(1, 0);
                ps.setString(2, "2026-01-01T00:00:00Z");
                ps.executeUpdate();
            }
        }

        SqlBackend backend = backendFor(db);
        assertThrows(PersistenceException.class, backend::initialize,
                "initialize must reject version 0 as incompatible, not treat as absent");
        assertFalse(backend.isInitialized());
        List<Integer> versions = readMarkerVersions(db);
        assertEquals(List.of(0), versions, "version 0 marker must remain, not be augmented with 1");
        // inspection must not hide version 0: production returns 0 and needsRecreation true
        assertEquals(0, backend.schemaVersion(),
                "single version 0 marker must be reported as 0, not silently as current");
        assertTrue(backend.needsRecreation(),
                "version 0 marker must need recreation");
        backend.close();
    }

    @Test
    void initializeAllowsNoMarkerPartialInit() throws Exception {
        Path db = dir.resolve("partial.db");
        SqlBackend probe = backendFor(db);
        assertFalse(probe.needsRecreation(), "fresh has no tables -> false");
        probe.close();

        // Simulate partial init: accounts table exists but no schema marker
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
              Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + V2Schema.accountsTable() + " (owner TEXT PRIMARY KEY, owner_name TEXT)");
        }
        SqlBackend backend = backendFor(db);
        assertTrue(backend.needsRecreation(), "partial init (table without marker) must need recreation before fix");
        // But initialize must still be able to recover from no-marker partial init
        backend.initialize();
        assertTrue(backend.isInitialized());
        assertFalse(backend.needsRecreation());
        assertEquals(1, backend.schemaVersion());
        List<Integer> versions = readMarkerVersions(db);
        assertEquals(List.of(1), versions);
        backend.close();
    }

    @Test
    void initializeAllowsSingleCurrentRestart() throws Exception {
        Path db = dir.resolve("restart.db");
        SqlBackend first = backendFor(db);
        first.initialize();
        assertTrue(first.isInitialized());
        assertEquals(1, first.schemaVersion());
        first.close();

        SqlBackend second = backendFor(db);
        second.initialize();
        assertTrue(second.isInitialized(), "single current marker restart must succeed");
        assertEquals(1, second.schemaVersion());
        assertFalse(second.needsRecreation());
        second.close();
    }

    @Test
    void schemaVersionAndNeedsRecreationMustNotHideMultiple() throws Exception {
        Path db = dir.resolve("hide.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
              Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + V2Schema.schemaTable()
                    + " (version INTEGER PRIMARY KEY, updated_at TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + V2Schema.schemaTable() + " (version, updated_at) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "2026-01-01T00:00:00Z");
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setString(2, "2026-01-01T00:00:01Z");
                ps.executeUpdate();
            }
        }
        SqlBackend backend = backendFor(db);
        // Multiple markers must not be reported as single compatible: schemaVersion throws,
        // needsRecreation true. This verifies the corruption is not hidden by first-row read.
        assertThrows(PersistenceException.class, backend::schemaVersion,
                "schemaVersion must throw on multiple markers, not return single compatible version");
        assertTrue(backend.needsRecreation(),
                "multiple markers must need recreation, not report compatible");
        backend.close();
    }

    @Test
    void truncateAndRecreateRecoversFromCorruptedMarker() throws Exception {
        Path db = dir.resolve("truncateRecover.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
              Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + V2Schema.schemaTable()
                    + " (version INTEGER PRIMARY KEY, updated_at TEXT NOT NULL)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + V2Schema.schemaTable() + " (version, updated_at) VALUES (?, ?)")) {
                ps.setInt(1, 99);
                ps.setString(2, "x");
                ps.executeUpdate();
                ps.setInt(1, 1);
                ps.setString(2, "y");
                ps.executeUpdate();
            }
        }
        SqlBackend backend = backendFor(db);
        // truncateAndRecreate is explicit data-loss recovery and must succeed even when corrupted
        backend.truncateAndRecreate();
        assertTrue(backend.isInitialized());
        assertEquals(1, backend.schemaVersion());
        assertFalse(backend.needsRecreation());
        List<Integer> versions = readMarkerVersions(db);
        assertEquals(List.of(1), versions);
        backend.close();
    }
}
