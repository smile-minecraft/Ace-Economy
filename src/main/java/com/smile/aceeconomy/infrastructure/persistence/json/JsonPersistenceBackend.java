package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.AmountCap;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.operations.LeaderboardRepository;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import com.smile.aceeconomy.ports.persistence.AdminMutationResult;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.AtomicImportStore;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import com.smile.aceeconomy.ports.persistence.AtomicTransferStore;
import com.smile.aceeconomy.ports.persistence.CommittedImportException;
import com.smile.aceeconomy.ports.persistence.CommittedTransferException;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.IssuanceRefundResult;
import com.smile.aceeconomy.ports.persistence.NonceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.ImportCommitResult;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Durability / transaction boundary (process-crash scope only; hardware power loss
 * has not been verified — see below):</p>
 * <ul>
 *   <li>Every mutation rewrites the whole model through a temp file next to the live
 *       file, forces the temp content to storage, then replaces the live file with an
 *       atomic rename and syncs the file and its directory. The live file is therefore
 *       always either the previous or the new complete snapshot, never a half-written
 *       one. A failed write attempts to clean up its temp file on a best-effort basis;
 *       cleanup may itself fail, and a killed process skips cleanup entirely, so a temp
 *       file may be left behind — it never collides with the live file and is ignored.</li>
 *   <li>{@link #appendBatch} updates the model once and performs a single publish, so a
 *       transfer's out+in records are persisted together or not at all.</li>
 *   <li>{@link #restore} parses and validates the incoming snapshot fully before any live state is
 *       touched, so a corrupt backup cannot destroy existing data.</li>
 *   <li>If the live file was already replaced but the durability sync could not be
 *       confirmed, the backend keeps the published state in memory, reports the failure
 *       as committed ({@code PersistenceException.isCommitted() == true}, never a safe
 *       retry), and halts further writes until {@link #initialize()} re-reads the file.
 *       Reads keep serving the published state while halted.</li>
 *   <li>The filesystem must support atomic file replacement. When it does not, the
 *       backend refuses the publish with an explicit diagnosis instead of silently
 *       falling back to a non-atomic replacement.</li>
 *   <li>Concurrency scope is strictly a single {@code JsonPersistenceBackend} instance within a
 *       single JVM ({@code ReentrantLock} + copy-on-write). v2.2 does not provide cross-process or
 *       multi-instance file locking / CAS; two backends on the same file (even in one JVM) or in
 *       separate processes have no first-writer-wins guarantee. Deploy only one live instance per
 *       file.</li>
 * </ul>
 *
 * <p>Honest boundary: the fsync + atomic-rename + halted-writes design narrows the loss
 * window to operating-system and hardware behavior (page cache, drive write cache,
 * power loss), which this project has not tested. Do not claim proven safety against
 * arbitrary hardware power loss on the basis of the automated tests: they simulate IO
 * failures through an injectable publish path, they never cut power.</p>
 *
 * <p>Honest boundary: the instance-local lock makes concurrent {@code save(expected, updated)} on
 * the same object linearizable; callers must not share a file across backends and must not rely on
 * this backend for distributed coordination.</p>
 */
public final class JsonPersistenceBackend
        implements AccountRepository, TransactionRepository, PersistenceLifecycle,
        AtomicReversalStore, AtomicRedemptionStore, AtomicTransferStore, AtomicImportStore,
        com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore,
        AtomicBanknoteIssuanceStore,
        NonceStore, LeaderboardRepository,
        com.smile.aceeconomy.ports.operations.OperationAuditStore {

    private final Path dataFile;
    /** Instance-local only: not a file lock, not cross-process. See class javadoc. */
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * Durable issuance progress, kept in the additive {@code issuances} model
     * section and published with the same single-publish commit as balances
     * and audit rows. Guarded by {@link #lock}.
     */
    private JsonModel model = new JsonModel();
    private volatile boolean initialized = false;
    private final JsonFilePublisher publisher;
    /**
     * Set when a publish replaced the live file but the durability sync could not be
     * confirmed. Further mutations fail fast until {@link #initialize()} re-reads the
     * file; reads keep serving the published state. Guarded by {@link #lock}.
     */
    private boolean writeHalted = false;
    private String haltReason = null;

    public JsonPersistenceBackend(Path dataFile) {
        this(dataFile, new DefaultJsonFilePublisher());
    }

    /** Injectable publish path for tests; production callers use the public constructor. */
    JsonPersistenceBackend(Path dataFile, JsonFilePublisher publisher) {
        this.dataFile = dataFile;
        this.publisher = publisher;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new PersistenceException(
                    "Persistence backend not initialized: call initialize() or truncateAndRecreate() first (file: " + dataFile + ")");
        }
    }

    private void ensureWritable() {
        if (writeHalted) {
            throw new PersistenceException(
                    "JSON persistence halted writes after a publish whose durability could not be confirmed"
                            + " (file=" + dataFile + (haltReason == null ? "" : "; " + haltReason)
                            + "). Verify the file, then call initialize() to resume.");
        }
    }

    private void markHalted(String reason) {
        writeHalted = true;
        haltReason = reason;
    }

    /**
     * Swaps in the successor model and publishes it. A pre-publish failure rolls the
     * in-memory model back and stays retryable ({@code committed=false}); a failure
     * after the live file was replaced keeps the published model, halts further
     * writes, and reports {@code committed=true} so callers never retry it as an
     * uncommitted write.
     */
    private void commit(JsonModel previous, JsonModel next, String failureMessage) {
        model = next;
        try {
            persist();
        } catch (JsonPostPublishException e) {
            markHalted(e.getMessage());
            throw new PersistenceException(failureMessage
                    + "; the new data was already published but its durability sync failed"
                    + " (file=" + dataFile + "). Writes are halted: verify the file,"
                    + " then call initialize() to resume.", e, true);
        } catch (IOException e) {
            model = previous;
            throw new PersistenceException(failureMessage, e);
        } catch (RuntimeException | Error e) {
            model = previous;
            throw e;
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
                try {
                    persist();
                } catch (JsonPostPublishException e) {
                    markHalted(e.getMessage());
                    throw new PersistenceException("Initialized " + dataFile
                            + " but its durability sync failed: writes are halted until the file"
                            + " is verified and initialize() runs again.", e, true);
                }
            }
            writeHalted = false;
            haltReason = null;
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
            writeHalted = false;
            haltReason = null;
            initialized = true;
        } catch (JsonPostPublishException e) {
            initialized = false;
            markHalted(e.getMessage());
            throw new PersistenceException("Recreated " + dataFile
                    + " but its durability sync failed: writes are halted until the file"
                    + " is verified and initialize() runs again.", e, true);
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
        // Restore-only cap gate on balance strings (audit evidence stays
        // restorable, plain loads stay lenient): a snapshot that would store an
        // over-cap or over-wide balance is refused before the live model moves.
        SnapshotPreflight.checkRestoreCaps(candidate);
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            // Restore guard inside the exclusive window after the safety
            // backup: live issuance progress must survive the swap identically,
            // except a pre-upgrade candidate without issuance progress, which
            // may only replace live progress that cannot leave a redeemable
            // note behind (deduction-free, redeemed or aborted records). A live
            // delivered, possibly-delivered, refunded-voided or undecidable record
            // refuses the empty candidate so the balance cannot revert while a
            // physical note stays or becomes redeemable again.
            // Candidate newcomers with debit evidence must carry their
            // authoritative debit. A violation throws before the live model
            // moves, so live bytes are unchanged.
            SnapshotPreflight.checkIssuanceRestoreGuard(model, candidate);
            JsonModel previous = model;
            try {
                model = candidate;
                persist();
            } catch (JsonPostPublishException e) {
                markHalted(e.getMessage());
                throw new PersistenceException("Restore published to " + dataFile
                        + " but its durability sync failed: writes are halted until the file"
                        + " is verified and initialize() runs again.", e, true);
            } catch (IOException e) {
                model = previous; // roll back in-memory state on write failure
                throw e;
            } catch (RuntimeException | Error e) {
                // Pre-publish failures that are not IOExceptions (for example the
                // atomic-replacement diagnosis): nothing was published, so the live
                // model must be restored just like the checked-IO path above.
                model = previous;
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
            ensureWritable();
            JsonModel.JsonAccount current = model.accounts.get(account.owner().toString());
            if (current != null && !sameAccount(toAccount(current), account)) {
                throw optimisticConflict(account.owner());
            }
            if (current != null) {
                return;
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(account.owner().toString(), toJsonAccount(account));
            commit(previous, next, "Failed to save account " + account.owner());
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
            ensureWritable();
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
            commit(previous, next, "Failed to save account " + updated.owner());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel.JsonAccount existing = model.accounts.get(uuid.toString());
            if (existing != null) {
                return toAccount(existing); // safe: never overwrite an existing account
            }
            Account account = Account.create(uuid, ownerName, initialBalances);
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(uuid.toString(), toJsonAccount(account));
            commit(previous, next, "Failed to create account " + uuid);
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
            ensureWritable();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            insertInto(next, transaction);
            commit(previous, next, "Failed to append transaction " + transaction.id());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendBatch(List<Transaction> transactions) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            for (Transaction t : transactions) {
                insertInto(next, t);
            }
            // Single publish => all-or-none for the whole batch.
            commit(previous, next, "Failed to append transaction batch");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markReverted(UUID transactionId) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            markOnModel(next, transactionId);
            commit(previous, next, "Failed to mark transaction reverted " + transactionId);
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

    // ---------------- operation audit ----------------

    /**
     * Internal restore-audit read in stored order; ordering for queries is the caller's job.
     */
    @Override
    public List<com.smile.aceeconomy.domain.OperationAudit> listOperationAudits() {
        lock.lock();
        try {
            ensureInitialized();
            return model.operationAudits.stream()
                    .map(JsonModel.JsonOperationAudit::toDomain)
                    .toList();
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
        applyReversalDeltas(reversalRecords, revertMarkerIds, null);
    }

    @Override
    public void applyReversal(List<Account> updatedAccounts, List<Transaction> reversalRecords,
                              List<UUID> revertMarkerIds, DebtPolicy debtPolicy)
            throws PersistenceException {
        applyReversalDeltas(reversalRecords, revertMarkerIds, debtPolicy);
    }

    private void applyReversalDeltas(List<Transaction> reversalRecords,
                                     List<UUID> revertMarkerIds,
                                     DebtPolicy debtPolicy) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            // Idempotency: all reverted => no-op; none reverted => full apply;
            // partial (some reverted) => reject to avoid duplicating the already-committed leg.
            if (!revertMarkerIds.isEmpty()) {
                boolean anyReverted = false;
                boolean anyNotReverted = false;
                for (UUID markerId : revertMarkerIds) {
                    String id = markerId.toString();
                    boolean found = false;
                    boolean reverted = false;
                    for (JsonModel.JsonTransaction t : model.transactions) {
                        if (t.id.equals(id)) {
                            found = true;
                            reverted = t.reverted;
                            break;
                        }
                    }
                    if (!found) {
                        throw new PersistenceException("Cannot mark unknown transaction reverted: " + markerId);
                    }
                    if (reverted) {
                        anyReverted = true;
                    } else {
                        anyNotReverted = true;
                    }
                }
                if (anyReverted && anyNotReverted) {
                    throw new PersistenceException(
                            "partial reversal markers already reverted: markers=" + revertMarkerIds);
                }
                if (anyReverted && !anyNotReverted) {
                    return;
                }
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
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
                if (debtPolicy != null && !debtPolicy.allows(after)) {
                    // Inside the atomic scope, before the single commit below: a spend that
                    // landed after the caller's pre-check is visible in this re-read, so the
                    // violation aborts with the previous model untouched (no balance effect,
                    // no record, no marker).
                    throw new AtomicReversalStore.DebtLimitExceededException(
                            debtRejectionMessage(record.accountId(), currencyId, after, debtPolicy));
                }
                live.put(owner, updated);
                next.accounts.put(owner.toString(), toJsonAccount(updated));
                insertInto(next, authoritativeReversal(record, before, after));
            }
            for (UUID markerId : revertMarkerIds) {
                markOnModel(next, markerId);
            }
            commit(previous, next, "Failed to apply reversal atomically at " + dataFile);
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
            ensureWritable();
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return false;
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.nonces.put(key, java.time.Instant.now().toString());
            commit(previous, next, "Failed to persist consumed nonce " + nonce);
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
        return redeemPrepared(nonce, account, transaction, null);
    }

    @Override
    public RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction,
                                           DebtPolicy debtPolicy) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return RedemptionResult.replay();
            }
            String ownerKey = transaction.accountId().toString();
            JsonModel.JsonAccount stored = model.accounts.get(ownerKey);
            if (stored == null) {
                return RedemptionResult.accountMissing();
            }
            Account live = toAccount(stored);
            Amount current = live.balanceOf(transaction.currencyId());
            Amount before = current == null ? Amount.zero(transaction.amount().scale()) : current;
            Account updated = live.deposit(transaction.currencyId(), transaction.amount());
            Amount after = updated.balanceOf(transaction.currencyId());
            if (debtPolicy != null && !debtPolicy.allows(after)) {
                return RedemptionResult.debtLimitExceeded();
            }
            Transaction authoritative = new Transaction(transaction.id(), transaction.accountId(),
                    transaction.counterparty(), Currency.normalizeId(transaction.currencyId()),
                    transaction.amount(), TransactionType.DEPOSIT, before, after,
                    transaction.timestamp(), transaction.reason());

            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            insertInto(next, authoritative);
            next.nonces.put(key, java.time.Instant.now().toString());
            commit(previous, next, "Failed to persist banknote redemption " + nonce);
            return RedemptionResult.committed(before, after, transaction.id());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TransferResult transfer(UUID from, UUID to, String currencyId, Amount amount,
                                   DebtPolicy debtPolicy) throws PersistenceException {
        String cid = Currency.normalizeId(currencyId);
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel.JsonAccount fromStored = model.accounts.get(from.toString());
            JsonModel.JsonAccount toStored = model.accounts.get(to.toString());
            if (fromStored == null || toStored == null) {
                throw new PersistenceException("account not found for transfer");
            }
            Account fromAcc = toAccount(fromStored);
            Account toAcc = toAccount(toStored);
            Amount fromBefore = fromAcc.balanceOf(cid);
            if (fromBefore == null) fromBefore = Amount.zero(amount.scale());
            Amount toBefore = toAcc.balanceOf(cid);
            if (toBefore == null) toBefore = Amount.zero(amount.scale());
            Amount fromAfter = fromBefore.subtract(amount);
            if (debtPolicy != null && !debtPolicy.allows(fromAfter)) {
                throw new AtomicTransferStore.DebtLimitExceededException("debt limit exceeded");
            }
            Amount toAfter = toBefore.add(amount);
            Account updatedFrom = fromAcc.withdraw(cid, amount);
            Account updatedTo = toAcc.deposit(cid, amount);
            UUID outId = UUID.randomUUID();
            UUID inId = UUID.randomUUID();
            java.time.Instant now = java.time.Instant.now();
            Transaction outTx = new Transaction(outId, from, to, cid, amount,
                    TransactionType.TRANSFER_OUT, fromBefore, fromAfter, now, "transfer-out");
            Transaction inTx = new Transaction(inId, to, from, cid, amount,
                    TransactionType.TRANSFER_IN, toBefore, toAfter, now, "transfer-in");
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updatedFrom.owner().toString(), toJsonAccount(updatedFrom));
            next.accounts.put(updatedTo.owner().toString(), toJsonAccount(updatedTo));
            insertInto(next, outTx);
            insertInto(next, inTx);
            try {
                commit(previous, next, "Failed to persist transfer");
            } catch (PersistenceException e) {
                if (e.isCommitted()) {
                    // The model was already published with these ids; carry the real receipt
                    // instead of letting callers fabricate one.
                    throw new CommittedTransferException(e.getMessage(),
                            e.getCause() != null ? e.getCause() : e,
                            new TransferResult(from, to, fromAfter, toAfter, outId, inId));
                }
                throw e;
            }
            return new TransferResult(from, to, fromAfter, toAfter, outId, inId);
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
            ensureWritable();
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
            // nonce, then publish it once. A pre-publish failure leaves the previous model
            // live, so memory and file never disagree about whether the redemption happened.
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            insertInto(next, credit);
            next.nonces.put(key, java.time.Instant.now().toString());
            commit(previous, next, "Failed to persist banknote redemption " + nonce);
            return RedemptionResult.committed(before, after, credit.id());
        } finally {
            lock.unlock();
        }
    }

    // ---------------- atomic import ----------------

    @Override
    public ImportCommitResult importPrepared(UUID nonce, Account prepared, Transaction preparedTx,
                                             boolean createMissing) throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            String key = nonce.toString();
            if (model.nonces.containsKey(key)) {
                return ImportCommitResult.replay();
            }
            String ownerKey = preparedTx.accountId().toString();
            JsonModel.JsonAccount stored = model.accounts.get(ownerKey);
            String cid = Currency.normalizeId(preparedTx.currencyId());
            Amount target = preparedTx.amount();
            Account updated;
            Amount before;
            if (stored == null) {
                if (!createMissing) {
                    return ImportCommitResult.accountMissing();
                }
                before = Amount.zero(target.scale());
                Map<String, Amount> initial = new LinkedHashMap<>();
                if (prepared != null) {
                    initial.putAll(prepared.balances());
                }
                initial.put(cid, target);
                updated = Account.create(preparedTx.accountId(),
                        prepared == null || prepared.ownerName() == null
                                ? preparedTx.accountId().toString() : prepared.ownerName(),
                        initial);
            } else {
                // Derived from the live row inside the same lock, never by overwriting it
                // with the caller's snapshot: a general transaction that committed first is
                // observed here, so concurrent imports and transfers cannot lose updates.
                Account live = toAccount(stored);
                Amount current = live.balanceOf(cid);
                before = current == null ? Amount.zero(target.scale()) : current;
                updated = live.setBalance(cid, target);
            }
            Transaction authoritative = new Transaction(preparedTx.id(), preparedTx.accountId(),
                    preparedTx.counterparty(), cid, target, TransactionType.SET, before, target,
                    preparedTx.timestamp(), preparedTx.reason(),
                    preparedTx.operatorId(), preparedTx.operatorName());

            // Copy-on-write: the successor carries balance + audit record + consumed key, then
            // publishes once. A pre-publish failure (for example a duplicate audit id) leaves
            // the previous model live, so the account and the key stay untouched and a retry
            // converges instead of reporting a duplicate skip.
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            insertInto(next, authoritative);
            next.nonces.put(key, java.time.Instant.now().toString());
            try {
                commit(previous, next, "Failed to persist import " + nonce);
            } catch (PersistenceException e) {
                if (e.isCommitted()) {
                    // The model was already published with this receipt; carry it instead of
                    // letting callers fabricate one or retry a durable write.
                    throw new CommittedImportException(e.getMessage(),
                            e.getCause() != null ? e.getCause() : e,
                            before, target, preparedTx.id());
                }
                throw e;
            }
            return ImportCommitResult.committed(before, target, preparedTx.id());
        } finally {
            lock.unlock();
        }
    }

    // ---------------- atomic admin mutation ----------------

    /**
     * Single publish: the updated account, the authoritative record and nothing else land in
     * one model swap. Before/after come from the live row inside the lock, so a general
     * transaction that committed first is observed. The public amount cap is re-checked
     * against the live-derived outcome inside the same swap (a spend that landed after the
     * caller's pre-check is visible here), and a debt violation returns without touching
     * the model; a failed publish throws and leaves the previous model live. A publish
     * that replaced the file but lost its durability confirmation travels as a committed
     * receipt with the authoritative balances instead of a bare retryable failure.
     */
    @Override
    public AdminMutationResult commitAdminMutation(Transaction preparedTx, DebtPolicy debtPolicy)
            throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            String cid = Currency.normalizeId(preparedTx.currencyId());
            JsonModel.JsonAccount stored = model.accounts.get(preparedTx.accountId().toString());
            if (stored == null) {
                return AdminMutationResult.accountMissing();
            }
            Account live = toAccount(stored);
            Amount current = live.balanceOf(cid);
            Amount before = current == null ? Amount.zero(preparedTx.amount().scale()) : current;
            if (AmountCap.exceeds(before)) {
                throw new com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore.CapExceededException(
                        AmountCap.describeStored(cid, before));
            }
            if (preparedTx.amount() != null && AmountCap.exceeds(preparedTx.amount())) {
                throw new com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore.CapExceededException(
                        AmountCap.describeRequest(cid, preparedTx.amount()));
            }
            Account updated = switch (preparedTx.type()) {
                case DEPOSIT -> live.deposit(cid, preparedTx.amount());
                case WITHDRAW -> live.withdraw(cid, preparedTx.amount());
                case SET -> live.setBalance(cid, preparedTx.amount());
                default -> throw new PersistenceException(
                        "Unsupported administrative mutation type: " + preparedTx.type());
            };
            Amount after = updated.balanceOf(cid);
            if (AmountCap.exceeds(after)) {
                throw new com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore.CapExceededException(
                        AmountCap.describeResult(cid, after));
            }
            if (debtPolicy != null && !debtPolicy.allows(after)) {
                return AdminMutationResult.debtLimitExceeded();
            }
            Transaction authoritative = new Transaction(preparedTx.id(), preparedTx.accountId(),
                    preparedTx.counterparty(), cid, preparedTx.amount(), preparedTx.type(), before, after,
                    preparedTx.timestamp(), preparedTx.reason(),
                    preparedTx.operatorId(), preparedTx.operatorName());

            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            insertInto(next, authoritative);
            try {
                commit(previous, next, "Failed to persist administrative mutation " + preparedTx.id());
            } catch (PersistenceException e) {
                if (e.isCommitted()) {
                    throw new com.smile.aceeconomy.ports.persistence.CommittedAdminMutationException(
                            e.getMessage(), e.getCause() != null ? e.getCause() : e,
                            before, after, preparedTx.id());
                }
                throw e;
            }
            return AdminMutationResult.committed(before, after, preparedTx.id());
        } finally {
            lock.unlock();
        }
    }

    // ---------------- durable issuance ----------------

    /**
     * Durable issuance progress in the additive {@code issuances} model
     * section. {@code prepare} publishes the {@code PREPARED} record before
     * any deduction; {@link #commitDebit} and {@link #commitRecoveryRefund}
     * commit the money movement together with the progress flip in ONE
     * publish, so a deduction can never succeed while the issuance still
     * reads {@code PREPARED}. Every marker enforces its legal-state
     * compare-and-swap in code. Money-bearing states are reached only
     * through the two atomic commits, never through the markers below.
     */
    @Override
    public com.smile.aceeconomy.domain.BanknoteIssuance prepare(
            com.smile.aceeconomy.domain.BanknoteIssuance issuance) throws PersistenceException {
        java.util.Objects.requireNonNull(issuance, "issuance");
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            if (issuance.status() != com.smile.aceeconomy.domain.BanknoteIssuanceStatus.PREPARED) {
                throw new PersistenceException("prepare requires PREPARED status, got " + issuance.status());
            }
            if (issuance.debitTransactionId() != null || issuance.debitProof() != null) {
                throw new PersistenceException("prepare must carry no debit evidence");
            }
            JsonModel.JsonIssuance existing = model.issuances.get(issuance.nonce().toString());
            if (existing != null) {
                com.smile.aceeconomy.domain.BanknoteIssuance stored = existing.toDomain();
                if (!stored.accountId().equals(issuance.accountId())
                        || !stored.currencyId().equals(issuance.currencyId())
                        || !stored.amount().equals(issuance.amount())) {
                    throw new PersistenceException(
                            "issuance payload mismatch on replay for nonce " + issuance.nonce());
                }
                return stored;
            }
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.issuances.put(issuance.nonce().toString(), JsonModel.JsonIssuance.fromDomain(issuance));
            commit(previous, next, "Failed to persist issuance preparation " + issuance.nonce());
            return issuance;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public com.smile.aceeconomy.domain.BanknoteIssuance markDeliveryStarted(UUID nonce)
            throws PersistenceException {
        return issuanceTransition(nonce,
                com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERY_STARTED);
    }

    @Override
    public com.smile.aceeconomy.domain.BanknoteIssuance markDelivered(UUID nonce)
            throws PersistenceException {
        return issuanceTransition(nonce,
                com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERED);
    }

    @Override
    public com.smile.aceeconomy.domain.BanknoteIssuance markRedeemed(UUID nonce)
            throws PersistenceException {
        return issuanceTransition(nonce,
                com.smile.aceeconomy.domain.BanknoteIssuanceStatus.REDEEMED);
    }

    @Override
    public com.smile.aceeconomy.domain.BanknoteIssuance markAborted(UUID nonce, String reason)
            throws PersistenceException {
        if (reason == null || reason.isBlank()) {
            throw new PersistenceException("abort reason missing");
        }
        return issuanceTransition(nonce,
                com.smile.aceeconomy.domain.BanknoteIssuanceStatus.ABORTED);
    }

    @Override
    public com.smile.aceeconomy.domain.BanknoteIssuance markQuarantined(UUID nonce, String diagnosis)
            throws PersistenceException {
        if (diagnosis == null || diagnosis.isBlank()) {
            throw new PersistenceException("quarantine diagnosis missing");
        }
        return issuanceTransition(nonce,
                com.smile.aceeconomy.domain.BanknoteIssuanceStatus.QUARANTINED);
    }

    @Override
    public Optional<com.smile.aceeconomy.domain.BanknoteIssuance> findByNonce(UUID nonce)
            throws PersistenceException {
        java.util.Objects.requireNonNull(nonce, "nonce");
        lock.lock();
        try {
            ensureInitialized();
            JsonModel.JsonIssuance stored = model.issuances.get(nonce.toString());
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(stored.toDomain());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<com.smile.aceeconomy.domain.BanknoteIssuance> listUnfinished()
            throws PersistenceException {
        lock.lock();
        try {
            ensureInitialized();
            List<com.smile.aceeconomy.domain.BanknoteIssuance> out = new ArrayList<>();
            for (JsonModel.JsonIssuance stored : model.issuances.values()) {
                com.smile.aceeconomy.domain.BanknoteIssuance issuance = stored.toDomain();
                if (issuance.status().isUnfinished()) {
                    out.add(issuance);
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically deduct plus flip {@code PREPARED -> DEBIT_COMMITTED} in ONE
     * publish with full proof verification. First-writer-wins per nonce: an
     * identical replay observes {@code REPLAY} with no second charge.
     */
    @Override
    public IssuanceDebitResult commitDebit(UUID nonce, Transaction preparedWithdrawal,
                                           DebtPolicy debtPolicy) throws PersistenceException {
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(preparedWithdrawal, "preparedWithdrawal");
        java.util.Objects.requireNonNull(debtPolicy, "debtPolicy");
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel.JsonIssuance raw = model.issuances.get(nonce.toString());
            if (raw == null) {
                return IssuanceDebitResult.inconsistentProof();
            }
            com.smile.aceeconomy.domain.BanknoteIssuance stored = raw.toDomain();
            if (stored.status() != com.smile.aceeconomy.domain.BanknoteIssuanceStatus.PREPARED) {
                String replayBinding =
                        com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                                .checkDebitBinding(stored, preparedWithdrawal, nonce);
                if (replayBinding == null
                        && stored.debitTransactionId() != null
                        && stored.debitTransactionId().equals(preparedWithdrawal.id().toString())) {
                    return IssuanceDebitResult.replay(stored);
                }
                return IssuanceDebitResult.inconsistentProof();
            }
            String bindingError =
                    com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                            .checkDebitBinding(stored, preparedWithdrawal, nonce);
            if (bindingError != null) {
                return IssuanceDebitResult.inconsistentProof();
            }
            String newDebitId = preparedWithdrawal.id().toString();
            for (JsonModel.JsonIssuance other : model.issuances.values()) {
                com.smile.aceeconomy.domain.BanknoteIssuance o = other.toDomain();
                if (!o.nonce().equals(nonce)
                        && newDebitId.equals(o.debitTransactionId())) {
                    return IssuanceDebitResult.inconsistentProof();
                }
            }
            for (JsonModel.JsonTransaction t : model.transactions) {
                if (t.id.equals(newDebitId)) {
                    return IssuanceDebitResult.inconsistentProof();
                }
            }
            JsonModel.JsonAccount storedAcc =
                    model.accounts.get(preparedWithdrawal.accountId().toString());
            if (storedAcc == null) {
                return IssuanceDebitResult.accountMissing();
            }
            com.smile.aceeconomy.domain.Account live = toAccount(storedAcc);
            String cid = Currency.normalizeId(preparedWithdrawal.currencyId());
            com.smile.aceeconomy.domain.Amount before = live.balanceOf(cid);
            if (before == null) {
                before = Amount.zero(preparedWithdrawal.amount().scale());
            }
            if (!com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                    .scalesAgree(stored.amount(), preparedWithdrawal.amount(), before)) {
                return IssuanceDebitResult.scaleMismatch();
            }
            com.smile.aceeconomy.domain.Amount after;
            try {
                after = before.subtract(preparedWithdrawal.amount());
            } catch (IllegalArgumentException scaleMismatch) {
                return IssuanceDebitResult.scaleMismatch();
            }
            if (com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                    .capExceeded(stored.amount(), preparedWithdrawal.amount(), before, after)) {
                return IssuanceDebitResult.capExceeded();
            }
            if (!debtPolicy.allows(after)) {
                return IssuanceDebitResult.debtLimitExceeded();
            }
            com.smile.aceeconomy.domain.Account updated;
            try {
                updated = live.withdraw(cid, preparedWithdrawal.amount());
            } catch (IllegalArgumentException scaleMismatch) {
                return IssuanceDebitResult.scaleMismatch();
            }
            after = updated.balanceOf(cid);
            if (!debtPolicy.allows(after)) {
                return IssuanceDebitResult.debtLimitExceeded();
            }
            Transaction authoritative = new Transaction(preparedWithdrawal.id(),
                    preparedWithdrawal.accountId(), preparedWithdrawal.counterparty(), cid,
                    preparedWithdrawal.amount(), TransactionType.WITHDRAW, before, after,
                    preparedWithdrawal.timestamp(), preparedWithdrawal.reason(),
                    preparedWithdrawal.operatorId(), preparedWithdrawal.operatorName());
            com.smile.aceeconomy.domain.BanknoteIssuance updatedIssuance = stored.withStatus(
                    com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DEBIT_COMMITTED,
                    preparedWithdrawal.id().toString(),
                    com.smile.aceeconomy.domain.BanknoteIssuanceReasons.issueReason(nonce),
                    java.time.Instant.now());
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            insertInto(next, authoritative);
            next.issuances.put(nonce.toString(),
                    JsonModel.JsonIssuance.fromDomain(updatedIssuance));
            try {
                commit(previous, next, "Failed to persist issuance debit " + nonce);
            } catch (PersistenceException e) {
                if (e.isCommitted()) {
                    throw new com.smile.aceeconomy.ports.persistence.CommittedIssuanceDebitException(
                            e.getMessage(), e.getCause() != null ? e.getCause() : e,
                            before, after, preparedWithdrawal.id(), updatedIssuance);
                }
                throw e;
            }
            return IssuanceDebitResult.committed(before, after,
                    preparedWithdrawal.id(), updatedIssuance);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically consume the nonce plus refund plus final-mark in ONE publish
     * with full proof verification. An already redeemed nonce stabilises to
     * {@code REDEEMED} with no money write.
     */
    @Override
    public IssuanceRefundResult commitRecoveryRefund(UUID nonce, Transaction preparedRefund,
                                                     DebtPolicy debtPolicy) throws PersistenceException {
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(preparedRefund, "preparedRefund");
        java.util.Objects.requireNonNull(debtPolicy, "debtPolicy");
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel.JsonIssuance raw = model.issuances.get(nonce.toString());
            if (raw == null) {
                return IssuanceRefundResult.inconsistentProof();
            }
            com.smile.aceeconomy.domain.BanknoteIssuance stored = raw.toDomain();
            if (stored.status()
                    == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.FINAL_VOID_REFUNDED) {
                return IssuanceRefundResult.replay(stored);
            }
            if (stored.status()
                    == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.REDEEMED) {
                return IssuanceRefundResult.alreadyRedeemed(stored, null);
            }
            if (stored.status()
                            != com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DEBIT_COMMITTED
                    && stored.status()
                            != com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERY_STARTED) {
                return IssuanceRefundResult.inconsistentProof();
            }
            boolean nonceConsumed = model.nonces.containsKey(nonce.toString());
            if (nonceConsumed) {
                com.smile.aceeconomy.domain.BanknoteIssuance redeemed =
                        stored.withStatus(
                                com.smile.aceeconomy.domain.BanknoteIssuanceStatus.REDEEMED,
                                java.time.Instant.now());
                JsonModel previous = model;
                JsonModel next = shallowCopy(previous);
                next.issuances.put(nonce.toString(),
                        JsonModel.JsonIssuance.fromDomain(redeemed));
                try {
                    commit(previous, next,
                            "Failed to stabilise redeemed issuance " + nonce);
                } catch (PersistenceException e) {
                    if (e.isCommitted()) {
                        com.smile.aceeconomy.domain.BanknoteIssuance committedView = redeemed;
                        try {
                            JsonModel.JsonIssuance reloaded =
                                    model.issuances.get(nonce.toString());
                            if (reloaded != null) {
                                committedView = reloaded.toDomain();
                            }
                        } catch (RuntimeException ignored) {
                            // Keep the locally built view when re-read fails.
                        }
                        return IssuanceRefundResult.alreadyRedeemed(committedView, null);
                    }
                    throw e;
                }
                return IssuanceRefundResult.alreadyRedeemed(redeemed, null);
            }
            String refundBinding =
                    com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                            .checkRefundBinding(stored, preparedRefund, nonce);
            if (refundBinding != null) {
                return IssuanceRefundResult.inconsistentProof();
            }
            Transaction debit = null;
            if (stored.debitTransactionId() != null) {
                for (JsonModel.JsonTransaction t : model.transactions) {
                    if (t.id.equals(stored.debitTransactionId())) {
                        debit = t.toDomain();
                        break;
                    }
                }
            }
            String authority =
                    com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                            .checkDebitAuthority(stored, debit, nonce);
            if (authority != null) {
                return IssuanceRefundResult.inconsistentProof();
            }
            for (JsonModel.JsonTransaction t : model.transactions) {
                if (t.id.equals(preparedRefund.id().toString())) {
                    return IssuanceRefundResult.inconsistentProof();
                }
            }
            JsonModel.JsonAccount storedAcc =
                    model.accounts.get(preparedRefund.accountId().toString());
            if (storedAcc == null) {
                return IssuanceRefundResult.accountMissing();
            }
            com.smile.aceeconomy.domain.Account live = toAccount(storedAcc);
            String cid = Currency.normalizeId(preparedRefund.currencyId());
            com.smile.aceeconomy.domain.Amount before = live.balanceOf(cid);
            if (before == null) {
                before = Amount.zero(preparedRefund.amount().scale());
            }
            if (!com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                    .scalesAgree(stored.amount(), preparedRefund.amount(), before)) {
                return IssuanceRefundResult.scaleMismatch();
            }
            com.smile.aceeconomy.domain.Amount after;
            try {
                after = before.add(preparedRefund.amount());
            } catch (IllegalArgumentException scaleMismatch) {
                return IssuanceRefundResult.scaleMismatch();
            }
            if (com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                    .capExceeded(stored.amount(), preparedRefund.amount(), before, after)) {
                return IssuanceRefundResult.capExceeded();
            }
            if (!debtPolicy.allows(after)) {
                return IssuanceRefundResult.debtLimitExceeded();
            }
            com.smile.aceeconomy.domain.Account updated;
            try {
                updated = live.deposit(cid, preparedRefund.amount());
            } catch (IllegalArgumentException scaleMismatch) {
                return IssuanceRefundResult.scaleMismatch();
            }
            after = updated.balanceOf(cid);
            if (!debtPolicy.allows(after)) {
                return IssuanceRefundResult.debtLimitExceeded();
            }
            Transaction authoritative = new Transaction(preparedRefund.id(),
                    preparedRefund.accountId(), preparedRefund.counterparty(), cid,
                    preparedRefund.amount(), TransactionType.DEPOSIT, before, after,
                    preparedRefund.timestamp(), preparedRefund.reason(),
                    preparedRefund.operatorId(), preparedRefund.operatorName());
            com.smile.aceeconomy.domain.BanknoteIssuance finalIssuance = stored.withStatus(
                    com.smile.aceeconomy.domain.BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                    java.time.Instant.now());
            JsonModel previous = model;
            JsonModel next = shallowCopy(previous);
            next.accounts.put(updated.owner().toString(), toJsonAccount(updated));
            insertInto(next, authoritative);
            next.nonces.put(nonce.toString(), java.time.Instant.now().toString());
            next.issuances.put(nonce.toString(),
                    JsonModel.JsonIssuance.fromDomain(finalIssuance));
            try {
                commit(previous, next, "Failed to persist issuance recovery refund " + nonce);
            } catch (PersistenceException e) {
                if (e.isCommitted()) {
                    throw new com.smile.aceeconomy.ports.persistence.CommittedIssuanceRefundException(
                            e.getMessage(), e.getCause() != null ? e.getCause() : e,
                            before, after, preparedRefund.id(), finalIssuance);
                }
                throw e;
            }
            return IssuanceRefundResult.committed(before, after,
                    preparedRefund.id(), finalIssuance);
        } finally {
            lock.unlock();
        }
    }

    private com.smile.aceeconomy.domain.BanknoteIssuance issuanceTransition(
            UUID nonce, com.smile.aceeconomy.domain.BanknoteIssuanceStatus next)
            throws PersistenceException {
        java.util.Objects.requireNonNull(nonce, "nonce");
        lock.lock();
        try {
            ensureInitialized();
            ensureWritable();
            JsonModel.JsonIssuance stored = model.issuances.get(nonce.toString());
            if (stored == null) {
                throw new PersistenceException("unknown issuance nonce " + nonce);
            }
            com.smile.aceeconomy.domain.BanknoteIssuance current = stored.toDomain();
            if (current.status().isTerminal()
                    && !(current.status()
                            == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERED
                            && next
                                    == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.REDEEMED)) {
                throw new PersistenceException("issuance " + nonce
                        + " already terminal (" + current.status() + ")");
            }
            if (!issuanceAllows(current.status(), next)) {
                throw new PersistenceException("illegal issuance transition "
                        + current.status() + " -> " + next + " for nonce " + nonce);
            }
            com.smile.aceeconomy.domain.BanknoteIssuance updated =
                    current.withStatus(next, java.time.Instant.now());
            JsonModel previous = model;
            JsonModel nextModel = shallowCopy(previous);
            nextModel.issuances.put(nonce.toString(), JsonModel.JsonIssuance.fromDomain(updated));
            commit(previous, nextModel, "Failed to persist issuance transition " + nonce);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    private static boolean issuanceAllows(
            com.smile.aceeconomy.domain.BanknoteIssuanceStatus from,
            com.smile.aceeconomy.domain.BanknoteIssuanceStatus to) {
        return switch (to) {
            case DELIVERY_STARTED -> from == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DEBIT_COMMITTED;
            case DELIVERED -> from == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERY_STARTED;
            case REDEEMED -> from == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DEBIT_COMMITTED
                    || from == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERY_STARTED
                    || from == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERED;
            case ABORTED -> from == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.PREPARED;
            case QUARANTINED -> from.isUnfinished();
            default -> false;
        };
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

    /**
     * Clear debt-policy refusal naming the governing setting, worded like the
     * ordinary-transaction refusal so operators see one consistent rule.
     */
    private static String debtRejectionMessage(UUID accountId, String currencyId, Amount after,
                                               DebtPolicy debtPolicy) {
        String where = "account " + accountId + " (" + Currency.normalizeId(currencyId) + ")";
        if (!debtPolicy.isAllowNegative()) {
            return "rollback rejected: reversal would make balance " + after + " for " + where
                    + ", which economy.allow-negative-balance=false forbids"
                    + " (insufficient funds); nothing was changed";
        }
        return "rollback rejected: reversal would make balance " + after + " for " + where
                + " exceeding debt limit " + debtPolicy.debtLimit()
                + " (debt limit exceeded); nothing was changed";
    }

    private static Transaction authoritativeReversal(Transaction record, Amount before, Amount after) {
        Amount auditAmount = record.type() == TransactionType.SET ? after : record.amount().abs();
        return new Transaction(record.id(), record.accountId(), record.counterparty(),
                Currency.normalizeId(record.currencyId()), auditAmount, record.type(), before, after,
                record.timestamp(), record.reason(), record.operatorId(), record.operatorName());
    }

    private static void insertInto(JsonModel target, Transaction t) {
        String id = t.id().toString();
        for (JsonModel.JsonTransaction existing : target.transactions) {
            if (existing.id.equals(id)) {
                throw new PersistenceException("Duplicate transaction id: " + id);
            }
        }
        target.transactions.add(JsonModel.JsonTransaction.fromDomain(t));
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
        next.operationAudits.addAll(source.operationAudits);
        next.issuances.putAll(source.issuances);
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
        c.operatorId = t.operatorId;
        c.operatorName = t.operatorName;
        return c;
    }

    // ---------------- leaderboard (native, constant-query for JSON too) ----------------

    @Override
    public List<LeaderboardRow> leaderboardRows(String currencyId) {
        lock.lock();
        try {
            ensureInitialized();
            String cid = Currency.normalizeId(currencyId);
            List<LeaderboardRow> rows = new java.util.ArrayList<>();
            for (JsonModel.JsonAccount a : model.accounts.values()) {
                String balStr = a.balances.get(cid);
                if (balStr == null) continue;
                Amount bal = JsonModel.stringToAmount(balStr);
                rows.add(new LeaderboardRow(UUID.fromString(a.owner), a.ownerName, bal));
            }
            rows.sort(java.util.Comparator.comparing((LeaderboardRow r) -> r.balance().value()).reversed()
                    .thenComparing(LeaderboardRow::accountId));
            return List.copyOf(rows);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<LeaderboardRow> leaderboardTop(String currencyId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        List<LeaderboardRow> all = leaderboardRows(currencyId);
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }

    private JsonModel loadFromFile() throws IOException {
        String text = Files.readString(dataFile, StandardCharsets.UTF_8);
        return JsonModel.fromJson(text);
    }

    private void persist() throws IOException {
        String json = model.toJson();
        Path dir = siblingDir();
        Path tmp = publisher.createTempFile(dir, "ace-json-", ".tmp");
        boolean published = false;
        try {
            publisher.writeString(tmp, json);
            publisher.syncFile(tmp);
            try {
                publisher.moveAtomic(tmp, dataFile);
            } catch (AtomicMoveNotSupportedException e) {
                throw atomicReplacementUnsupported(dir, e);
            }
            published = true;
            publisher.syncFile(dataFile);
            publisher.syncDirectory(dir);
        } catch (JsonPostPublishException e) {
            throw e;
        } catch (IOException e) {
            if (published) {
                throw new JsonPostPublishException(
                        "Published " + dataFile + " but the post-publish durability sync failed", e);
            }
            throw e;
        } catch (RuntimeException e) {
            if (published) {
                throw new JsonPostPublishException(
                        "Published " + dataFile + " but the post-publish durability sync failed", e);
            }
            throw e;
        } finally {
            // A leftover temp file must never fail the operation and must never be
            // mistaken for a snapshot: after a successful move this is a no-op.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Best effort cleanup; the temp name can never collide with the live file.
            }
        }
    }

    /**
     * The temp file must live next to the live file so the replacement stays on one
     * filesystem; a system temp directory could force a cross-device move.
     */
    private Path siblingDir() {
        Path parent = dataFile.getParent();
        if (parent != null) {
            return parent;
        }
        Path absolute = dataFile.toAbsolutePath().getParent();
        return absolute == null ? Path.of(System.getProperty("java.io.tmpdir")) : absolute;
    }

    private static PersistenceException atomicReplacementUnsupported(Path dir,
                                                                     AtomicMoveNotSupportedException e) {
        return new PersistenceException(
                "JSON persistence requires an atomic file replacement, but the filesystem holding "
                        + dir + " does not support it. Move the data file to a local filesystem with"
                        + " atomic-move support (for example ext4, APFS, or a local NTFS volume), then"
                        + " re-initialize. Nothing was published: the live file and memory are unchanged.",
                e);
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
