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
    private volatile boolean initialized = false;

    public JsonPersistenceBackend(Path dataFile) {
        this.dataFile = dataFile;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new PersistenceException(
                    "Persistence backend not initialized: call initialize() or truncateAndRecreate() first (file: " + dataFile + ")");
        }
    }

    // ---------------- lifecycle ----------------

    @Override
    public void initialize() throws PersistenceException {
        lock.lock();
        try {
            // Fail-closed: any re-initialize that subsequently fails must not leave the
            // backend in an initialized state with stale in-memory data. Reset up front
            // and only set true after the file has been fully validated and adopted.
            initialized = false;
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
                // Validate records before adopting the loaded model: a corrupted file
                // (mismatched key/owner, duplicate owner, invalid balances etc.) must
                // not become the live state and must leave isInitialized() false.
                SnapshotPreflight.validateRecords(loaded);
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
            initialized = false;
            model = new JsonModel();
            persist();
            initialized = true;
        } catch (IOException e) {
            initialized = false;
            throw new PersistenceException("Failed to recreate JSON persistence at " + dataFile, e);
        } catch (RuntimeException | Error e) {
            initialized = false;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void backup(OutputStream out) throws PersistenceException, IOException {
        lock.lock();
        try {
            ensureInitialized();
            out.write(model.toJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void restore(InputStream in) throws PersistenceException, IOException {
        // Parse and validate fully BEFORE touching live state. The structure check in
        // JsonModel rejects missing or mistyped sections before empty collections could
        // wipe the live model, and record validation rejects mismatched identities.
        byte[] bytes = in.readAllBytes();
        JsonModel candidate = JsonModel.fromJson(new String(bytes, StandardCharsets.UTF_8));
        if (candidate.schemaVersion != JsonModel.SCHEMA_VERSION) {
            throw new PersistenceException(
                    "Backup schema version " + candidate.schemaVersion
                            + " incompatible with expected " + JsonModel.SCHEMA_VERSION);
        }
        SnapshotPreflight.validateRecords(candidate);
        lock.lock();
        try {
            ensureInitialized();
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
            ensureInitialized();
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
            ensureInitialized();
            return model.accounts.containsKey(uuid.toString());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Account> load(UUID uuid) {
        lock.lock();
        try {
            ensureInitialized();
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
            ensureInitialized();
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
            ensureInitialized();
            JsonModel.JsonAccount current = model.accounts.get(account.owner().toString());
            if (current != null && !sameAccount(toAccount(current), account)) {
                throw optimisticConflict(account.owner());
            }
            if (current != null) {
                return;
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            try {
                next.accounts.put(account.owner().toString(), toJsonAccount(account));
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to save account " + account.owner(), e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(Account expected, Account updated) {
        if (expected == null || updated == null || !expected.owner().equals(updated.owner())) {
            throw new PersistenceException("Invalid expected account snapshot");
        }
        lock.lock();
        try {
            ensureInitialized();
            JsonModel.JsonAccount current = model.accounts.get(updated.owner().toString());
            if (current == null || !sameAccount(toAccount(current), expected)) {
                throw optimisticConflict(updated.owner());
            }
            if (sameAccount(expected, updated)) {
                return;
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            model = next;
            try {
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to save account " + updated.owner(), e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
        lock.lock();
        try {
            ensureInitialized();
            JsonModel.JsonAccount existing = model.accounts.get(uuid.toString());
            if (existing != null) {
                return toAccount(existing); // safe: never overwrite an existing account
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            Account account;
            try {
                account = Account.create(uuid, ownerName, initialBalances);
                next.accounts.put(uuid.toString(), toJsonAccount(account));
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to create account " + uuid, e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
            return account;
        } finally {
            lock.unlock();
        }
    }

    // ---------------- transaction repository ----------------

    @Override
    public void append(Transaction transaction) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            try {
                insert(transaction);
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to append transaction " + transaction.id(), e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendBatch(List<Transaction> transactions) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            try {
                for (Transaction t : transactions) {
                    insert(t);
                }
                // Single atomic rewrite => all-or-none for the whole batch.
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to append transaction batch", e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markReverted(UUID transactionId) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            try {
                markOnModel(next, transactionId);
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to mark transaction reverted " + transactionId, e);
            } catch (RuntimeException e) {
                model = previous;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isReverted(UUID transactionId) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
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
            ensureInitialized();
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
            ensureInitialized();
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
        // Keep the snapshot overload source-compatible, but derive the mutation from the live
        // model and the signed audit intent instead of copying caller state.
        applyReversalDeltas(reversalRecords, revertMarkerIds);
    }

    private void applyReversalDeltas(List<Transaction> reversalRecords,
                                     List<UUID> revertMarkerIds) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            model = next;
            try {
                Map<UUID, Account> live = new LinkedHashMap<>();
                for (Transaction record : reversalRecords) {
                    UUID owner = record.accountId();
                    Account base = live.get(owner);
                    if (base == null) {
                        JsonModel.JsonAccount stored = next.accounts.get(owner.toString());
                        if (stored == null) {
                            throw new PersistenceException("account not found for reversal: " + owner);
                        }
                        base = toAccount(stored);
                    }
                    String currencyId = Currency.normalizeId(record.currencyId());
                    Amount delta = reversalDelta(record);
                    Amount before = base.balanceOf(currencyId);
                    if (before == null) {
                        before = Amount.zero(delta.scale());
                    }
                    Account updated = delta.isNegative()
                            ? base.withdraw(currencyId, delta.abs())
                            : base.deposit(currencyId, delta);
                    Amount after = updated.balanceOf(currencyId);
                    live.put(owner, updated);
                    next.accounts.put(owner.toString(), toJsonAccount(updated));
                    insert(authoritativeReversal(record, before, after));
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
            ensureInitialized();
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
            ensureInitialized();
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
            ensureInitialized();
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return RedemptionResult.replay();
            }
            String ownerKey = transaction.accountId().toString();
            JsonModel.JsonAccount stored = model.accounts.get(ownerKey);
            if (stored == null) {
                return RedemptionResult.accountMissing();
            }
            // The Account argument and balance fields on the Transaction are prepared snapshots.
            // Re-read the live model while holding the backend lock and build the audit record from
            // that live balance, matching the SQL backend's transaction-before-read contract.
            Account live = toAccount(stored);
            Amount current = live.balanceOf(transaction.currencyId());
            Amount before = current == null ? Amount.zero(transaction.amount().scale()) : current;
            Account updated = live.deposit(transaction.currencyId(), transaction.amount());
            Amount after = updated.balanceOf(transaction.currencyId());
            Transaction authoritative = new Transaction(transaction.id(), transaction.accountId(),
                    transaction.counterparty(), Currency.normalizeId(transaction.currencyId()),
                    transaction.amount(), TransactionType.DEPOSIT, before, after,
                    transaction.timestamp(), transaction.reason());

            // All-or-none copy-on-write with the live account, authoritative transaction and nonce.
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            next.transactions.add(JsonModel.JsonTransaction.fromDomain(authoritative));
            next.nonces.put(key, java.time.Instant.now().toString());
            model = next;
            try {
                persist();
            } catch (IOException e) {
                model = previous;
                throw new PersistenceException("Failed to persist banknote redemption " + nonce, e);
            }
            return RedemptionResult.committed(before, after, transaction.id());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount)
            throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
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

    private static PersistenceException optimisticConflict(UUID owner) {
        return new PersistenceException("Optimistic account conflict for " + owner
                + "; the caller snapshot is stale");
    }

    private static boolean sameAccount(Account left, Account right) {
        if (!left.owner().equals(right.owner()) || !left.ownerName().equals(right.ownerName())
                || !left.balances().keySet().equals(right.balances().keySet())) {
            return false;
        }
        for (String currency : left.balances().keySet()) {
            if (left.balances().get(currency).compareTo(right.balances().get(currency)) != 0) {
                return false;
            }
        }
        return true;
    }

    private static Amount reversalDelta(Transaction record) {
        return switch (record.type()) {
            case DEPOSIT, TRANSFER_IN -> record.amount();
            case WITHDRAW, TRANSFER_OUT -> record.amount().negate();
            case SET -> {
                if (record.balanceBefore() == null || record.balanceAfter() == null) {
                    throw new PersistenceException("SET reversal requires balanceBefore and balanceAfter");
                }
                yield record.balanceAfter().subtract(record.balanceBefore());
            }
        };
    }

    private static Transaction authoritativeReversal(Transaction record, Amount before, Amount after) {
        Amount auditAmount = record.type() == TransactionType.SET ? after : record.amount().abs();
        return new Transaction(record.id(), record.accountId(), record.counterparty(),
                Currency.normalizeId(record.currencyId()), auditAmount, record.type(), before, after,
                record.timestamp(), record.reason());
    }

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
