package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.BlockedControlAttempt;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.domain.SecurityControlState;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central safety-control gate for every initiating balance change.
 *
 * <p>Two levels: freezing one account, and a global read-only switch. Every
 * initiating path (Vault deposit/withdraw, pay on both legs, ticket issue and
 * redeem, administrative give/take/set with no override, import apply and
 * rollback touching a frozen account, restore under global read-only or
 * touching a frozen account) consults {@link #checkInitiating} before any
 * debit and refuses with {@code ACCOUNT_FROZEN}, {@code GLOBAL_READ_ONLY} or
 * {@code CONTROL_UNAVAILABLE} without moving money, consuming a nonce, or
 * appending a money record. Read-only queries (balance, history,
 * leaderboard), previews and backups never consult the gate.
 *
 * <p>A mutation holds a {@link MutationPermit} across its commit; freezing
 * waits for older permits to drain before it becomes effective, so a freeze
 * that returns success can never let an already-admitted mutation commit
 * afterwards. The control lock is always taken before any per-account lock,
 * and control changes never take account locks, so the two orderings cannot
 * deadlock. The pre-commit gate check is an early diagnosis; the backend
 * commit runs under the same permit, which freezing cannot overtake, so the
 * checked state still holds inside the atomic commit.
 *
 * <p>Restoring an old economy snapshot never replaces this control: the
 * control lives in its own file outside the economy snapshot, and a restore
 * that would touch a frozen account is refused as a whole.
 *
 * <p>A corrupt control file fails closed: mutations stay refused with a loud
 * fixed diagnosis, and the load path never overwrites or deletes the file.
 * State and its control audits publish together atomically through
 * {@link SecurityControlStore#save}; a failed publish applies neither, while
 * a landed-but-unsynced replacement adopts the new state in memory and
 * surfaces loudly instead of claiming that nothing was applied.
 * Refused attempts leave one minimal in-memory record each (operation,
 * target, fixed refusal category, control version, trusted operator) and, when
 * a journal is bound, one appended journal line on the bound executor, never
 * on a Folia region thread. Attempt records are read-only evidence and can
 * never reconstruct control state.
 */
public final class SecurityControlService {

    private static final Logger LOG = Logger.getLogger(SecurityControlService.class.getName());

    private final SecurityControlStore store;
    private final ReadWriteLock controlLock = new ReentrantReadWriteLock();

    private final Set<UUID> frozen = new HashSet<>();
    private boolean globalReadonly;
    private boolean loadFailed;
    private String diagnosis = "";
    private long version;
    private final List<SecurityControlAudit> audits = new ArrayList<>();
    /**
     * Bounded recent view of refused attempts: at most the latest 1024
     * records, oldest evicted first, so a flood of refusals can never grow
     * memory without limit. Guarded by its own monitor (never the control
     * lock: refusals are recorded while holding a mutation permit, which is
     * a control read lock, so taking the write lock here would deadlock).
     * The durable journal still appends every record completely; the full
     * history is read back from the journal, never from this view.
     */
    private static final int MAX_RECENT_BLOCKED = 1024;
    private final Deque<BlockedControlAttempt> blocked = new ArrayDeque<>();
    private final Object blockedLock = new Object();

    private volatile BlockedAttemptJournal attemptJournal;
    private volatile Executor journalExecutor;

    /** In-memory control opening writable; used by offline tests and as a last resort. */
    public SecurityControlService() {
        this.store = null;
    }

    /**
     * Durable control loading its snapshot once at construction. A corrupt
     * file fails closed (read-only, loud fixed diagnosis) and is never
     * overwritten or deleted by the load path.
     */
    public SecurityControlService(SecurityControlStore store) {
        this.store = Objects.requireNonNull(store, "store");
        SecurityControlState loaded;
        try {
            loaded = store.load();
        } catch (RuntimeException loadFailure) {
            LOG.log(Level.SEVERE,
                    "Security control store failed to load; failing closed to read-only", loadFailure);
            this.loadFailed = true;
            this.diagnosis = fixedLoadDiagnosis();
            this.globalReadonly = true;
            this.version = -1L;
            return;
        }
        this.frozen.addAll(loaded.frozenAccounts());
        this.globalReadonly = loaded.isGlobalReadonly();
        this.loadFailed = store.isLoadFailed() || loaded.isLoadFailed();
        if (this.loadFailed) {
            this.globalReadonly = true;
            String reported = store.loadDiagnosis();
            this.diagnosis = reported == null || reported.isBlank()
                    ? fixedLoadDiagnosis() : reported;
            this.version = -1L;
        } else {
            this.version = loaded.version();
        }
        try {
            this.audits.addAll(store.audits());
        } catch (RuntimeException auditFailure) {
            LOG.log(Level.WARNING,
                    "Security control audits could not be listed; continuing with state only",
                    auditFailure);
        }
    }

    /**
     * Mirror refused attempts to a durable append-only journal on the given
     * executor. Regions only enqueue through {@link #recordBlockedAttempt};
     * they never block on the journal, and file work never runs on a Folia
     * region thread. A journal failure is logged loudly with the minimal
     * record; the in-memory record is always kept, so nothing is silently
     * dropped, and the refused mutation stays refused.
     */
    public void bindAttemptJournal(BlockedAttemptJournal journal, Executor ioExecutor) {
        this.attemptJournal = Objects.requireNonNull(journal, "journal");
        this.journalExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * Freeze one account. Waits for older mutation permits to drain, then
     * publishes state and its audit together. Repeating a freeze is
     * idempotent: no version change, no second audit.
     *
     * @throws IllegalStateException when the control cannot be loaded or the publish fails;
     *                              nothing is applied then
     */
    public void freeze(UUID accountId, AdminAudit audit) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(audit, "audit");
        SecurityControlAudit record =
                SecurityControlAudit.freeze(audit, Instant.now(), accountId);
        controlLock.writeLock().lock();
        try {
            failClosedOnLoadFailure("freeze");
            if (frozen.contains(accountId)) {
                return;
            }
            Set<UUID> next = new HashSet<>(frozen);
            next.add(accountId);
            publish(new SecurityControlState(next, globalReadonly, false, version + 1), record,
                    () -> {
                        frozen.add(accountId);
                        version++;
                        audits.add(record);
                    });
        } finally {
            controlLock.writeLock().unlock();
        }
    }

    /**
     * Unfreeze one account. Repeating an unfreeze is idempotent.
     *
     * @throws IllegalStateException when the control cannot be loaded or the publish fails;
     *                              nothing is applied then
     */
    public void unfreeze(UUID accountId, AdminAudit audit) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(audit, "audit");
        SecurityControlAudit record =
                SecurityControlAudit.unfreeze(audit, Instant.now(), accountId);
        controlLock.writeLock().lock();
        try {
            failClosedOnLoadFailure("unfreeze");
            if (!frozen.contains(accountId)) {
                return;
            }
            Set<UUID> next = new HashSet<>(frozen);
            next.remove(accountId);
            publish(new SecurityControlState(next, globalReadonly, false, version + 1), record,
                    () -> {
                        frozen.remove(accountId);
                        version++;
                        audits.add(record);
                    });
        } finally {
            controlLock.writeLock().unlock();
        }
    }

    /**
     * Switch the global read-only flag. Repeating the current value is
     * idempotent. Switching off while the control failed to load is ignored:
     * a load failure can only be cleared by repairing the file and
     * restarting, never by a command.
     *
     * @throws IllegalStateException when switching on while the control cannot
     *                              be loaded, or when the publish fails
     */
    public void setGlobalReadonly(boolean readonly, AdminAudit audit) {
        Objects.requireNonNull(audit, "audit");
        SecurityControlAudit record =
                SecurityControlAudit.readonly(audit, Instant.now(), readonly);
        controlLock.writeLock().lock();
        try {
            if (loadFailed) {
                if (!readonly) {
                    LOG.log(Level.WARNING,
                            "Ignoring read-only off while the safety control failed to load;"
                                    + " repair the control file and restart: {0}", diagnosis);
                    return;
                }
                throw new IllegalStateException(
                        "Safety control unavailable; refusing read-only change: " + diagnosis);
            }
            if (globalReadonly == readonly) {
                return;
            }
            publish(new SecurityControlState(new HashSet<>(frozen), readonly, false, version + 1),
                    record,
                    () -> {
                        globalReadonly = readonly;
                        version++;
                        audits.add(record);
                    });
        } finally {
            controlLock.writeLock().unlock();
        }
    }

    public boolean isFrozen(UUID accountId) {
        controlLock.readLock().lock();
        try {
            return accountId != null && frozen.contains(accountId);
        } finally {
            controlLock.readLock().unlock();
        }
    }

    public boolean isGlobalReadonly() {
        controlLock.readLock().lock();
        try {
            return globalReadonly;
        } finally {
            controlLock.readLock().unlock();
        }
    }

    public boolean isLoadFailed() {
        controlLock.readLock().lock();
        try {
            return loadFailed;
        } finally {
            controlLock.readLock().unlock();
        }
    }

    /** Fixed loud diagnosis for the load failure; empty when the load succeeded. */
    public String loadDiagnosis() {
        controlLock.readLock().lock();
        try {
            return diagnosis;
        } finally {
            controlLock.readLock().unlock();
        }
    }

    public SecurityControlState snapshot() {
        controlLock.readLock().lock();
        try {
            return new SecurityControlState(new HashSet<>(frozen), globalReadonly, loadFailed,
                    version);
        } finally {
            controlLock.readLock().unlock();
        }
    }

    /**
     * Gate for initiating mutations. A present value refuses the mutation
     * before any debit; an empty value allows it.
     *
     * @param accountIds every account the mutation would touch
     * @return the refusal reason, or empty when allowed
     */
    public Optional<EconomyError> checkInitiating(UUID... accountIds) {
        controlLock.readLock().lock();
        try {
            if (loadFailed) {
                return Optional.of(EconomyError.CONTROL_UNAVAILABLE);
            }
            if (globalReadonly) {
                return Optional.of(EconomyError.GLOBAL_READ_ONLY);
            }
            if (accountIds != null) {
                for (UUID accountId : accountIds) {
                    if (accountId != null && frozen.contains(accountId)) {
                        return Optional.of(EconomyError.ACCOUNT_FROZEN);
                    }
                }
            }
            return Optional.empty();
        } finally {
            controlLock.readLock().unlock();
        }
    }

    /**
     * Admit one mutation commit. The permit holds the control read lock, so a
     * freeze requested afterwards waits until this permit closes. Take the
     * permit before any per-account lock and close it after the commit.
     */
    public MutationPermit acquireMutationPermit() {
        controlLock.readLock().lock();
        return new Permit();
    }

    /** Control audits applied so far, oldest first. */
    public List<SecurityControlAudit> audits() {
        controlLock.readLock().lock();
        try {
            return List.copyOf(audits);
        } finally {
            controlLock.readLock().unlock();
        }
    }

    /** Control audits whose reason contains the fragment, oldest first. */
    public List<SecurityControlAudit> findAuditsByReason(String fragment) {
        if (fragment == null) {
            return List.of();
        }
        controlLock.readLock().lock();
        try {
            List<SecurityControlAudit> found = new ArrayList<>();
            for (SecurityControlAudit audit : audits) {
                if (audit.reason() != null && audit.reason().contains(fragment)) {
                    found.add(audit);
                }
            }
            return List.copyOf(found);
        } finally {
            controlLock.readLock().unlock();
        }
    }

    /** Monotonic version; increments once per applied control change. */
    public long controlVersion() {
        controlLock.readLock().lock();
        try {
            return version;
        } finally {
            controlLock.readLock().unlock();
        }
    }

    /**
     * Keep one minimal record per refused initiating mutation. Carries only
     * the operation, the target account when there is one, the fixed refusal
     * category, the control version at refusal time, and the trusted
     * operator. Never carries the full command line, an IP, or raw reason
     * text. Never throws for journal failures: the in-memory record is kept
     * and the failure is logged loudly, while the refused mutation stays
     * refused.
     */
    public void recordBlockedAttempt(BlockedControlAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!isKnownCategory(attempt.refusalCategory())) {
            throw new IllegalArgumentException(
                    "blocked attempt must use a fixed refusal category");
        }
        synchronized (blockedLock) {
            blocked.addLast(attempt);
            while (blocked.size() > MAX_RECENT_BLOCKED) {
                blocked.removeFirst();
            }
        }
        BlockedAttemptJournal journal = attemptJournal;
        Executor io = journalExecutor;
        if (journal == null || io == null) {
            return;
        }
        try {
            io.execute(() -> {
                try {
                    journal.append(attempt);
                } catch (Throwable appendFailure) {
                    LOG.log(Level.SEVERE,
                            "Blocked-attempt journal append failed; the mutation stays refused"
                                    + " and the record is kept in memory: operation="
                                    + attempt.operation()
                                    + " category=" + attempt.refusalCategory()
                                    + " version=" + attempt.controlVersion(),
                            appendFailure);
                }
            });
        } catch (Throwable rejected) {
            LOG.log(Level.SEVERE,
                    "Blocked-attempt journal executor rejected the append; the mutation stays"
                            + " refused and the record is kept in memory: operation="
                            + attempt.operation()
                            + " category=" + attempt.refusalCategory()
                            + " version=" + attempt.controlVersion(),
                    rejected);
        }
    }

    /**
     * Recent refused-attempt records, oldest first: at most the latest 1024.
     * Read-only evidence only. The full history lives in the bound journal;
     * read it back from there, never from this bounded view.
     */
    public List<BlockedControlAttempt> blockedAttempts() {
        synchronized (blockedLock) {
            return List.copyOf(blocked);
        }
    }

    private void failClosedOnLoadFailure(String operation) {
        if (loadFailed) {
            throw new IllegalStateException(
                    "Safety control unavailable; refusing " + operation + ": " + diagnosis);
        }
    }

    /**
     * Publish state and its audit together, then apply the memory change. A
     * landed-but-unsynced replacement still applies the memory change (disk
     * already holds the new state) and rethrows loudly instead of claiming
     * that nothing was applied; a pre-publish failure applies neither.
     */
    private void publish(SecurityControlState state, SecurityControlAudit audit,
            Runnable applyMemory) {
        if (store != null) {
            try {
                store.save(state, audit);
            } catch (ControlPostPublishException landed) {
                applyMemory.run();
                throw new IllegalStateException(
                        "Safety control change landed on disk but its durability sync failed;"
                                + " the new state was adopted — verify the control file and"
                                + " restart if the machine crashed",
                        landed);
            }
        }
        applyMemory.run();
    }

    private static boolean isKnownCategory(String category) {
        return BlockedControlAttempt.CATEGORY_FROZEN.equals(category)
                || BlockedControlAttempt.CATEGORY_READONLY.equals(category)
                || BlockedControlAttempt.CATEGORY_UNAVAILABLE.equals(category);
    }

    private static String fixedLoadDiagnosis() {
        return "Safety control failed to load; all balance-changing operations are refused"
                + " until an operator repairs the control file and restarts (fail-closed).";
    }

    /** Read permit covering one mutation commit. */
    public interface MutationPermit extends AutoCloseable {

        @Override
        void close();
    }

    private final class Permit implements MutationPermit {

        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                controlLock.readLock().unlock();
            }
        }
    }
}
