package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration rollback through managed restore, plus the guards that stay strict.
 *
 * <p>A snapshot without issuance progress (written before issuance tracking)
 * may only replace live progress that cannot leave a redeemable note behind
 * (deduction-free or terminal settled states such as the aborted record used
 * here). Delivered, possibly-delivered or undecidable live progress refuses
 * the empty candidate instead. Once the candidate carries an issuance
 * section, the strict rules apply again: a live settled record must never
 * rewind to unfinished, and a newcomer carrying debit evidence must reference
 * its authoritative durable debit in the same snapshot.</p>
 *
 * <p>Comment wording here avoids bare functional keywords on purpose so the
 * declaration stays descriptive prose.</p>
 */
class MigrationRollbackRestoreTest {

    private static UUID abortWithoutMoney(JsonPersistenceBackend backend, UUID account, UUID nonce,
            Amount amount) throws Exception {
        backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        backend.markAborted(nonce,
                "rollback scenario: prepared without deduction; aborting with no money movement");
        assertEquals(BanknoteIssuanceStatus.ABORTED,
                backend.findByNonce(nonce).orElseThrow().status(),
                "setup must leave an aborted record that moved no money");
        return nonce;
    }

    private static UUID abortWithoutMoney(SqlBackend backend, UUID account, UUID nonce,
            Amount amount) throws Exception {
        backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        backend.markAborted(nonce,
                "rollback scenario: prepared without deduction; aborting with no money movement");
        assertEquals(BanknoteIssuanceStatus.ABORTED,
                backend.findByNonce(nonce).orElseThrow().status(),
                "setup must leave an aborted record that moved no money");
        return nonce;
    }

    @Test
    void jsonRollbackToSnapshotWithoutIssuanceProgressSucceeds(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("rollback.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();
            assertFalse(new String(preUpgradeBytes, StandardCharsets.UTF_8).contains("issuances"),
                    "pre-upgrade snapshot must keep the old shape without an issuance section");

            UUID nonce = UUID.randomUUID();
            abortWithoutMoney(backend, account, nonce, Amount.of(100L, 2));

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "migration rollback replaces issuance progress wholesale;"
                            + " the aborted live record must be gone");
            assertTrue(backend.listUnfinished().isEmpty(),
                    "no unfinished issuance may survive a rollback to a snapshot without progress");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "balances must mirror the restored snapshot exactly");

