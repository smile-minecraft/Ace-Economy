package com.smile.aceeconomy.infrastructure.persistence.sql;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thread-safety contract for {@link SqlBackend}. The single-Connection model is safe
 * only if every public method serializes access. These tests exercise that contract
 * with concurrent append/load from multiple threads on the same backend instance and
 * assert no {@link PersistenceException} escapes from racing writes and that the final
 * state is consistent (no lost writes, no phantom rows).
 */
final class SqlBackendConcurrencyTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    private SqlBackend open(Path db) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(conn, new SqliteDialect());
        backend.initialize();
        return backend;
    }

    private Transaction deposit(UUID account, int cents) {
        UUID txId = UUID.randomUUID();
        Amount amount = Amount.of(cents, 0);
        return new Transaction(txId, account, null, "dollar", amount, TransactionType.DEPOSIT,
                Amount.of(0, 0), amount, Fixtures.T0, "concurrent-test");
    }

    @Test
    void concurrentAppendsOnSeparateAccountsAreSerializedAndAllVisible() throws Exception {
        Path db = dir.resolve("separate.db");
        SqlBackend backend = open(db);
        try {
            int threadCount = 8;
            int perThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    UUID account = UUID.randomUUID();
                    for (int i = 0; i < perThread; i++) {
                        backend.append(deposit(account, 1));
                    }
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            // Every append must be persisted — no lost writes under contention.
            int total = backend.loadAll().size();
            assertEquals(threadCount * perThread, total,
                    "all " + (threadCount * perThread) + " deposits must be visible");
        } finally {
            backend.close();
        }
    }

    @Test
    void concurrentReadAndWriteOnSameAccountNeverCorruptState() throws Exception {
        Path db = dir.resolve("shared.db");
        SqlBackend backend = open(db);
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Fixtures.amt("0.00")));

            int threadCount = 6;
            int iterations = 40;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            List<Future<Void>> futures = new ArrayList<>();
            AtomicInteger writeErrors = new AtomicInteger();
            Set<UUID> distinctTxIds = java.util.Collections.synchronizedSet(new HashSet<>());

            for (int t = 0; t < threadCount; t++) {
                final boolean writer = (t % 2 == 0);
                futures.add(pool.submit((Callable<Void>) () -> {
                    for (int i = 0; i < iterations; i++) {
                        if (writer) {
                            UUID txId = UUID.randomUUID();
                            distinctTxIds.add(txId);
                            try {
                                backend.append(new Transaction(txId, account, null, "dollar",
                                        Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                                        Fixtures.amt("0.00"), Fixtures.amt("1.00"),
                                        Fixtures.T0, "concurrent"));
                            } catch (PersistenceException e) {
                                writeErrors.incrementAndGet();
                                throw new RuntimeException(e);
                            }
                        } else {
                            var loaded = backend.load(account);
                            assertNotNull(loaded, "read must always observe the account");
                        }
                    }
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(0, writeErrors.get(),
                    "no PersistenceException must escape the synchronized append path");
            // The distinct writes are exactly the number of writer-thread iterations.
            int writers = (threadCount + 1) / 2;
            assertEquals(writers * iterations, distinctTxIds.size());
            // Every distinct id must be visible in loadAll (none lost).
            List<Transaction> all = backend.loadAll();
            assertEquals(writers * iterations, all.size());
        } finally {
            backend.close();
        }
    }
}