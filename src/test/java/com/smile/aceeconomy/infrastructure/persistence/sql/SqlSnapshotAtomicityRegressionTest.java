package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Red tests for stale caller snapshots crossing independent SQL connections. */
final class SqlSnapshotAtomicityRegressionTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    private SqlBackend open(Path database) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
        backend.initialize();
        return backend;
    }

    @Test
    void independentSavesMustNotLetTheSecondStaleSnapshotOverwriteTheFirst() throws Exception {
        Path database = dir.resolve("save.db");
        SqlBackend first = open(database);
        SqlBackend second = open(database);
        try {
            UUID owner = UUID.randomUUID();
            first.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            Account firstSnapshot = first.load(owner).orElseThrow();
            Account secondSnapshot = second.load(owner).orElseThrow();

            first.save(firstSnapshot, firstSnapshot.deposit("dollar", Fixtures.amt("10.00")));
            assertThrows(PersistenceException.class,
                    () -> second.save(secondSnapshot, secondSnapshot.deposit("dollar", Fixtures.amt("20.00"))),
                    "a stale expected snapshot must be rejected rather than silently overwriting the first save");

            assertEquals(0, Fixtures.amt("110.00")
                    .compareTo(first.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            second.close();
            first.close();
        }
    }

    @Test
    void preparedRedemptionMustCalculateFromTheLiveRowNotThePreparedAccount() throws Exception {
        Path database = dir.resolve("prepared-live-row.db");
        SqlBackend planner = open(database);
        SqlBackend concurrentWriter = open(database);
        try {
            UUID owner = UUID.randomUUID();
            planner.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            Account stale = planner.load(owner).orElseThrow();
            Account prepared = stale.deposit("dollar", Fixtures.amt("10.00"));
            Transaction preparedTransaction = tx(owner, Fixtures.amt("10.00"),
                    Fixtures.amt("100.00"), Fixtures.amt("110.00"));

            concurrentWriter.redeem(UUID.randomUUID(), owner, "dollar", Fixtures.amt("20.00"));
            planner.redeemPrepared(UUID.randomUUID(), prepared, preparedTransaction);

            assertEquals(0, Fixtures.amt("130.00")
                    .compareTo(planner.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            concurrentWriter.close();
            planner.close();
        }
    }

    @Test
    void reversalMustApplyItsDeltaToTheLiveRowRatherThanWriteItsStaleAccountSnapshot() throws Exception {
        Path database = dir.resolve("reversal-live-row.db");
        SqlBackend planner = open(database);
        SqlBackend concurrentWriter = open(database);
        try {
            UUID owner = UUID.randomUUID();
            planner.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            UUID originalId = UUID.randomUUID();
            planner.append(tx(originalId, owner, Fixtures.amt("10.00"),
                    Fixtures.amt("100.00"), Fixtures.amt("110.00")));
            Account staleReversed = planner.load(owner).orElseThrow()
                    .withdraw("dollar", Fixtures.amt("10.00"));
            Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, "dollar",
                    Fixtures.amt("10.00"), TransactionType.WITHDRAW,
                    Fixtures.amt("110.00"), Fixtures.amt("100.00"), Instant.now(), "rollback:deposit");

            concurrentWriter.redeem(UUID.randomUUID(), owner, "dollar", Fixtures.amt("20.00"));
            planner.applyReversal(List.of(staleReversed), List.of(reversal), List.of(originalId));

            assertEquals(0, Fixtures.amt("110.00")
                    .compareTo(planner.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            concurrentWriter.close();
            planner.close();
        }
    }

    private static Transaction tx(UUID owner, Amount amount, Amount before, Amount after) {
        return new Transaction(UUID.randomUUID(), owner, null, "dollar", amount,
                TransactionType.DEPOSIT, before, after, Instant.now(), "banknote-deposit");
    }

    private static Transaction tx(UUID id, UUID owner, Amount amount, Amount before, Amount after) {
        return new Transaction(id, owner, null, "dollar", amount,
                TransactionType.DEPOSIT, before, after, Instant.now(), "deposit");
    }
}