            ByteArrayOutputStream after = new ByteArrayOutputStream();
            backend.backup(after);
            assertFalse(new String(after.toByteArray(), StandardCharsets.UTF_8).contains("issuances"),
                    "restored state must keep the old shape so older readers still load it");
        } finally {
            backend.close();
        }

        JsonPersistenceBackend reopened = new JsonPersistenceBackend(dataFile);
        reopened.initialize();
        try {
            assertTrue(reopened.listUnfinished().isEmpty(),
                    "reopened store must see no unfinished issuance after the rollback");
        } finally {
            reopened.close();
        }
    }

    @Test
    void sqliteRollbackToSnapshotWithoutIssuanceProgressSucceeds(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("rollback.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();
            assertFalse(new String(preUpgradeBytes, StandardCharsets.UTF_8).contains("issuances"),
                    "pre-upgrade snapshot must keep the old shape without an issuance section");

            UUID nonce = UUID.randomUUID();
            abortWithoutMoney(backend, account, nonce, Amount.of(100L, 2));

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "migration rollback replaces issuance progress wholesale;"
                            + " the aborted live record must be gone");
            assertTrue(backend.listUnfinished().isEmpty(),
                    "no unfinished issuance may survive a rollback to a snapshot without progress");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "balances must mirror the restored snapshot exactly");

            ByteArrayOutputStream after = new ByteArrayOutputStream();
            backend.backup(after);
            JsonModel reloaded = JsonModel.fromJson(
                    new String(after.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(reloaded.issuances.isEmpty(),
                    "restored state must carry no issuance progress,"
                            + " so older readers still load it");
        } finally {
            backend.close();
        }

        SqlBackend reopened = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        reopened.initialize();
        try {
            assertTrue(reopened.listUnfinished().isEmpty(),
                    "reopened store must see no unfinished issuance after the rollback");
        } finally {
            reopened.close();
        }
    }

    @Test
    void jsonSettledLiveRecordStillRefusesRewoundCandidate(@TempDir Path dir) throws Exception {
        Path liveFile = dir.resolve("guard-live.json");
        JsonPersistenceBackend live = new JsonPersistenceBackend(liveFile);
        live.initialize();
        try {
            UUID account = UUID.randomUUID();
            UUID nonce = UUID.randomUUID();
            live.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            abortWithoutMoney(live, account, nonce, Amount.of(100L, 2));

            Path candidateFile = dir.resolve("guard-candidate.json");
            JsonPersistenceBackend candidate = new JsonPersistenceBackend(candidateFile);
            candidate.initialize();
            try {
                candidate.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
                candidate.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                        Amount.of(100L, 2), Instant.now()));
                ByteArrayOutputStream rewound = new ByteArrayOutputStream();
                candidate.backup(rewound);
                assertTrue(new String(rewound.toByteArray(), StandardCharsets.UTF_8)
                                .contains("issuances"),
                        "setup must produce a candidate that carries an issuance section");

                PersistenceException refused = assertThrows(PersistenceException.class,
                        () -> live.restore(new ByteArrayInputStream(rewound.toByteArray())),
                        "restoring a PREPARED candidate over an ABORTED live record must be refused");
                assertTrue(refused.getMessage() != null
                        && refused.getMessage().contains("Restore refused"),
                        "diagnosis must name the restore refusal, got: " + refused.getMessage());
                assertEquals(BanknoteIssuanceStatus.ABORTED,
                        live.findByNonce(nonce).orElseThrow().status(),
                        "a refused restore must leave the settled live record untouched");
            } finally {
                candidate.close();
            }
        } finally {
            live.close();
        }
    }

    @Test
    void sqliteSettledLiveRecordStillRefusesRewoundCandidate(@TempDir Path dir) throws Exception {
        Path liveDb = dir.resolve("guard-live.db");
        SqlBackend live = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + liveDb)),
                new SqliteDialect());
        live.initialize();
        try {
            UUID account = UUID.randomUUID();
            UUID nonce = UUID.randomUUID();
            live.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            abortWithoutMoney(live, account, nonce, Amount.of(100L, 2));

            Path candidateDb = dir.resolve("guard-candidate.db");
            SqlBackend candidate = new SqlBackend(
                    new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + candidateDb)),
                    new SqliteDialect());
            candidate.initialize();
            try {
                candidate.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
                candidate.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                        Amount.of(100L, 2), Instant.now()));
                ByteArrayOutputStream rewound = new ByteArrayOutputStream();
                candidate.backup(rewound);

                PersistenceException refused = assertThrows(PersistenceException.class,
                        () -> live.restore(new ByteArrayInputStream(rewound.toByteArray())),
                        "restoring a PREPARED candidate over an ABORTED live record must be refused");
                assertTrue(refused.getMessage() != null
                        && refused.getMessage().contains("Restore refused"),
                        "diagnosis must name the restore refusal, got: " + refused.getMessage());
                assertEquals(BanknoteIssuanceStatus.ABORTED,
                        live.findByNonce(nonce).orElseThrow().status(),
                        "a refused restore must leave the settled live record untouched");
            } finally {
                candidate.close();
            }
        } finally {
            live.close();
        }
    }

    /**
     * A candidate whose issuance section carries debit evidence backed only by
     * a decoy row of the wrong type. The shared record preflight lets the row
     * through (the id is present), so the restore guard itself must refuse it
     * for lacking an authoritative durable debit.
     */
    private static byte[] forgedCandidate(byte[] baseSnapshot, UUID account, Amount amount)
            throws Exception {
        JsonModel candidate = JsonModel.fromJson(
                new String(baseSnapshot, StandardCharsets.UTF_8));
        UUID nonce = UUID.randomUUID();
        Instant now = Instant.now();
        UUID decoyId = UUID.randomUUID();
        BanknoteIssuance forged = new BanknoteIssuance(nonce, account, "dollar", amount,
                BanknoteIssuanceStatus.DEBIT_COMMITTED, decoyId.toString(),
                BanknoteIssuanceReasons.issueReason(nonce), now, now);
        candidate.issuances.put(nonce.toString(), JsonModel.JsonIssuance.fromDomain(forged));
        JsonModel.JsonTransaction decoy = new JsonModel.JsonTransaction();
        decoy.id = decoyId.toString();
        decoy.accountId = account.toString();
        decoy.counterparty = null;
        decoy.currencyId = "dollar";
        decoy.amount = amount.value().toPlainString();
        decoy.type = "DEPOSIT";
        decoy.balanceBefore = Amount.of(10000L, 2).value().toPlainString();
        decoy.balanceAfter = Amount.of(10100L, 2).value().toPlainString();
        decoy.timestamp = now.toString();
        decoy.reason = "forged-decoy-row";
        decoy.reverted = false;
        candidate.transactions.add(decoy);
        return candidate.toJson().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void jsonForgedNewcomerWithoutAuthoritativeDebitIsRefused(@TempDir Path dir) throws Exception {
        Path liveFile = dir.resolve("forged-live.json");
        JsonPersistenceBackend live = new JsonPersistenceBackend(liveFile);
        live.initialize();
        try {
            UUID account = UUID.randomUUID();
            live.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream base = new ByteArrayOutputStream();
            live.backup(base);

            byte[] forged = forgedCandidate(base.toByteArray(), account, Amount.of(100L, 2));
            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> live.restore(new ByteArrayInputStream(forged)),
                    "a candidate newcomer carrying debit evidence without its authoritative"
                            + " durable debit in the same snapshot must be refused");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            live.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave live balances untouched");
        } finally {
            live.close();
        }
    }

    @Test
    void sqliteForgedNewcomerWithoutAuthoritativeDebitIsRefused(@TempDir Path dir) throws Exception {
        Path liveDb = dir.resolve("forged-live.db");
        SqlBackend live = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + liveDb)),
                new SqliteDialect());
        live.initialize();
        try {
            UUID account = UUID.randomUUID();
            live.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream base = new ByteArrayOutputStream();
            live.backup(base);

            byte[] forged = forgedCandidate(base.toByteArray(), account, Amount.of(100L, 2));
            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> live.restore(new ByteArrayInputStream(forged)),
                    "a candidate newcomer carrying debit evidence without its authoritative"
                            + " durable debit in the same snapshot must be refused");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            live.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave live balances untouched");
        } finally {
            live.close();
        }
    }

    @Test
    void jsonPlainNonceRollsBackWithBalances(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("nonce-rollback.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream before = new ByteArrayOutputStream();
            backend.backup(before);

            UUID nonce = UUID.randomUUID();
            assertTrue(backend.consume(nonce), "setup must consume the nonce");
            assertTrue(backend.isConsumed(nonce), "setup must observe the consumed nonce");

            backend.restore(new ByteArrayInputStream(before.toByteArray()));

            assertFalse(backend.isConsumed(nonce),
                    "this records rollback semantics for plain nonces, not a value promise:"
                            + " balances and plain nonces roll back together, so a consumed"
                            + " nonce becomes presentable again after the restore;"
                            + " physical notes live outside the snapshot and are unaffected");
        } finally {
            backend.close();
        }
    }
}
