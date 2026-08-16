package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed v2 persistence backend (JSON). Implements the account, transaction and lifecycle
 * ports with a single atomic file as the source of truth.
 *
 * <p>Durability / transaction boundary:</p>
 * <ul>
 *   <li>Every mutation rewrites the whole model through a temp file + atomic rename, so a crash
 *       mid-write never corrupts the live file (all-or-none at the file level).</li>
 *   <li>{@link #appendBatch} updates the model once and performs a single atomic rewrite, so a
 *       transfer's out+in records are persisted together or not at all.</li>
 *   <li>{@link #restore} parses and validates the incoming snapshot fully before any live state is
 *       touched, so a corrupt backup cannot destroy existing data.</li>
 * </ul>
 */
public final class JsonPersistenceBackend
        implements AccountRepository, TransactionRepository, PersistenceLifecycle {

    private final Path dataFile;
    private final ReentrantLock lock = new ReentrantLock();
    private JsonModel model = new JsonModel();
    private boolean initialized = false;

    public JsonPersistenceBackend(Path dataFile) {
        this.dataFile = dataFile;
    }

    // ---------------- lifecycle ----------------

    @Override
    public void initialize() throws PersistenceException {
        lock.lock();
        try {
            Path parent = dataFile.getParent();
            if (parent != null) {
                // If the parent cannot be created (e.g. it is an existing regular file) this
                // throws and no data file is produced: initialization failure leaves nothing behind.
                Files.createDirectories(parent);
            }
            if (Files.exists(dataFile)) {
                JsonModel loaded = loadFromFile();
                if (loaded.schemaVersion != JsonModel.SCHEMA_VERSION) {
                    throw new PersistenceException(
                            "Incompatible JSON schema version " + loaded.schemaVersion
                                    + "; expected " + JsonModel.SCHEMA_VERSION
                                    + ". Call truncateAndRecreate().");
                }
                model = loaded;
            } else {
                model = new JsonModel();
                persist();
            }
            initialized = true;
        } catch (IOException e) {
            throw new PersistenceException("Failed to initialize JSON persistence at " + dataFile, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            initialized = false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public int schemaVersion() throws PersistenceException {
        return JsonModel.SCHEMA_VERSION;
    }

    @Override
    public boolean needsRecreation() throws PersistenceException {
        lock.lock();
        try {
            if (!Files.exists(dataFile)) {
                return false; // fresh install, nothing to recreate
            }
            JsonModel loaded = loadFromFile();
            return loaded.schemaVersion != JsonModel.SCHEMA_VERSION;
        } catch (IOException e) {
            throw new PersistenceException("Failed to read JSON persistence at " + dataFile, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void truncateAndRecreate() throws PersistenceException {
        lock.lock();
        try {
            model = new JsonModel();
            persist();
            initialized = true;
        } catch (IOException e) {
            throw new PersistenceException("Failed to recreate JSON persistence at " + dataFile, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void backup(OutputStream out) throws PersistenceException, IOException {
        lock.lock();
        try {
            out.write(model.toJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void restore(InputStream in) throws PersistenceException, IOException {
        // Parse and validate fully BEFORE touching live state.
        byte[] bytes = in.readAllBytes();
        JsonModel candidate = JsonModel.fromJson(new String(bytes, StandardCharsets.UTF_8));
        if (candidate.schemaVersion != JsonModel.SCHEMA_VERSION) {
            throw new PersistenceException(
                    "Backup schema version " + candidate.schemaVersion
                            + " incompatible with expected " + JsonModel.SCHEMA_VERSION);
        }
        lock.lock();
        try {
            JsonModel previous = model;
            try {
                model = candidate;
                persist();
            } catch (IOException e) {
                model = previous; // roll back in-memory state on write failure
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------- account repository ----------------

    @Override
    public boolean exists(UUID uuid) {
        lock.lock();
        try {
            return model.accounts.containsKey(uuid.toString());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Account> load(UUID uuid) {
        lock.lock();
        try {
            JsonModel.JsonAccount a = model.accounts.get(uuid.toString());
            return a == null ? Optional.empty() : Optional.of(toAccount(a));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(Account account) {
        lock.lock();
        try {
            model.accounts.put(account.owner().toString(), toJsonAccount(account));
            persist();
        } catch (IOException e) {
            throw new PersistenceException("Failed to save account " + account.owner(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
        lock.lock();
        try {
            JsonModel.JsonAccount existing = model.accounts.get(uuid.toString());
            if (existing != null) {
                return toAccount(existing); // safe: never overwrite an existing account
            }
            Account account = Account.create(uuid, ownerName, initialBalances);
            model.accounts.put(uuid.toString(), toJsonAccount(account));
            persist();
            return account;
        } catch (IOException e) {
            throw new PersistenceException("Failed to create account " + uuid, e);
        } finally {
            lock.unlock();
        }
    }

    // ---------------- transaction repository ----------------

    @Override
    public void append(Transaction transaction) throws PersistenceException {
        lock.lock();
        try {
            insert(transaction);
            persist();
        } catch (IOException e) {
            throw new PersistenceException("Failed to append transaction " + transaction.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendBatch(List<Transaction> transactions) throws PersistenceException {
        lock.lock();
        try {
            for (Transaction t : transactions) {
                insert(t);
            }
            // Single atomic rewrite => all-or-none for the whole batch.
            persist();
        } catch (IOException e) {
            throw new PersistenceException("Failed to append transaction batch", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markReverted(UUID transactionId) throws PersistenceException {
        lock.lock();
        try {
            boolean found = false;
            for (JsonModel.JsonTransaction t : model.transactions) {
                if (t.id.equals(transactionId.toString())) {
                    t.reverted = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new PersistenceException("Cannot mark unknown transaction reverted: " + transactionId);
            }
            persist();
        } catch (IOException e) {
            throw new PersistenceException("Failed to mark transaction reverted " + transactionId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isReverted(UUID transactionId) throws PersistenceException {
        lock.lock();
        try {
            for (JsonModel.JsonTransaction t : model.transactions) {
                if (t.id.equals(transactionId.toString())) {
                    return t.reverted;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Transaction> loadByAccount(UUID accountId) throws PersistenceException {
        lock.lock();
        try {
            List<Transaction> result = new ArrayList<>();
            String id = accountId.toString();
            for (JsonModel.JsonTransaction t : model.transactions) {
                if (id.equals(t.accountId)) {
                    result.add(t.toDomain());
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Transaction> loadAll() throws PersistenceException {
        lock.lock();
        try {
            List<Transaction> result = new ArrayList<>(model.transactions.size());
            for (JsonModel.JsonTransaction t : model.transactions) {
                result.add(t.toDomain());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    // ---------------- internals ----------------

    private void insert(Transaction t) {
        String id = t.id().toString();
        for (JsonModel.JsonTransaction existing : model.transactions) {
            if (existing.id.equals(id)) {
                throw new PersistenceException("Duplicate transaction id: " + id);
            }
        }
        model.transactions.add(JsonModel.JsonTransaction.fromDomain(t));
    }

    private JsonModel loadFromFile() throws IOException {
        String text = Files.readString(dataFile, StandardCharsets.UTF_8);
        return JsonModel.fromJson(text);
    }

    private void persist() throws IOException {
        String json = model.toJson();
        Path parent = dataFile.getParent();
        Path tmp = parent == null
                ? Files.createTempFile("ace-json-", ".tmp")
                : Files.createTempFile(parent, "ace-json-", ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonModel.JsonAccount toJsonAccount(Account account) {
        JsonModel.JsonAccount a = new JsonModel.JsonAccount();
        a.owner = account.owner().toString();
        a.ownerName = account.ownerName();
        for (Map.Entry<String, Amount> e : account.balances().entrySet()) {
            a.balances.put(e.getKey(), JsonModel.amountToString(e.getValue()));
        }
        return a;
    }

    private static Account toAccount(JsonModel.JsonAccount a) {
        Map<String, Amount> balances = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : a.balances.entrySet()) {
            balances.put(e.getKey(), JsonModel.stringToAmount(e.getValue()));
        }
        return Account.create(UUID.fromString(a.owner), a.ownerName, balances);
    }
}
