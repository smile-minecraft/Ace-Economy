package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;

/**
 * Offline logical round-trip matrix for the managed backup/restore surface: JSON→JSON and
 * SQLite→JSON→SQLite must preserve accounts, balances, transactions (including reverted
 * markers) and consumed nonces; a backend-level restore failure must roll back live rows.
 * These are offline contract tests — they are NOT a live MySQL or native-dump validation.
 */
class BackupRestoreRoundTripTest {

    @TempDir
    Path dir;

    private static final Set<String> CURRENCIES = Set.of("dollar", "token");

    private BackupRestoreService serviceFor(com.smile.aceeconomy.ports.persistence.PersistenceLifecycle lifecycle,
                                            Path dataFolder) {
        return serviceFor(lifecycle, dataFolder, "aaaa1111");
    }

    private BackupRestoreService serviceFor(com.smile.aceeconomy.ports.persistence.PersistenceLifecycle lifecycle,
                                            Path dataFolder, String idSuffix) {
        return new BackupRestoreService(lifecycle, dataFolder, () -> false, () -> CURRENCIES,
                () -> { }, () -> Instant.parse("2026-08-24T09:30:00Z"), () -> idSuffix);
    }

    private SqlBackend sqliteBackend(Path dbFile) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        return backend;
    }

    /** Seeds one account (two currencies), one reverted transaction and one consumed nonce. */
    private UUID seed(JsonPersistenceBackend json) {
        UUID owner = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        json.create(owner, "Alice", Map.of(
                "dollar", Fixtures.amt("100.00"),
                "token", Fixtures.amt("5")));
        json.append(Fixtures.tx(txId, owner, null, "dollar", Fixtures.amt("40.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("60.00")));
        try {
            json.markReverted(txId);
            assertTrue(json.consume(nonce), "nonce must be consumable while seeding");
        } catch (com.smile.aceeconomy.ports.persistence.PersistenceException e) {
            throw new IllegalStateException("seeding failed", e);
        }
        SEEDED_TX = txId;
        SEEDED_NONCE = nonce;
        return owner;
    }

    private UUID SEEDED_TX;
    private UUID SEEDED_NONCE;

    private void assertLogicalStateMatches(JsonPersistenceBackend expected, JsonPersistenceBackend actual,
                                           UUID owner, UUID txId, UUID nonce) {
        Account expectedAccount = expected.load(owner).orElseThrow();
        Account actualAccount = actual.load(owner).orElseThrow();
        assertEquals(expectedAccount.ownerName(), actualAccount.ownerName());
        assertEquals(expectedAccount.balances(), actualAccount.balances(),
                "balances must survive the round trip exactly");
        assertEquals(expected.isReverted(txId), actual.isReverted(txId),
                "reverted marker must survive");
        assertTrue(actual.isReverted(txId));
        assertEquals(expected.isConsumed(nonce), actual.isConsumed(nonce));
        assertTrue(actual.isConsumed(nonce));
        assertEquals(expected.loadAll().size(), actual.loadAll().size());
        List<Transaction> actualTxs = actual.loadByAccount(owner);
        assertEquals(1, actualTxs.size());
        assertEquals(expected.loadByAccount(owner).get(0).id(), actualTxs.get(0).id());
    }

    @Test
    void jsonToJsonRoundTripPreservesAccountsBalancesRevertedAndNonces() throws Exception {
        JsonPersistenceBackend source = new JsonPersistenceBackend(dir.resolve("a.json"));
        source.initialize();
        JsonPersistenceBackend target = new JsonPersistenceBackend(dir.resolve("b.json"));
        target.initialize();

        UUID owner = seed(source);
        UUID txId = SEEDED_TX;
        UUID nonce = SEEDED_NONCE;

        BackupRestoreService sourceService = serviceFor(source, dir.resolve("src-data"));
        BackupResult backup = sourceService.createBackup("handover");
        assertTrue(backup.isSuccess(), "source backup must succeed: " + backup.message());

        // Operator moves the committed snapshot pair into the target's controlled directory.
        Path snapshotFile = dir.resolve("src-data").resolve("backups")
                .resolve(backup.backupId() + ".json");
        Path snapshotMarker = dir.resolve("src-data").resolve("backups")
                .resolve(backup.backupId() + ".ready");
        assertTrue(Files.isRegularFile(snapshotFile));
        assertTrue(Files.isRegularFile(snapshotMarker));
        Path targetBackups = Files.createDirectories(dir.resolve("dst-data").resolve("backups"));
        Files.copy(snapshotFile, targetBackups.resolve(backup.backupId() + ".json"));
        Files.copy(snapshotMarker, targetBackups.resolve(backup.backupId() + ".ready"));

        // Mutate the target before restoring so the restore visibly replaces state.
        target.create(UUID.randomUUID(), "Bob", Map.of("dollar", Fixtures.amt("9.00")));

        BackupRestoreService targetService = serviceFor(target, dir.resolve("dst-data"));
        RestoreResult restored = targetService.restore(backup.backupId());
        assertTrue(restored.isSuccess(), "target restore must succeed: " + restored.message());

        assertLogicalStateMatches(source, target, owner, txId, nonce);
    }

    @Test
    void sqliteToJsonSqliteLogicalRoundTripPreservesTheFullModel() throws Exception {
        JsonPersistenceBackend json = new JsonPersistenceBackend(dir.resolve("mid.json"));
        json.initialize();
        SqlBackend sqliteSource = sqliteBackend(dir.resolve("s1.sqlite"));
        SqlBackend sqliteTarget = sqliteBackend(dir.resolve("s2.sqlite"));

        UUID owner = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        sqliteSource.create(owner, "Alice", Map.of(
                "dollar", Fixtures.amt("100.00"),
                "token", Fixtures.amt("5")));
        sqliteSource.append(Fixtures.tx(txId, owner, null, "dollar", Fixtures.amt("40.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("60.00")));
        sqliteSource.markReverted(txId);
        assertTrue(sqliteSource.consume(nonce));

        // SQLite → JSON
        BackupRestoreService fromSql = serviceFor(sqliteSource, dir.resolve("sql-src"));
        BackupResult backup = fromSql.createBackup(null);
        assertTrue(backup.isSuccess(), "SQLite backup must succeed: " + backup.message());
        Path snapshotFile = dir.resolve("sql-src").resolve("backups")
                .resolve(backup.backupId() + ".json");
        Path snapshotMarker = dir.resolve("sql-src").resolve("backups")
                .resolve(backup.backupId() + ".ready");
        Path jsonBackups = Files.createDirectories(dir.resolve("json-mid").resolve("backups"));
        Files.copy(snapshotFile, jsonBackups.resolve(backup.backupId() + ".json"));
        Files.copy(snapshotMarker, jsonBackups.resolve(backup.backupId() + ".ready"));
        RestoreResult intoJson = serviceFor(json, dir.resolve("json-mid")).restore(backup.backupId());
        assertTrue(intoJson.isSuccess(), "JSON mid-restore must succeed: " + intoJson.message());

        // JSON → SQLite (a fresh service in the same folder needs a fresh id suffix)
        BackupRestoreService fromJson = serviceFor(json, dir.resolve("json-mid"), "bbbb2222");
        BackupResult second = fromJson.createBackup(null);
        assertTrue(second.isSuccess(), "JSON backup must succeed: " + second.message());
        Path secondFile = dir.resolve("json-mid").resolve("backups")
                .resolve(second.backupId() + ".json");
        Path secondMarker = dir.resolve("json-mid").resolve("backups")
                .resolve(second.backupId() + ".ready");
        Path sqlBackups = Files.createDirectories(dir.resolve("sql-dst").resolve("backups"));
        Files.copy(secondFile, sqlBackups.resolve(second.backupId() + ".json"));
        Files.copy(secondMarker, sqlBackups.resolve(second.backupId() + ".ready"));
        RestoreResult intoSql = serviceFor(sqliteTarget, dir.resolve("sql-dst"))
                .restore(second.backupId());
        assertTrue(intoSql.isSuccess(), "SQLite restore must succeed: " + intoSql.message());

        // Logical equality across all three stores.
        assertEquals("Alice", sqliteTarget.load(owner).orElseThrow().ownerName());
        assertEquals(Map.of("dollar", Fixtures.amt("100.00"), "token", Fixtures.amt("5")),
                sqliteTarget.load(owner).orElseThrow().balances());
        assertTrue(sqliteTarget.isReverted(txId), "reverted marker must cross both hops");
        assertTrue(sqliteTarget.isConsumed(nonce), "consumed nonce must cross both hops");
        assertEquals(1, sqliteTarget.loadAll().size());
        assertEquals(json.loadAll().size(), sqliteTarget.loadAll().size());
        assertNotNull(json.load(owner).orElseThrow().balances().get("token"));

        sqliteSource.close();
        sqliteTarget.close();
    }

    @Test
    void sqlRestoreFailureRollsBackLiveRows() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("fail.sqlite"));
        UUID owner = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        backend.create(owner, "Alice", Map.of("dollar", Fixtures.amt("50.00")));
        backend.append(Fixtures.tx(txId, owner, null, "dollar", Fixtures.amt("10.00"),
                TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("10.00")));
        assertTrue(backend.consume(nonce));

        // A snapshot whose transaction ids collide trips the PK constraint mid-transaction:
        // the DELETEs already issued must be rolled back, leaving every live row in place.
        String duplicatedTx = "{\"id\":\"" + txId + "\",\"accountId\":\"" + owner + "\","
                + "\"counterparty\":null,\"currencyId\":\"dollar\",\"amount\":\"1\","
                + "\"type\":\"DEPOSIT\",\"balanceBefore\":\"0\",\"balanceAfter\":\"1\","
                + "\"timestamp\":\"" + Instant.now() + "\",\"reason\":\"t\",\"reverted\":false}";
        String brokenSnapshot = "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":["
                + duplicatedTx + "," + duplicatedTx + "],\"nonces\":{}}";

        assertThrows(PersistenceException.class, () -> backend.restore(
                new ByteArrayInputStream(brokenSnapshot.getBytes(StandardCharsets.UTF_8))),
                "duplicate transaction ids must fail the SQL restore");

        assertEquals(1, backend.loadAll().size(), "the original transaction must survive");
        assertTrue(backend.exists(owner), "the original account must survive");
        assertEquals(0, Fixtures.amt("50.00")
                        .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")),
                "the original balance must survive");
        assertTrue(backend.isConsumed(nonce), "the consumed nonce must survive");
        assertFalse(backend.isReverted(txId));

        backend.close();
    }
}
