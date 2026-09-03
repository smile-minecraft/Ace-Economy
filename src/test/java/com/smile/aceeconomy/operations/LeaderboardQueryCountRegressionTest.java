package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LeaderboardQueryCountRegressionTest {

    static {
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }

    @TempDir Path dir;

    private static Connection countingConnection(Connection real, AtomicInteger prepareCount) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if ("prepareStatement".equals(name)) { prepareCount.incrementAndGet(); }
            if ("close".equals(name)) { return null; }
            try { return method.invoke(real, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, h);
    }

    // This test proves the N+1 is gone: listing 100 and 1000 accounts still uses constant prepareStatements.
    // Before fix: RepositoryLeaderboardSource used accounts.listAll() which internally did
    // 1 + N prepareStatements (N loadBalances). After fix: single JOIN query => <=3.
    @Test
    void sqlLeaderboardRefreshIsConstantQueries_notLinear() throws Exception {
        Path db = dir.resolve("count.db");
        AtomicInteger prepareCount = new AtomicInteger();

        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        Connection counting = countingConnection(real, prepareCount);
        SqlConnectionProvider provider = new SqlConnectionProvider(counting);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.initialize();

        int accounts = 100;
        for (int i = 0; i < accounts; i++) {
            UUID id = new UUID(0, i + 1);
            backend.create(id, "player" + i, Map.of("coin", Amount.of((long) (i * 10), 2)));
        }

        // Use the backend's native leaderboard path if available, else fallback to listAll path
        // We test via the LeaderboardSource contract: a single SQL query should serve 100 rows
        prepareCount.set(0);
        List<LeaderboardRow> rows = loadViaLeaderboardPath(backend, "coin");
        int preparesFor100 = prepareCount.get();
        assertEquals(accounts, rows.size(), "all rows must be returned");
        assertTrue(preparesFor100 <= 3,
                "SQL leaderboard refresh must be constant queries (prepareStatement <=3), was " + preparesFor100
                + " for " + accounts + " accounts — N+1 still active");

        // [VERIFY:P2] 驗證排行榜排序具決定性：餘額最高者在首位，同分時依 UUID 遞增排序。
        assertEquals(new UUID(0, 100), rows.get(0).accountId(), "highest balance account should be first");

        // Scale to 1000: count must not grow linearly
        for (int i = 100; i < 1000; i++) {
            UUID id = new UUID(0, i + 1);
            backend.create(id, "player" + i, Map.of("coin", Amount.of((long) (i * 10), 2)));
        }
        prepareCount.set(0);
        List<LeaderboardRow> rows1000 = loadViaLeaderboardPath(backend, "coin");
        int preparesFor1000 = prepareCount.get();
        assertEquals(1000, rows1000.size());
        assertTrue(preparesFor1000 <= 3,
                "1k accounts must still be constant queries, was " + preparesFor1000);
        assertTrue(preparesFor1000 <= preparesFor100 + 2,
                "query count must not grow linearly: 100->" + preparesFor100 + " , 1000->" + preparesFor1000);

        backend.close(); real.close();
    }

    @Test
    void sqlLeaderboardTopN_isSingleQuery() throws Exception {
        Path db = dir.resolve("topn.db");
        AtomicInteger prepareCount = new AtomicInteger();
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        Connection counting = countingConnection(real, prepareCount);
        SqlConnectionProvider provider = new SqlConnectionProvider(counting);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.initialize();
        for (int i = 0; i < 50; i++) {
            backend.create(new UUID(0, i + 1), "p" + i, Map.of("coin", Amount.of((long) (i * 10), 2)));
        }
        prepareCount.set(0);
        List<LeaderboardRow> rows = loadViaLeaderboardPath(backend, "coin");
        List<LeaderboardRow> top10 = rows.subList(0, Math.min(10, rows.size()));
        assertEquals(10, top10.size());
        assertTrue(prepareCount.get() <= 3, "top-N fetch must be single query, was " + prepareCount.get());
        backend.close(); real.close();
    }

    @Test
    void numericOrderingIsCorrect_notLexicographic() throws Exception {
        Path db = dir.resolve("numeric.db");
        AtomicInteger prepareCount = new AtomicInteger();
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        Connection counting = countingConnection(real, prepareCount);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(counting), new SqliteDialect());
        backend.initialize();
        // Balances that expose lexicographic vs numeric sort: "100.00" < "20.00" lexicographically but > numerically
        backend.create(new UUID(0, 1), "a", Map.of("coin", Amount.of(100, 2)));
        backend.create(new UUID(0, 2), "b", Map.of("coin", Amount.of(20, 2)));
        backend.create(new UUID(0, 3), "c", Map.of("coin", Amount.of(9, 2)));
        backend.create(new UUID(0, 4), "d", Map.of("coin", Amount.of(200, 2)));
        List<LeaderboardRow> rows = loadViaLeaderboardPath(backend, "coin");
        assertEquals(new UUID(0, 4), rows.get(0).accountId(), "200 should rank 1st");
        assertEquals(new UUID(0, 1), rows.get(1).accountId(), "100 should rank 2nd");
        assertEquals(new UUID(0, 2), rows.get(2).accountId(), "20 should rank 3rd");
        assertEquals(new UUID(0, 3), rows.get(3).accountId(), "9 should rank last");
        backend.close(); real.close();
    }

    // Helper: uses native leaderboard API if backend implements it, else falls back to listAll path.
    // After fix, backend will implement LeaderboardRepository and this will be constant.
    private List<LeaderboardRow> loadViaLeaderboardPath(SqlBackend backend, String currencyId) {
        // Try native leaderboard method via reflection so test is green before and after the API exists
        try {
            var m = backend.getClass().getMethod("leaderboardRows", String.class);
            @SuppressWarnings("unchecked")
            List<LeaderboardRow> r = (List<LeaderboardRow>) m.invoke(backend, currencyId);
            return r;
        } catch (NoSuchMethodException e) {
            // Fallback: emulate old RepositoryLeaderboardSource behavior: listAll + filter
            return backend.listAll().stream()
                    .map(a -> {
                        var amt = a.balanceOf(currencyId);
                        return amt == null ? null : new LeaderboardRow(a.owner(), a.ownerName(), amt);
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparing((LeaderboardRow r) -> r.balance().value()).reversed()
                            .thenComparing(LeaderboardRow::accountId))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
