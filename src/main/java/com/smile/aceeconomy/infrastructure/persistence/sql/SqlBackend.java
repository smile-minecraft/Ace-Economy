package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed v2 persistence backend. Implements the account, transaction and lifecycle ports for
 * both SQLite and MySQL through a single {@link SqlDialect}.
 *
 * <p>Transaction boundary:</p>
 * <ul>
 *   <li>Schema creation runs inside one transaction; on failure it rolls back (SQLite DDL is
 *       transactional) and rethrows, leaving no partial schema. {@code CREATE TABLE IF NOT EXISTS}
 *       plus {@code INSERT IGNORE} make a restart idempotent.</li>
 *   <li>{@link #appendBatch} writes every record inside one transaction: either all are committed
 *       or none are (all-or-none).</li>
 *   <li>{@link #markReverted} is the rollback marker write; it is idempotent and isolated.</li>
 *   <li>{@link #restore} parses and validates the snapshot fully before any live row is touched,
 *       so a corrupt backup cannot destroy existing data.</li>
 * </ul>
 *
 * <p>Thread safety (durable contract): every public method that touches {@link #connection}
 * is {@code synchronized} on this backend instance. A single {@link SqlBackend} therefore
 * serializes all JDBC access; concurrent callers see one operation at a time. This matches
 * {@link com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend}, which
 * guards the in-memory model with a {@code ReentrantLock}, and keeps domain semantics
 * identical across backends (no hidden concurrent-write surprises when an operator switches
 * {@code storage.type}).</p>
 *
 * <p>Only {@code java.sql} is used here; no vendor driver types leak into the port boundary.</p>
 */
public final class SqlBackend
        implements AccountRepository, TransactionRepository, PersistenceLifecycle {

    private final Connection connection;
    private final SqlDialect dialect;
    private volatile boolean initialized = false;

    public SqlBackend(Connection connection, SqlDialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
    }

    // ---------------- lifecycle ----------------

    @Override
    public synchronized void initialize() throws PersistenceException {
        try {
            createSchema();
            initialized = true;
        } catch (SQLException e) {
            throw new PersistenceException("Failed to initialize v2 SQL schema", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignore) {
            // best-effort; resource release on shutdown
        }
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public synchronized int schemaVersion() throws PersistenceException {
        try {
            if (!tableExists(V2Schema.schemaTable())) {
                return 0;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT version FROM " + V2Schema.schemaTable())) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read schema version", e);
        }
    }

    @Override
    public synchronized boolean needsRecreation() throws PersistenceException {
        try {
            if (!tableExists(V2Schema.schemaTable())) {
                // Fresh only if no v2 table exists at all; otherwise a partial init left tables behind.
                return tableExists(V2Schema.accountsTable())
                        || tableExists(V2Schema.balancesTable())
                        || tableExists(V2Schema.transactionsTable());
            }
            int v = schemaVersion();
            return !SchemaVersion.isCompatible(v);
        } catch (SQLException e) {
            throw new PersistenceException("Failed to inspect schema state", e);
        }
    }

    @Override
    public synchronized void truncateAndRecreate() throws PersistenceException {
        try {
            dropSchema();
            createSchema();
            initialized = true;
        } catch (SQLException e) {
            throw new PersistenceException("Failed to recreate v2 SQL schema", e);
        }
    }

    @Override
    public synchronized void backup(OutputStream out) throws PersistenceException, IOException {
        JsonModel model = loadAllIntoModel();
        out.write(model.toJson().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public synchronized void restore(InputStream in) throws PersistenceException, IOException {
        byte[] bytes = in.readAllBytes();
        JsonModel candidate = JsonModel.fromJson(new String(bytes, StandardCharsets.UTF_8));
        if (candidate.schemaVersion != JsonModel.SCHEMA_VERSION) {
            throw new PersistenceException(
                    "Backup schema version " + candidate.schemaVersion
                            + " incompatible with expected " + JsonModel.SCHEMA_VERSION);
        }
        try {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("DELETE FROM " + V2Schema.transactionsTable());
                st.executeUpdate("DELETE FROM " + V2Schema.balancesTable());
                st.executeUpdate("DELETE FROM " + V2Schema.accountsTable());
            }
            for (JsonModel.JsonAccount a : candidate.accounts.values()) {
                insertAccount(a);
            }
            for (JsonModel.JsonTransaction t : candidate.transactions) {
                insertTransaction(t);
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignore) {
                // best-effort
            }
            throw new PersistenceException("Failed to restore from backup", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    // ---------------- account repository ----------------

    @Override
    public synchronized boolean exists(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM " + V2Schema.accountsTable() + " WHERE owner = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to check account " + uuid, e);
        }
    }

    @Override
    public synchronized Optional<Account> load(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT owner_name FROM " + V2Schema.accountsTable() + " WHERE owner = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String ownerName = rs.getString(1);
                Map<String, Amount> balances = loadBalances(uuid);
                return Optional.of(Account.create(uuid, ownerName, balances));
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load account " + uuid, e);
        }
    }

    @Override
    public synchronized List<Account> listAll() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT owner, owner_name FROM " + V2Schema.accountsTable() + " ORDER BY owner")) {
            List<Account> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID owner = UUID.fromString(rs.getString("owner"));
                    result.add(Account.create(owner, rs.getString("owner_name"), loadBalances(owner)));
                }
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list accounts", e);
        }
    }

    @Override
    public synchronized void save(Account account) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM " + V2Schema.balancesTable() + " WHERE owner = ?")) {
                del.setString(1, account.owner().toString());
                del.executeUpdate();
            }
            try (PreparedStatement ins = connection.prepareStatement(
                    "REPLACE INTO " + V2Schema.accountsTable() + " (owner, owner_name) VALUES (?, ?)")) {
                ins.setString(1, account.owner().toString());
                ins.setString(2, account.ownerName());
                ins.executeUpdate();
            }
            try (PreparedStatement bal = connection.prepareStatement(
                    "REPLACE INTO " + V2Schema.balancesTable()
                            + " (owner, currency_id, amount) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, Amount> e : account.balances().entrySet()) {
                    bal.setString(1, account.owner().toString());
                    bal.setString(2, e.getKey());
                    bal.setString(3, amountToString(e.getValue()));
                    bal.addBatch();
                }
                bal.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignore) {
                // best-effort
            }
            throw new PersistenceException("Failed to save account " + account.owner(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    @Override
    public synchronized Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
        Optional<Account> existing = load(uuid);
        if (existing.isPresent()) {
            return existing.get(); // safe: never overwrite an existing account
        }
        Account account = Account.create(uuid, ownerName, initialBalances);
        save(account);
        return account;
    }

    // ---------------- transaction repository ----------------

    @Override
    public synchronized void append(Transaction transaction) throws PersistenceException {
        try {
            insertTransactionRow(transaction, false);
        } catch (SQLException e) {
            throw new PersistenceException("Failed to append transaction " + transaction.id(), e);
        }
    }

    @Override
    public synchronized void appendBatch(List<Transaction> transactions) throws PersistenceException {
        if (transactions.isEmpty()) {
            return;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(transactionInsertSql())) {
                for (Transaction t : transactions) {
                    bindTransaction(ps, t, false);
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to append transaction batch", e);
        }
    }

    @Override
    public synchronized void markReverted(UUID transactionId) throws PersistenceException {
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + V2Schema.transactionsTable() + " SET reverted = ? WHERE id = ?")) {
                ps.setBoolean(1, true);
                ps.setString(2, transactionId.toString());
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    throw new PersistenceException(
                            "Cannot mark unknown transaction reverted: " + transactionId);
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to mark transaction reverted " + transactionId, e);
        }
    }

    @Override
    public synchronized boolean isReverted(UUID transactionId) throws PersistenceException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT reverted FROM " + V2Schema.transactionsTable() + " WHERE id = ?")) {
            ps.setString(1, transactionId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read reverted flag " + transactionId, e);
        }
    }

    @Override
    public synchronized List<Transaction> loadByAccount(UUID accountId) throws PersistenceException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + V2Schema.transactionsTable()
                        + " WHERE account_id = ? ORDER BY timestamp")) {
            ps.setString(1, accountId.toString());
            return readTransactions(ps.executeQuery());
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load transactions for " + accountId, e);
        }
    }

    @Override
    public synchronized List<Transaction> loadAll() throws PersistenceException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + V2Schema.transactionsTable() + " ORDER BY timestamp")) {
            return readTransactions(ps.executeQuery());
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load transactions", e);
        }
    }

    // ---------------- internals ----------------

    private void createSchema() throws SQLException {
        connection.setAutoCommit(false);
        try {
            for (String ddl : V2Schema.ddlStatements(dialect)) {
                try (Statement st = connection.createStatement()) {
                    st.execute(ddl);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(V2Schema.versionInsertSql(dialect))) {
                ps.setString(1, Instant.now().toString());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void dropSchema() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.transactionsTable());
            st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.balancesTable());
            st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.accountsTable());
            st.executeUpdate("DROP TABLE IF EXISTS " + V2Schema.schemaTable());
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private Map<String, Amount> loadBalances(UUID uuid) throws SQLException {
        Map<String, Amount> balances = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT currency_id, amount FROM " + V2Schema.balancesTable() + " WHERE owner = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(rs.getString(1), stringToAmount(rs.getString(2)));
                }
            }
        }
        return balances;
    }

    private void insertAccount(JsonModel.JsonAccount a) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO " + V2Schema.accountsTable() + " (owner, owner_name) VALUES (?, ?)")) {
            ps.setString(1, a.owner);
            ps.setString(2, a.ownerName);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO " + V2Schema.balancesTable()
                        + " (owner, currency_id, amount) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, String> e : a.balances.entrySet()) {
                ps.setString(1, a.owner);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTransaction(JsonModel.JsonTransaction t) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(transactionInsertSql())) {
            bindTransaction(ps, t, t.reverted);
            ps.executeUpdate();
        }
    }

    private void insertTransactionRow(Transaction t, boolean reverted) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(transactionInsertSql())) {
            bindTransaction(ps, t, reverted);
            ps.executeUpdate();
        }
    }

    private String transactionInsertSql() {
        // Plain INSERT (no IGNORE): a duplicate id or a null amount must fail loudly so the
        // caller's transaction boundary rolls back instead of silently dropping a record.
        return "INSERT INTO " + V2Schema.transactionsTable()
                + " (id, account_id, counterparty, currency_id, amount, type,"
                + " balance_before, balance_after, timestamp, reason, reverted)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private void bindTransaction(PreparedStatement ps, Transaction t, boolean reverted) throws SQLException {
        ps.setString(1, t.id().toString());
        ps.setString(2, t.accountId().toString());
        ps.setString(3, t.counterparty() == null ? null : t.counterparty().toString());
        ps.setString(4, t.currencyId());
        ps.setString(5, amountToString(t.amount()));
        ps.setString(6, t.type().name());
        ps.setString(7, t.balanceBefore() == null ? null : amountToString(t.balanceBefore()));
        ps.setString(8, t.balanceAfter() == null ? null : amountToString(t.balanceAfter()));
        ps.setString(9, t.timestamp().toString());
        ps.setString(10, t.reason());
        ps.setBoolean(11, reverted);
    }

    private void bindTransaction(PreparedStatement ps, JsonModel.JsonTransaction t, boolean reverted)
            throws SQLException {
        ps.setString(1, t.id);
        ps.setString(2, t.accountId);
        ps.setString(3, t.counterparty);
        ps.setString(4, t.currencyId);
        ps.setString(5, t.amount);
        ps.setString(6, t.type);
        ps.setString(7, t.balanceBefore);
        ps.setString(8, t.balanceAfter);
        ps.setString(9, t.timestamp);
        ps.setString(10, t.reason);
        ps.setBoolean(11, reverted);
    }

    private List<Transaction> readTransactions(ResultSet rs) throws SQLException {
        List<Transaction> result = new ArrayList<>();
        while (rs.next()) {
            result.add(new Transaction(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("account_id")),
                    rs.getString("counterparty") == null ? null : UUID.fromString(rs.getString("counterparty")),
                    rs.getString("currency_id"),
                    stringToAmount(rs.getString("amount")),
                    TransactionType.valueOf(rs.getString("type")),
                    rs.getString("balance_before") == null ? null : stringToAmount(rs.getString("balance_before")),
                    rs.getString("balance_after") == null ? null : stringToAmount(rs.getString("balance_after")),
                    Instant.parse(rs.getString("timestamp")),
                    rs.getString("reason")));
        }
        return result;
    }

    private JsonModel loadAllIntoModel() throws PersistenceException {
        JsonModel model = new JsonModel();
        try (PreparedStatement acc = connection.prepareStatement(
                "SELECT owner, owner_name FROM " + V2Schema.accountsTable());
             ResultSet ars = acc.executeQuery()) {
            while (ars.next()) {
                JsonModel.JsonAccount a = new JsonModel.JsonAccount();
                a.owner = ars.getString("owner");
                a.ownerName = ars.getString("owner_name");
                model.accounts.put(a.owner, a);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read accounts for backup", e);
        }
        try (PreparedStatement bal = connection.prepareStatement(
                "SELECT owner, currency_id, amount FROM " + V2Schema.balancesTable());
             ResultSet brs = bal.executeQuery()) {
            while (brs.next()) {
                String owner = brs.getString("owner");
                JsonModel.JsonAccount a = model.accounts.get(owner);
                if (a != null) {
                    a.balances.put(brs.getString("currency_id"), brs.getString("amount"));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read balances for backup", e);
        }
        try (PreparedStatement tx = connection.prepareStatement(
                "SELECT * FROM " + V2Schema.transactionsTable());
             ResultSet trs = tx.executeQuery()) {
            while (trs.next()) {
                JsonModel.JsonTransaction t = new JsonModel.JsonTransaction();
                t.id = trs.getString("id");
                t.accountId = trs.getString("account_id");
                t.counterparty = trs.getString("counterparty");
                t.currencyId = trs.getString("currency_id");
                t.amount = trs.getString("amount");
                t.type = trs.getString("type");
                t.balanceBefore = trs.getString("balance_before");
                t.balanceAfter = trs.getString("balance_after");
                t.timestamp = trs.getString("timestamp");
                t.reason = trs.getString("reason");
                t.reverted = trs.getBoolean("reverted");
                model.transactions.add(t);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read transactions for backup", e);
        }
        return model;
    }

    private boolean tableExists(String tableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private static String amountToString(Amount a) {
        return a == null ? null : a.value().toPlainString();
    }

    private static Amount stringToAmount(String s) {
        BigDecimal bd = new BigDecimal(s);
        return Amount.of(bd, bd.scale());
    }
}
