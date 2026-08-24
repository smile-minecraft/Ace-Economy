package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import com.smile.aceeconomy.ports.persistence.NonceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;
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
 *   <li>Concurrency scope is a single backend instance / single JVM ({@code ReentrantLock} +
 *       copy-on-write). It does not provide cross-process first-writer-wins without an OS
 *       file lock or CAS; that remains a release gate.</li>
 * </ul>
 */
public final class JsonPersistenceBackend
        implements AccountRepository, TransactionRepository, PersistenceLifecycle,
        AtomicReversalStore, AtomicRedemptionStore, NonceStore {

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

    /**
     * Holds the SAME {@link ReentrantLock} that guards every repository method while the
     * composed operation runs, so ordinary writes cannot interleave inside an exclusive
     * window (for example a safety backup followed by a restore). Reentrant by construction:
     * operations inside the window may call backup()/restore() on this same instance.
     */
    @Override
    public <R> R runExclusive(ExclusiveOperation<R> operation)
            throws PersistenceException, IOException {
        lock.lock();
        try {
            return operation.run();
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
    public List<Account> listAll() {
        lock.lock();
        try {
            List<Account> result = new ArrayList<>(model.accounts.size());
            for (JsonModel.JsonAccount account : model.accounts.values()) {
                result.add(toAccount(account));
            }
            return List.copyOf(result);
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

    // ---------------- atomic reversal store ----------------

    @Override
    public void applyReversal(List<Account> updatedAccounts, List<Transaction> reversalRecords,
                              List<UUID> revertMarkerIds) throws PersistenceException {
        lock.lock();
        try {
            // Copy-on-write: build the mutated successor model first (validations run here,
            // before any live state changes), swap it in, then persist once. A failure at any
            // point restores the previous reference, so the file and the memory stay in sync
            // and no half-applied reversal is ever observable.
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            try {
                for (Account a : updatedAccounts) {
                    next.accounts.put(a.owner().toString(), toJsonAccount(a));
                }
                for (Transaction t : reversalRecords) {
                    insert(t);
                }
                for (UUID markerId : revertMarkerIds) {
                    markOnModel(next, markerId);
                }
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to apply reversal atomically at " + dataFile, e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------- nonce store ----------------

    @Override
    public boolean consume(UUID nonce) throws PersistenceException {
        lock.lock();
        try {
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return false;
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.nonces.put(key, java.time.Instant.now().toString());
            model = next;
            try {
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to persist consumed nonce " + nonce, e);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isConsumed(UUID nonce) throws PersistenceException {
        lock.lock();
        try {
            return model.nonces.containsKey(nonce.toString());
        } finally {
            lock.unlock();
        }
    }

    // ---------------- atomic redemption ----------------

    @Override
    public RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction)
            throws PersistenceException {
        lock.lock();
        try {
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return RedemptionResult.replay();
            }
            String ownerKey = transaction.accountId().toString();
            if (!model.accounts.containsKey(ownerKey)) {
                return RedemptionResult.accountMissing();
            }
            // All-or-none copy-on-write with the application-prepared account and transaction.
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(account.owner().toString(), toJsonAccount(account));
            next.transactions.add(JsonModel.JsonTransaction.fromDomain(transaction));
            next.nonces.put(key, java.time.Instant.now().toString());
            model = next;
            try {
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to persist banknote redemption " + nonce, e);
            }
            return RedemptionResult.committed(transaction.balanceBefore(), transaction.balanceAfter(),
                    transaction.id());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount)
            throws PersistenceException {
        lock.lock();
        try {
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return RedemptionResult.replay();
            }
            JsonModel.JsonAccount existing = model.accounts.get(accountId.toString());
            if (existing == null) {
                // Deliberately leave the nonce unconsumed so the physical note stays redeemable
                // once the account exists.
                return RedemptionResult.accountMissing();
            }
            Account account = toAccount(existing);
            Amount current = account.balanceOf(currencyId);
            Amount before = current == null ? Amount.zero(amount.scale()) : current;
            Account updated = account.deposit(currencyId, amount);
            Amount after = updated.balanceOf(currencyId);
            Transaction credit = new Transaction(UUID.randomUUID(), accountId, null,
                    Currency.normalizeId(currencyId), amount, TransactionType.DEPOSIT,
                    before, after, java.time.Instant.now(), "banknote-deposit");

            // Copy-on-write: build the successor model with balance + audit record + consumed
            // nonce, swap it in, then persist once. A write failure restores the previous
            // reference, so memory and file never disagree about whether the redemption happened.
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            next.transactions.add(JsonModel.JsonTransaction.fromDomain(credit));
            next.nonces.put(key, java.time.Instant.now().toString());
            model = next;
            try {
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to persist banknote redemption " + nonce, e);
            }
            return RedemptionResult.committed(before, after, credit.id());
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

    /**
     * Successor model for copy-on-write mutations: fresh collections sharing the (never
     * mutated in place afterwards) element objects. Markers that need {@code reverted=true}
     * are replaced with copies so the discarded predecessor stays untouched.
     */
    private static JsonModel shallowCopy(JsonModel source) {
        JsonModel next = new JsonModel();
        next.accounts.putAll(source.accounts);
        next.transactions.addAll(source.transactions);
        next.nonces.putAll(source.nonces);
        return next;
    }

    /** Set the reverted flag on one transaction inside the given model; unknown ids fail. */
    private static void markOnModel(JsonModel target, UUID transactionId) {
        String id = transactionId.toString();
        for (int i = 0; i < target.transactions.size(); i++) {
            JsonModel.JsonTransaction t = target.transactions.get(i);
            if (t.id.equals(id)) {
                target.transactions.set(i, copyReverted(t));
                return;
            }
        }
        throw new PersistenceException("Cannot mark unknown transaction reverted: " + transactionId);
    }

    private static JsonModel.JsonTransaction copyReverted(JsonModel.JsonTransaction t) {
        JsonModel.JsonTransaction c = new JsonModel.JsonTransaction();
        c.id = t.id;
        c.accountId = t.accountId;
        c.counterparty = t.counterparty;
        c.currencyId = t.currencyId;
        c.amount = t.amount;
        c.type = t.type;
        c.balanceBefore = t.balanceBefore;
        c.balanceAfter = t.balanceAfter;
        c.timestamp = t.timestamp;
        c.reason = t.reason;
        c.reverted = true;
        return c;
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
