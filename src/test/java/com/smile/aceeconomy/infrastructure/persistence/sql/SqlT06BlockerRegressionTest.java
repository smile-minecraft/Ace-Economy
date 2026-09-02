package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression for SQL blockers ({taskId}):
 * 1) MySQL redeem lost-update (read before transaction) must be prevented via
 *    transaction-before-read + SELECT ... FOR UPDATE.
 * 2) Hikari unsafe eviction failure must not return poisoned connection to pool via ordinary close().
 */
final class SqlT06BlockerRegressionTest {

    static {
        try { Class.forName("org.sqlite.JDBC"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @TempDir
    Path dir;

    // ---------- blocker 1: MySQL FOR UPDATE contract ----------

    @Test
    void mysqlDialectEmitsForUpdateAndSqliteDoesNot() {
        assertEquals(" FOR UPDATE", new MySqlDialect().forUpdateClause());
        assertEquals("", new SqliteDialect().forUpdateClause());
    }

    @Test
    void redeemWithMySqlDialectStartsTransactionBeforeLoadAndUsesForUpdate() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement psSelect = mock(PreparedStatement.class);
        PreparedStatement psBalances = mock(PreparedStatement.class);
        PreparedStatement psSaveDel = mock(PreparedStatement.class);
        PreparedStatement psSaveAcc = mock(PreparedStatement.class);
        PreparedStatement psSaveBal = mock(PreparedStatement.class);
        PreparedStatement psTx = mock(PreparedStatement.class);
        PreparedStatement psNonce = mock(PreparedStatement.class);
        ResultSet rsAccount = mock(ResultSet.class);
        ResultSet rsBalances = mock(ResultSet.class);

        // strict call-order recording: every prepare and setAutoCommit is logged so the
        // assertion can prove transaction-before-read, not just that mocks were called in some order.
        java.util.List<String> order = new java.util.ArrayList<>();
        AtomicInteger firstSelectIndex = new AtomicInteger(-1);
        AtomicReference<String> accountsSelectSql = new AtomicReference<>();
        AtomicReference<String> balancesSelectSql = new AtomicReference<>();
        doAnswer(inv -> {
            order.add("setAutoCommit:" + inv.getArgument(0));
            return null;
        }).when(conn).setAutoCommit(anyBoolean());
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            order.add("prepare:" + sql);
            if (sql.startsWith("SELECT ") && firstSelectIndex.get() == -1) {
                firstSelectIndex.set(order.size() - 1);
            }
            if (sql.contains("SELECT owner_name")) {
                accountsSelectSql.set(sql);
                return psSelect;
            }
            if (sql.contains("SELECT currency_id")) {
                balancesSelectSql.set(sql);
                return psBalances;
            }
            if (sql.contains("DELETE FROM")) return psSaveDel;
            if (sql.contains("REPLACE INTO " + V2Schema.accountsTable())) return psSaveAcc;
            if (sql.contains("REPLACE INTO " + V2Schema.balancesTable())) return psSaveBal;
            if (sql.contains("INSERT INTO " + V2Schema.transactionsTable())) return psTx;
            if (sql.contains(V2Schema.noncesTable())) return psNonce;
            return mock(PreparedStatement.class);
        });
        when(psSelect.executeQuery()).thenAnswer(inv -> {
            order.add("executeQuery:SELECT-owner");
            return rsAccount;
        });
        when(rsAccount.next()).thenReturn(true);
        when(rsAccount.getString(1)).thenReturn("alice");
        when(psBalances.executeQuery()).thenAnswer(inv -> {
            order.add("executeQuery:SELECT-balances");
            return rsBalances;
        });
        when(rsBalances.next()).thenReturn(true).thenReturn(false);
        when(rsBalances.getString(1)).thenReturn("dollar");
        when(rsBalances.getString(2)).thenReturn("10.00");
        when(psNonce.executeUpdate()).thenReturn(1);
        when(conn.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
        SqlConnectionProvider provider = new SqlConnectionProvider(new SingleConnectionDataSource(conn));
        SqlBackend backend = new SqlBackend(provider, new MySqlDialect());
        backend.setInitializedForTest(true);

        UUID account = UUID.randomUUID();
        RedemptionResult result = backend.redeem(UUID.randomUUID(), account, "dollar", Amount.of(new BigDecimal("5.00"), 2));

        assertTrue(result.isCommitted());
        // Transaction must start before any read: first setAutoCommit(false) precedes first SELECT prepare
        int txIndex = order.indexOf("setAutoCommit:false");
        int firstSelectOrderIndex = firstSelectIndex.get();
        assertTrue(txIndex >= 0, "setAutoCommit(false) must be called; order=" + order);
        assertTrue(firstSelectOrderIndex >= 0, "SELECT prepare must occur; order=" + order);
        assertTrue(txIndex < firstSelectOrderIndex,
                "transaction (setAutoCommit false at " + txIndex + ") must precede first SELECT prepare at " + firstSelectOrderIndex + "; order=" + order);
        assertEquals("SELECT owner_name FROM " + V2Schema.accountsTable()
                        + " WHERE owner = ? FOR UPDATE", accountsSelectSql.get(),
                "accounts SELECT must carry FOR UPDATE");
        assertEquals("SELECT currency_id, amount FROM " + V2Schema.balancesTable()
                        + " WHERE owner = ? FOR UPDATE", balancesSelectSql.get(),
                "balances SELECT must carry FOR UPDATE");
    }

    @Test
    void redeemWithSqliteDialectDoesNotEmitForUpdate() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement psSelect = mock(PreparedStatement.class);
        PreparedStatement psBalances = mock(PreparedStatement.class);
        PreparedStatement psSaveDel = mock(PreparedStatement.class);
        PreparedStatement psSaveAcc = mock(PreparedStatement.class);
        PreparedStatement psSaveBal = mock(PreparedStatement.class);
        PreparedStatement psTx = mock(PreparedStatement.class);
        PreparedStatement psNonce = mock(PreparedStatement.class);
        ResultSet rsAccount = mock(ResultSet.class);
        ResultSet rsBalances = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            assertFalse(sql.contains("FOR UPDATE"), "SQLite must not emit FOR UPDATE: " + sql);
            if (sql.contains("SELECT owner_name")) return psSelect;
            if (sql.contains("SELECT currency_id")) return psBalances;
            if (sql.contains("DELETE FROM")) return psSaveDel;
            if (sql.contains("REPLACE INTO " + V2Schema.accountsTable())) return psSaveAcc;
            if (sql.contains("REPLACE INTO " + V2Schema.balancesTable())) return psSaveBal;
            if (sql.contains("INSERT INTO " + V2Schema.transactionsTable())) return psTx;
            if (sql.contains(V2Schema.noncesTable())) return psNonce;
            return mock(PreparedStatement.class);
        });
        when(psSelect.executeQuery()).thenReturn(rsAccount);
        when(rsAccount.next()).thenReturn(true);
        when(rsAccount.getString(1)).thenReturn("bob");
        when(psBalances.executeQuery()).thenReturn(rsBalances);
        when(rsBalances.next()).thenReturn(true).thenReturn(false);
        when(rsBalances.getString(1)).thenReturn("dollar");
        when(rsBalances.getString(2)).thenReturn("10.00");
        when(psNonce.executeUpdate()).thenReturn(1);
        SqlConnectionProvider provider = new SqlConnectionProvider(new SingleConnectionDataSource(conn));
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.setInitializedForTest(true);
        UUID account = UUID.randomUUID();
        RedemptionResult r = backend.redeem(UUID.randomUUID(), account, "dollar", Amount.of(new BigDecimal("1.00"), 2));
        assertTrue(r.isCommitted());
    }

    static final class ForUpdateSqliteDialect implements SqlDialect {
        @Override public boolean isMySQL() { return false; }
        @Override public String forUpdateClause() { return " FOR UPDATE"; }
    }

    @Test
    void concurrentRedeemsWithDistinctNoncesDoNotLoseUpdateMySqlMode() throws Exception {
        Path shared = dir.resolve("redeem-concurrent.db");
        // Need a provider that hands distinct physical connections sharing same SQLite file
        // but with a dialect that emits FOR UPDATE (to test MySQL row-lock semantics)
        // while DDL remains SQLite-compatible. We use a test dialect that isMySQL=false
        // for DDL but still returns FOR UPDATE.
        ReentrantLock forUpdateLock = new ReentrantLock();
        DataSource ds = new LockingFakeDataSource(shared, forUpdateLock);
        SqlConnectionProvider provider = new SqlConnectionProvider(ds);
        SqlBackend backend = new SqlBackend(provider, new ForUpdateSqliteDialect());
        backend.initialize();
        UUID account = UUID.randomUUID();
        backend.create(account, "alice", Map.of("dollar", Fixtures.amt("10.00")));

        UUID nonce1 = UUID.randomUUID();
        UUID nonce2 = UUID.randomUUID();
        Amount ten = Fixtures.amt("10.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<RedemptionResult> f1 = pool.submit(() -> backend.redeem(nonce1, account, "dollar", ten));
        Future<RedemptionResult> f2 = pool.submit(() -> backend.redeem(nonce2, account, "dollar", ten));
        RedemptionResult r1 = f1.get(10, TimeUnit.SECONDS);
        RedemptionResult r2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(r1.isCommitted(), "first redeem must commit");
        assertTrue(r2.isCommitted(), "second redeem with distinct nonce must commit");
        // Both must be observed as distinct audit rows and no lost update: 10 +10 +10 =30
        Account loaded = backend.load(account).orElseThrow();
        Amount finalBalance = loaded.balanceOf("dollar");
        assertEquals(Fixtures.amt("30.00").value(), finalBalance.value(),
                "MySQL FOR UPDATE must prevent lost update: initial 10 + two 10 deposits =30, got " + finalBalance);
        // Also verify two nonce rows and two transaction rows
        assertEquals(2, backend.loadAll().size());
        backend.close();
    }

    @Test
    void redeemUsesSameBorrowedConnectionNoNestedBorrow() throws Exception {
        Path db = dir.resolve("pool1-redeem-check.db");
        Connection shared = DriverManager.getConnection("jdbc:sqlite:" + db);
        try {
            SingleSlotDataSource pool = new SingleSlotDataSource(shared);
            SqlConnectionProvider provider = new SqlConnectionProvider(pool);
            SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
            backend.initialize();
            UUID account = UUID.randomUUID();
            backend.create(account, "carol", Map.of("dollar", Fixtures.amt("20.00")));
            int before = pool.getConnectionCallCount();
            assertEquals(1, before, "initialization should create one physical connection");
            RedemptionResult r = backend.redeem(UUID.randomUUID(), account, "dollar", Fixtures.amt("5.00"));
            assertTrue(r.isCommitted());
            assertEquals(before, pool.getConnectionCallCount(),
                    "a healthy idle slot must be reused; redeem must not nested-borrow or open a second physical connection");
            backend.close();
        } finally { shared.close(); }
    }

    // ---------- blocker 2: Hikari eviction failure fail-closed ----------

    @Test
    void abandonWithHikariEvictionFailureDoesNotCallOrdinaryClose() throws Exception {
        TrackingConnection tracking = new TrackingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("simulated eviction failure")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.abandonConnection(tracking));
        assertTrue(thrown.getMessage().contains("Failed to evict"), thrown.getMessage());
        assertFalse(tracking.closeCalled, "poisoned connection must NOT be returned via ordinary close()");
        assertTrue(tracking.abortCalled, "fail-closed must attempt abort to dispose physical connection");
        assertNotNull(thrown.getCause());
    }

    @Test
    void abandonSuppressedChainPreservedWhenCloseFailsAfterEvictSuccess() throws Exception {
        TrackingConnection tracking = new TrackingConnection(DriverManager.getConnection("jdbc:sqlite::memory:")) {
            @Override public void close() throws SQLException { closeCalled = true; throw new SQLException("close-boom"); }
        };
        HikariDataSource hikari = mock(HikariDataSource.class);
        doNothing().when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        // Eviction success must NOT ordinary-close dirty proxy; close-boom must not be observed.
        assertDoesNotThrow(() -> provider.abandonConnection(tracking));
        assertFalse(tracking.closeCalled, "eviction success must not trigger ordinary close (would NPE on dirty Hikari proxy)");
        verify(hikari).evictConnection(tracking);
    }

    @Test
    void borrowedConnectionCloseAttachesAbandonFailureToPrimarySuppressed() throws Exception {
        TrackingConnection tracking = new TrackingConnection(DriverManager.getConnection("jdbc:sqlite::memory:"));
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("simulated eviction failure")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        BorrowedConnection borrowed = new BorrowedConnection(tracking, provider);
        borrowed.markUnsafe();
        SQLException primary = new SQLException("primary-op-failure");
        // close(primary) must attach abandon failure to primary's suppressed chain, not throw
        borrowed.close(primary);
        assertTrue(primary.getSuppressed().length >= 1);
        boolean foundEvict = false;
        for (Throwable t : primary.getSuppressed()) {
            if (t.getMessage() != null && t.getMessage().contains("Failed to evict")) foundEvict = true;
        }
        assertTrue(foundEvict, "eviction failure must be suppressed on primary");
        assertFalse(tracking.closeCalled);
    }

    @Test
    void abandonWithHikariEvictionFailureDoesNotLeakAbortExecutor() throws Exception {
        AtomicReference<Executor> captured = new AtomicReference<>();
        // 捕捉 abort 所使用的 Executor，驗證不會產生未關閉的執行緒池
        Connection delegate = DriverManager.getConnection("jdbc:sqlite::memory:");
        TrackingConnection tracking = new TrackingConnection(delegate) {
            @Override public void abort(Executor e) throws SQLException {
                captured.set(e);
                super.abort(e);
            }
        };
        HikariDataSource hikari = mock(HikariDataSource.class);
        doThrow(new RuntimeException("simulated eviction failure")).when(hikari).evictConnection(any());
        SqlConnectionProvider provider = new SqlConnectionProvider((DataSource) hikari);
        SQLException thrown = assertThrows(SQLException.class, () -> provider.abandonConnection(tracking));
        assertTrue(thrown.getMessage().contains("Failed to evict"));
        assertFalse(tracking.closeCalled, "污染連線不得透過普通 close 回到池中");
        assertTrue(tracking.abortCalled, "fail-closed 應嘗試 abort 釋放實體連線");
        Executor exec = captured.get();
        assertNotNull(exec, "abort 應以 Executor 執行");
        if (exec instanceof ExecutorService es) {
            assertTrue(es.isShutdown() || es.isTerminated(),
                    "abort 使用的 ExecutorService 必須已關閉，避免 non-daemon 執行緒洩漏");
        }
        // 若為直接執行器（Runnable::run）則天然無資源洩漏，無需額外關閉
    }

    // ---------- helpers ----------

    private static final class SingleConnectionDataSource implements DataSource {
        private final Connection delegate;
        SingleConnectionDataSource(Connection c) { this.delegate = c; }
        @Override public Connection getConnection() { return delegate; }
        @Override public Connection getConnection(String u, String p) { return delegate; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class SingleSlotDataSource implements DataSource {
        private final Connection shared;
        private final AtomicInteger calls = new AtomicInteger(0);
        SingleSlotDataSource(Connection shared) { this.shared = shared; }
        @Override public Connection getConnection() throws SQLException {
            calls.incrementAndGet();
            return new PooledConnectionWrapper(shared);
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        int getConnectionCallCount() { return calls.get(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class PooledConnectionWrapper implements Connection {
        private final Connection delegate;
        PooledConnectionWrapper(Connection d) { this.delegate = d; }
        @Override public void close() {}
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void setAutoCommit(boolean a) throws SQLException { delegate.setAutoCommit(a); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean r) throws SQLException { delegate.setReadOnly(r); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String c) throws SQLException { delegate.setCatalog(c); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int l) throws SQLException { delegate.setTransactionIsolation(l); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int a, int b) throws SQLException { return delegate.createStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,int b,int c) throws SQLException { return delegate.prepareStatement(a,b,c); }
        @Override public CallableStatement prepareCall(String a,int b,int c) throws SQLException { return delegate.prepareCall(a,b,c); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public Statement createStatement(int a,int b,int c) throws SQLException { return delegate.createStatement(a,b,c); }
        @Override public PreparedStatement prepareStatement(String a,int b,int c,int d) throws SQLException { return delegate.prepareStatement(a,b,c,d); }
        @Override public CallableStatement prepareCall(String a,int b,int c,int d) throws SQLException { return delegate.prepareCall(a,b,c,d); }
        @Override public PreparedStatement prepareStatement(String a,int b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,int[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,String[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int t) throws SQLException { return delegate.isValid(t); }
        @Override public void setClientInfo(String n,String v) throws SQLClientInfoException { delegate.setClientInfo(n,v); }
        @Override public void setClientInfo(java.util.Properties p) throws SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String a,Object[] b) throws SQLException { return delegate.createArrayOf(a,b); }
        @Override public Struct createStruct(String a,Object[] b) throws SQLException { return delegate.createStruct(a,b); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(Executor e) throws SQLException { delegate.abort(e); }
        @Override public void setNetworkTimeout(Executor e,int m) throws SQLException { delegate.setNetworkTimeout(e,m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    private static class TrackingConnection implements Connection {
        final Connection delegate;
        boolean closeCalled = false;
        boolean abortCalled = false;
        TrackingConnection(Connection d) { this.delegate = d; }
        @Override public void close() throws SQLException { closeCalled = true; delegate.close(); }
        @Override public void abort(Executor e) throws SQLException { abortCalled = true; try { delegate.abort(e); } catch (Exception ex) { /* ignore for test */ } }
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void setAutoCommit(boolean a) throws SQLException { delegate.setAutoCommit(a); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean r) throws SQLException { delegate.setReadOnly(r); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String c) throws SQLException { delegate.setCatalog(c); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int l) throws SQLException { delegate.setTransactionIsolation(l); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int a,int b) throws SQLException { return delegate.createStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,int b,int c) throws SQLException { return delegate.prepareStatement(a,b,c); }
        @Override public CallableStatement prepareCall(String a,int b,int c) throws SQLException { return delegate.prepareCall(a,b,c); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public Statement createStatement(int a,int b,int c) throws SQLException { return delegate.createStatement(a,b,c); }
        @Override public PreparedStatement prepareStatement(String a,int b,int c,int d) throws SQLException { return delegate.prepareStatement(a,b,c,d); }
        @Override public CallableStatement prepareCall(String a,int b,int c,int d) throws SQLException { return delegate.prepareCall(a,b,c,d); }
        @Override public PreparedStatement prepareStatement(String a,int b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,int[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,String[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int t) throws SQLException { return delegate.isValid(t); }
        @Override public void setClientInfo(String n,String v) throws SQLClientInfoException { delegate.setClientInfo(n,v); }
        @Override public void setClientInfo(java.util.Properties p) throws SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String a,Object[] b) throws SQLException { return delegate.createArrayOf(a,b); }
        @Override public Struct createStruct(String a,Object[] b) throws SQLException { return delegate.createStruct(a,b); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void setNetworkTimeout(Executor e,int m) throws SQLException { delegate.setNetworkTimeout(e,m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    private static final class LockingFakeDataSource implements DataSource {
        private final Path file;
        private final ReentrantLock lock;
        LockingFakeDataSource(Path f, ReentrantLock l) { this.file = f; this.lock = l; }
        @Override public Connection getConnection() throws SQLException {
            Connection real = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            return new LockingConnection(real, lock);
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> c) { return null; }
        @Override public boolean isWrapperFor(Class<?> c) { return false; }
    }

    private static final class LockingConnection implements Connection {
        private final Connection delegate;
        private final ReentrantLock lock;
        private boolean lockHeld = false;
        private boolean transactionStarted = false;
        LockingConnection(Connection d, ReentrantLock l) { this.delegate = d; this.lock = l; }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException {
            if (sql.contains("FOR UPDATE")) {
                if (!transactionStarted) {
                    throw new SQLException("FOR UPDATE requested before transaction start");
                }
                if (!lockHeld) {
                    lock.lock();
                    lockHeld = true;
                }
                sql = sql.replace(" FOR UPDATE", "");
            }
            return delegate.prepareStatement(sql);
        }
        private void releaseLockIfHeld() {
            if (lockHeld) { lockHeld = false; lock.unlock(); }
        }
        @Override public void commit() throws SQLException {
            try { delegate.commit(); } finally { transactionStarted = false; releaseLockIfHeld(); }
        }
        @Override public void rollback() throws SQLException {
            try { delegate.rollback(); } finally { transactionStarted = false; releaseLockIfHeld(); }
        }
        @Override public void setAutoCommit(boolean a) throws SQLException {
            delegate.setAutoCommit(a);
            transactionStarted = !a;
            if (a) releaseLockIfHeld();
        }
        @Override public void close() throws SQLException { transactionStarted = false; releaseLockIfHeld(); delegate.close(); }
        @Override public void abort(Executor e) throws SQLException { transactionStarted = false; releaseLockIfHeld(); delegate.abort(e); }
        // delegate rest
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean r) throws SQLException { delegate.setReadOnly(r); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String c) throws SQLException { delegate.setCatalog(c); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int l) throws SQLException { delegate.setTransactionIsolation(l); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int a,int b) throws SQLException { return delegate.createStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,int b,int c) throws SQLException { return delegate.prepareStatement(a,b,c); }
        @Override public CallableStatement prepareCall(String a,int b,int c) throws SQLException { return delegate.prepareCall(a,b,c); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { delegate.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { delegate.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public Savepoint setSavepoint(String n) throws SQLException { return delegate.setSavepoint(n); }
        @Override public void rollback(Savepoint s) throws SQLException { delegate.rollback(s); }
        @Override public void releaseSavepoint(Savepoint s) throws SQLException { delegate.releaseSavepoint(s); }
        @Override public Statement createStatement(int a,int b,int c) throws SQLException { return delegate.createStatement(a,b,c); }
        @Override public PreparedStatement prepareStatement(String a,int b,int c,int d) throws SQLException { return delegate.prepareStatement(a,b,c,d); }
        @Override public CallableStatement prepareCall(String a,int b,int c,int d) throws SQLException { return delegate.prepareCall(a,b,c,d); }
        @Override public PreparedStatement prepareStatement(String a,int b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,int[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public PreparedStatement prepareStatement(String a,String[] b) throws SQLException { return delegate.prepareStatement(a,b); }
        @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int t) throws SQLException { return delegate.isValid(t); }
        @Override public void setClientInfo(String n,String v) throws SQLClientInfoException { delegate.setClientInfo(n,v); }
        @Override public void setClientInfo(java.util.Properties p) throws SQLClientInfoException { delegate.setClientInfo(p); }
        @Override public String getClientInfo(String n) throws SQLException { return delegate.getClientInfo(n); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String a,Object[] b) throws SQLException { return delegate.createArrayOf(a,b); }
        @Override public Struct createStruct(String a,Object[] b) throws SQLException { return delegate.createStruct(a,b); }
        @Override public void setSchema(String s) throws SQLException { delegate.setSchema(s); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void setNetworkTimeout(Executor e,int m) throws SQLException { delegate.setNetworkTimeout(e,m); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }
}
