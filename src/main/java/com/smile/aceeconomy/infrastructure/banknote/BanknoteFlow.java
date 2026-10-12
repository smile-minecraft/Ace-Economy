package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.operations.internal.FailedMintRefund;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single shared banknote pipeline for every entry: the command, the Java
 * GUI and the Bedrock form all issue and redeem banknotes through here.
 *
 * <p>Every operation follows region snapshot and checks first, money movement
 * on the IO executor, item handover back on the region thread. Storage never
 * runs on a region thread: the probe, delivery and removal callbacks only
 * touch the inventory and complete futures, while deduction, minting,
 * redemption and compensation run on the IO executor.
 *
 * <p>An operation counts as accepted once its region probe has run with space
 * (withdraw) or its snapshot has reached the flow (redeem) while the
 * caller's generation is still current. A stale generation refuses before
 * any money moves and refunds instead of delivering afterwards; once money
 * has committed, settlement always completes money-safe (deliver-or-refund,
 * remove-or-retain), because abandoning a committed charge is never an
 * option.
 *
 * <p>Money-safety rules enforced here, identical for all three entries:
 *
 * <ul>
 *   <li>A plain refund (a simple deposit) happens only when the note is proven
 *       undelivered: the region dispatch was rejected before running, the
 *       pre-delivery check found a full inventory, {@code addItem} returned the
 *       note as leftover, minting never materialised a note, the caller's
 *       generation went stale while the handover was queued, or a retired
 *       region task was fenced off before a late delivery. In all of these the
 *       nonce was never exposed to any player, so no voiding is needed. A
 *       refund that fails decidedly still closes the outcome as refunded with
 *       its flag cleared, plus an ERROR log carrying player, amount and
 *       nonce — never a silent loss.</li>
 *   <li>When the handover is uncertain (the region callback threw, so the note
 *       may or may not have landed), a refund happens only together with
 *       voiding the minted nonce in the SAME storage transaction, through
 *       {@link EconomyService#compensateUndeliveredNote}. A redeemable note
 *       and a refund can therefore never exist side by side.</li>
 *   <li>When even that atomic void+refund cannot be decided, the outcome stays
 *       a diagnosable {@code PENDING}: nothing is refunded blindly, the note
 *       is never delivered late (the per-operation fence), and the nonce,
 *       player and amount are logged for manual reconciliation. A mint failure
 *       whose refund cannot be proven behaves the same way: a successful
 *       refund closes as refunded with the mint-failure reason, anything else
 *       stays pending with the claim.</li>
 *   <li>An indeterminate deduction pends the same way: no mint, no delivery,
 *       no refund, never a blind retry.</li>
 * </ul>
 *
 * <p>Shutdown stops accepting new operations, fires every armed bounded wait
 * immediately — while IO, storage and the scheduler are still up, and again
 * for any watchdog armed afterwards — then waits out the shared deadline.
 * Operations still unsettled afterwards are returned as diagnosable pendings
 * carrying player, amount, nonce and stage. Callers must drain before
 * releasing the executors and storage.
 *
 * <p>This class never sends player messages: every outcome is a typed value
 * and the caller replies on the region thread. IO completion threads only
 * complete futures.
 */
public final class BanknoteFlow {

    private static final System.Logger LOG = System.getLogger(BanknoteFlow.class.getName());

    /** Stable reason codes for refunded withdrawals, used for reply mapping. */
    public static final String REASON_INVENTORY_FULL = "inventory.full";
    /** The region dispatch was rejected before the callback ever ran. */
    public static final String REASON_DISPATCH_REJECTED = "dispatch.rejected";
    /** The accepted region task never ran before the bounded wait fired. */
    public static final String REASON_DISPATCH_TIMEOUT = "dispatch.timeout";
    /** Minting never materialised a note after the deduction committed. */
    public static final String REASON_MINT_FAILED = "banknote.mint-failed";
    /** The caller's generation went stale before the note could be delivered. */
    public static final String REASON_STALE_GENERATION = "stale.generation";

    /** Stable reason codes for pending (undecided) outcomes. */
    public static final String PENDING_WITHDRAW_INDETERMINATE = "withdraw.indeterminate";
    public static final String PENDING_DELIVERY_UNCERTAIN = "delivery.uncertain";
    public static final String PENDING_CREDIT_INDETERMINATE = "credit.indeterminate";
    public static final String PENDING_MINT_UNCERTAIN = "mint.uncertain";
    public static final String PENDING_SHUTDOWN = "flow.shutdown";

    /** Which hand the redeemed snapshot came from; removal writes back to it. */
    public enum Hand {
        MAIN_HAND,
        OFF_HAND
    }

    /**
     * The commit half of a redemption: decode the snapshot, validate it and
     * commit the credit. Runs on the IO executor. Implementations must never
     * touch the inventory; item removal is owned by this flow on the region
     * thread after a committed credit.
     */
    @FunctionalInterface
    public interface RedeemCommit {
        DepositResult commit(UUID playerId, ItemStack snapshot);
    }

    public enum WithdrawKind {
        DELIVERED,
        REFUSED_FULL,
        REFUSED,
        REFUNDED,
        VOID_REFUNDED,
        PENDING
    }

    public enum RedeemKind {
        CREDITED,
        CREDIT_RETAINED,
        ITEM_MISMATCH,
        REJECTED,
        PENDING
    }

    /** Typed outcome of one async withdraw (issue) operation. */
    public static final class WithdrawOutcome {
        private final WithdrawKind kind;
        private final BanknoteClaim claim;
        private final ItemStack note;
        private final EconomyError error;
        private final String reason;
        private final String message;
        private final boolean refundOk;
        private final Throwable cause;
        private final Amount amount;
        private final Throwable auditError;

        private WithdrawOutcome(WithdrawKind kind, BanknoteClaim claim, ItemStack note,
                                EconomyError error, String reason, String message,
                                boolean refundOk, Throwable cause,
                                Amount amount, Throwable auditError) {
            this.kind = kind;
            this.claim = claim;
            this.note = note;
            this.error = error;
            this.reason = reason;
            this.message = message;
            this.refundOk = refundOk;
            this.cause = cause;
            this.amount = amount;
            this.auditError = auditError;
        }

        private WithdrawOutcome(WithdrawKind kind, BanknoteClaim claim, ItemStack note,
                                EconomyError error, String reason, String message,
                                boolean refundOk, Throwable cause) {
            this(kind, claim, note, error, reason, message, refundOk, cause, null, null);
        }

        static WithdrawOutcome delivered(BanknoteClaim claim, ItemStack note, Amount amount,
                                         Throwable auditError) {
            return new WithdrawOutcome(WithdrawKind.DELIVERED, claim, note,
                    null, null, null, true, null, amount, auditError);
        }

        static WithdrawOutcome refusedFull() {
            return new WithdrawOutcome(WithdrawKind.REFUSED_FULL, null, null,
                    EconomyError.INVENTORY_FULL, null, "inventory full", false, null);
        }

        static WithdrawOutcome refused(EconomyError error, String message) {
            return new WithdrawOutcome(WithdrawKind.REFUSED, null, null,
                    error, null, message, false, null);
        }

        static WithdrawOutcome refunded(EconomyError error, String reason, String message,
                                        boolean refundOk) {
            return new WithdrawOutcome(WithdrawKind.REFUNDED, null, null,
                    error, reason, message, refundOk, null);
        }

        static WithdrawOutcome voidRefunded(BanknoteClaim claim) {
            return new WithdrawOutcome(WithdrawKind.VOID_REFUNDED, claim, null,
                    null, null, null, true, null);
        }

        static WithdrawOutcome pending(String reason, String detail, Throwable cause) {
            return new WithdrawOutcome(WithdrawKind.PENDING, null, null,
                    null, reason, detail, false, cause);
        }

        static WithdrawOutcome pending(BanknoteClaim claim, String reason, String detail,
                                        Throwable cause) {
            return new WithdrawOutcome(WithdrawKind.PENDING, claim, null,
                    null, reason, detail, false, cause);
        }

        public boolean isDelivered() {
            return kind == WithdrawKind.DELIVERED;
        }

        public boolean isRefusedFull() {
            return kind == WithdrawKind.REFUSED_FULL;
        }

        public boolean isRefused() {
            return kind == WithdrawKind.REFUSED;
        }

        public boolean isRefunded() {
            return kind == WithdrawKind.REFUNDED;
        }

        public boolean isVoidRefunded() {
            return kind == WithdrawKind.VOID_REFUNDED;
        }

        public boolean isPending() {
            return kind == WithdrawKind.PENDING;
        }

        /** The minted claim; present for delivered, void-refunded and most pending outcomes. */
        public @Nullable BanknoteClaim claim() {
            return claim;
        }

        /** The delivered note; only present for delivered outcomes. */
        public @Nullable ItemStack note() {
            return note;
        }

        public @Nullable EconomyError error() {
            return error;
        }

        /** Stable reason code for refunded and pending outcomes; null otherwise. */
        public @Nullable String reason() {
            return reason;
        }

        public @Nullable String message() {
            return message;
        }

        /** For refunded outcomes: whether the compensating deposit succeeded. */
        public boolean refundOk() {
            return refundOk;
        }

        /** For pending outcomes: the underlying failure when one exists. */
        public @Nullable Throwable pendingCause() {
            return cause;
        }

        /** The withdrawn amount; present for delivered outcomes. */
        public @Nullable Amount amount() {
            return amount;
        }

        /** The deduction's audit failure, if the commit reported one. */
        public @Nullable Throwable auditError() {
            return auditError;
        }

        @Override
        public String toString() {
            return "WithdrawOutcome{" + kind
                    + (reason != null ? ", reason=" + reason : "")
                    + (message != null ? ", message=" + message : "")
                    + (error != null ? ", error=" + error : "")
                    + '}';
        }
    }

    /** Typed outcome of one async redeem (deposit) operation. */
    public static final class RedeemOutcome {
        private final RedeemKind kind;
        private final long value;
        private final String currencyId;
        private final String reason;
        private final String detail;
        private final Throwable cause;

        private RedeemOutcome(RedeemKind kind, long value, String currencyId,
                              String reason, String detail, Throwable cause) {
            this.kind = kind;
            this.value = value;
            this.currencyId = currencyId;
            this.reason = reason;
            this.detail = detail;
            this.cause = cause;
        }

        static RedeemOutcome credited(long value, String currencyId) {
            return new RedeemOutcome(RedeemKind.CREDITED, value, currencyId, null, null, null);
        }

        static RedeemOutcome creditRetained(long value, String currencyId, String detail) {
            return new RedeemOutcome(RedeemKind.CREDIT_RETAINED, value, currencyId,
                    "item.remove-failed", detail, null);
        }

        static RedeemOutcome itemMismatch() {
            return new RedeemOutcome(RedeemKind.ITEM_MISMATCH, 0L, null,
                    "item.mismatch", null, null);
        }

        static RedeemOutcome rejected(String reason) {
            return new RedeemOutcome(RedeemKind.REJECTED, 0L, null, reason, null, null);
        }

        static RedeemOutcome pending(String reason, String detail, Throwable cause) {
            return new RedeemOutcome(RedeemKind.PENDING, 0L, null, reason, detail, cause);
        }

        public boolean isCredited() {
            return kind == RedeemKind.CREDITED;
        }

        public boolean isCreditRetained() {
            return kind == RedeemKind.CREDIT_RETAINED;
        }

        public boolean isItemMismatch() {
            return kind == RedeemKind.ITEM_MISMATCH;
        }

        public boolean isRejected() {
            return kind == RedeemKind.REJECTED;
        }

        public boolean isPending() {
            return kind == RedeemKind.PENDING;
        }

        /** Actually-credited value; only meaningful for credited/retained outcomes. */
        public long value() {
            return value;
        }

        public @Nullable String currencyId() {
            return currencyId;
        }

        /** Stable reason code for rejected and pending outcomes. */
        public @Nullable String reason() {
            return reason;
        }

        public @Nullable String detail() {
            return detail;
        }

        public @Nullable Throwable pendingCause() {
            return cause;
        }

        @Override
        public String toString() {
            return "RedeemOutcome{" + kind
                    + (reason != null ? ", reason=" + reason : "")
                    + (detail != null ? ", detail=" + detail : "")
                    + '}';
        }
    }

    /** Bounded-shutdown tally: how many accepted operations settled in time. */
    public record ShutdownResult(int settled, int leftBehind, List<PendingOperation> pending) {
        public ShutdownResult(int settled, int leftBehind) {
            this(settled, leftBehind, List.of());
        }
    }

    /**
     * A shutdown-stranded operation that could not be settled while storage
     * was still up. Carries everything manual reconciliation needs: who was
     * charged, how much, which note (when one was minted) and where the
     * pipeline stopped.
     */
    public record PendingOperation(UUID playerId, String currencyId, String amount,
                                   UUID nonce, String stage, String detail) {
    }

    private final EconomyApi api;
    private final EconomyService economy;
    private final AtomicRedemptionStore redemptions;
    private final BanknoteFactory banknotes;
    private final CurrencyDisplayHolder display;
    private final FoliaContextExecutor folia;
    private final Executor io;
    private final ScheduledExecutorService watchdogs;
    private final Duration dispatchTimeout;

    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Trusted-refund ledger holding the single-use issuance rights. Null means
     * no issuance rights (legacy tests and unconnected flows use the ordinary
     * deposit fallback or the gate-checked direct void); production Bootstrap
     * binds the same ledger instance as the core service so only a real
     * committed deduction can authorize a single-use refund: an observably
     * failed mint for the plain path, an uncertain handover for the atomic
     * void+refund path.
     */
    private volatile com.smile.aceeconomy.operations.internal.TrustedRefundLedger refundLedger;

    public BanknoteFlow(@NotNull EconomyApi api,
                        @NotNull EconomyService economy,
                        @NotNull AtomicRedemptionStore redemptions,
                        @NotNull BanknoteFactory banknotes,
                        @NotNull CurrencyDisplayHolder display,
                        @NotNull FoliaContextExecutor folia,
                        @NotNull Executor io,
                        @NotNull ScheduledExecutorService watchdogs,
                        @NotNull Duration dispatchTimeout) {
        this.api = Objects.requireNonNull(api, "api");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.redemptions = Objects.requireNonNull(redemptions, "redemptions");
        this.banknotes = Objects.requireNonNull(banknotes, "banknotes");
        this.display = Objects.requireNonNull(display, "display");
        this.folia = Objects.requireNonNull(folia, "folia");
        this.io = Objects.requireNonNull(io, "io");
        this.watchdogs = Objects.requireNonNull(watchdogs, "watchdogs");
        this.dispatchTimeout = Objects.requireNonNull(dispatchTimeout, "dispatchTimeout");
    }

    /**
     * Bind the trusted-refund issuance rights. The failed-mint,
     * proven-undelivered, and uncertain-handover paths authorize through this
     * ledger and refund through the core's single-use trusted path; without
     * it they fall back to the ordinary deposit or the gate-checked direct
     * void.
     */
    public void bindRefundLedger(
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger) {
        this.refundLedger = ledger;
    }

    /** Skeleton accessor for the wired ledger; null when unconnected. */
    public com.smile.aceeconomy.operations.internal.TrustedRefundLedger refundLedger() {
        return refundLedger;
    }

    /**
     * Phase-1 seam for durable issuance progress. Null means the flow runs the
     * legacy memory-only path (existing tests); production Bootstrap must bind
     * the real backend-backed store in phase 2 so every withdraw route shares
     * one durable issuance core instead of bypassing it. Binding alone changes
     * no behaviour yet.
     */
    private volatile com.smile.aceeconomy.ports.persistence.BanknoteIssuanceStore issuanceStore;

    public void bindIssuanceStore(
            com.smile.aceeconomy.ports.persistence.BanknoteIssuanceStore store) {
        this.issuanceStore = store;
    }

    /** Skeleton accessor for the wired issuance store; null when unconnected. */
    public com.smile.aceeconomy.ports.persistence.BanknoteIssuanceStore issuanceStore() {
        return issuanceStore;
    }

    /**
     * Phase-1 seam for the atomic issuance core. Null means the flow runs the
     * legacy {@code api.withdraw} path (existing tests); production Bootstrap
     * must bind the SAME backend-backed instance as the core service in phase
     * 2, so the command entry ({@link #withdraw}) and the GUI sync entry
     * ({@link #withdrawCommit}) both deduct through
     * {@link com.smile.aceeconomy.application.EconomyService#issueBanknote}
     * plus {@code PREPARED} before the deduction and the delivery markers
     * around the handover — never {@code api.withdraw} followed by a
     * standalone mark. Binding alone changes no behaviour yet; phase 2 threads
     * this store through the shared deduct-and-mint core.
     */
    private volatile com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomicIssuanceStore;

    public void bindAtomicIssuanceStore(
            com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore store) {
        this.atomicIssuanceStore = store;
    }

    /** Skeleton accessor for the wired atomic issuance store; null when unconnected. */
    public com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomicIssuanceStore() {
        return atomicIssuanceStore;
    }

    /**
     * Recovery barrier for new managed issuance. Null means the gate stays
     * open (existing tests and unconnected flows); production Bootstrap binds
     * the startup runner closed and it opens only when the startup recovery
     * run finishes. Queries never consult it.
     */
    private volatile com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner startupRecovery;

    public void bindStartupRecovery(
            com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner runner) {
        this.startupRecovery = runner;
    }

    /** Skeleton accessor for the wired recovery barrier; null when unconnected. */
    public com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner startupRecovery() {
        return startupRecovery;
    }

    /**
     * Refusal for new managed issuance while the startup recovery barrier is
     * still closed. Nothing was deducted; a later retry after recovery is safe.
     */
    private static WithdrawOutcome recoveryBlocked() {
        return WithdrawOutcome.refused(EconomyError.TRANSACTION_CANCELLED,
                "banknote issuance is paused while startup recovery is incomplete;"
                        + " please try again later");
    }

    private boolean isRecoveryBlocked() {
        com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner barrier = startupRecovery;
        return barrier != null && !barrier.isRecoveryComplete();
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    /**
     * Per-operation shutdown state. Each operation arms its bounded waits as
     * early-fire settlers: shutdown runs them immediately — while IO,
     * storage and the scheduler are still up — instead of waiting out the
     * full dispatch timeout. A settler that loses its race is a no-op, so
     * firing early can never double-settle.
     */
    private abstract static sealed class FlowOp permits WithdrawOp, RedeemOp {
        final AtomicReference<Runnable> settler = new AtomicReference<>();
        final AtomicBoolean shutdownFired = new AtomicBoolean(false);
        volatile String stage;

        void fireSettler() {
            shutdownFired.set(true);
            Runnable fire = settler.getAndSet(null);
            if (fire != null) {
                try {
                    fire.run();
                } catch (Throwable ignored) {
                    // Settlement is best-effort; the bounded wait below reports.
                }
            }
        }

        boolean isShutdownFired() {
            return shutdownFired.get();
        }

        abstract PendingOperation diagnose();
    }

    private static final class WithdrawOp extends FlowOp {
        final UUID playerId;
        final String currencyId;
        final Amount amount;
        volatile MintedNote minted;

        WithdrawOp(UUID playerId, String currencyId, Amount amount) {
            this.playerId = playerId;
            this.currencyId = currencyId;
            this.amount = amount;
            this.stage = "probe";
        }

        @Override
        PendingOperation diagnose() {
            MintedNote done = minted;
            return new PendingOperation(playerId, currencyId,
                    amount.value().toPlainString(),
                    done == null ? null : done.claim().nonce(), stage,
                    done == null
                            ? "shutdown stranded the withdraw before any note was minted"
                            : "shutdown stranded the withdraw after the deduction committed");
        }
    }

    private static final class RedeemOp extends FlowOp {
        final UUID playerId;
        final ItemStack snapshot;

        RedeemOp(UUID playerId, ItemStack snapshot) {
            this.playerId = playerId;
            this.snapshot = snapshot;
            this.stage = "commit";
        }

        @Override
        PendingOperation diagnose() {
            return new PendingOperation(playerId, "unknown", "unknown", null, stage,
                    "shutdown stranded the redeem before its outcome was decided");
        }
    }

    private final Set<WithdrawOp> withdrawOps = ConcurrentHashMap.newKeySet();
    private final Set<RedeemOp> redeemOps = ConcurrentHashMap.newKeySet();

    /**
     * Stop accepting new operations, fire every armed bounded wait now so
     * stranded deductions are compensated while storage is still up, then
     * wait for accepted ones up to the shared deadline. Operations still
     * unsettled afterwards are returned as diagnosable pendings carrying
     * player, amount, nonce and stage — never a bare count — and logged the
     * same way; a forced crash may still strand them, which is why crash
     * recovery is explicitly out of scope.
     *
     * <p>Callers must invoke this before releasing the IO executor, storage
     * and scheduler: the early-fired compensations run on those resources.
     * In the production lifecycle the presentation slice drains first and
     * persistence stops last, in reverse registration order.
     */
    public @NotNull ShutdownResult shutdown(long deadlineMillis) {
        accepting.set(false);
        for (WithdrawOp op : List.copyOf(withdrawOps)) {
            op.fireSettler();
        }
        for (RedeemOp op : List.copyOf(redeemOps)) {
            op.fireSettler();
        }
        if (inFlight.isEmpty()) {
            return new ShutdownResult(0, 0, List.of());
        }
        // Single shared deadline: total wait is bounded by the deadline, not N times it.
        long start = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, deadlineMillis));
        int settled = 0;
        // Snapshot: completions remove themselves as they settle.
        for (CompletableFuture<?> pending : List.copyOf(inFlight)) {
            long remaining = budgetNanos - (System.nanoTime() - start);
            if (remaining <= 0L) {
                break;
            }
            try {
                pending.get(remaining, TimeUnit.NANOSECONDS);
                settled++;
            } catch (TimeoutException timeout) {
                break;
            } catch (ExecutionException failed) {
                // Settled with a failure outcome or a bug signal; either way it is done.
                settled++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int leftBehind = inFlight.size();
        List<PendingOperation> pending = List.of();
        if (leftBehind > 0) {
            List<PendingOperation> diagnoses = new ArrayList<>();
            for (WithdrawOp op : List.copyOf(withdrawOps)) {
                diagnoses.add(op.diagnose());
            }
            for (RedeemOp op : List.copyOf(redeemOps)) {
                diagnoses.add(diagnoseRedeem(op));
            }
            pending = List.copyOf(diagnoses);
            StringBuilder detail = new StringBuilder();
            for (PendingOperation diagnosed : pending) {
                detail.append(" [player=").append(diagnosed.playerId())
                        .append(" amount=").append(diagnosed.amount())
                        .append(' ').append(diagnosed.currencyId())
                        .append(" nonce=").append(diagnosed.nonce())
                        .append(" stage=").append(diagnosed.stage()).append(']');
            }
            LOG.log(System.Logger.Level.ERROR,
                    "banknote flow shutdown left {0} operations unsettled after the deadline;"
                            + " they keep their diagnostics and settle without messaging:"
                            + detail,
                    leftBehind);
        }
        return new ShutdownResult(settled, leftBehind, pending);
    }

    private PendingOperation diagnoseRedeem(RedeemOp op) {
        try {
            Optional<BanknoteClaim> decoded = safeDecode(op.snapshot);
            if (decoded.isPresent()) {
                BanknoteClaim claim = decoded.get();
                return new PendingOperation(op.playerId, claim.currency(),
                        Long.toString(claim.value()), claim.nonce(), op.stage,
                        "shutdown stranded the redeem before its outcome was decided");
            }
        } catch (Throwable ignored) {
            // Diagnosis is best-effort; fall through to the unknown-note record.
        }
        return op.diagnose();
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        inFlight.add(future);
        future.whenComplete((result, failure) -> inFlight.remove(future));
        return future;
    }

    // ---------------- withdraw (issue) ----------------

    /**
     * Whether the caller's generation is still current. Sampled before any
     * money moves and again before a late region callback places an item: a
     * reload or generation change in between refuses before charging and
     * refunds instead of delivering afterwards. An operation counts as
     * accepted once its probe has run with space and this check still
     * passes; after the deduction commits, settlement always completes
     * money-safe (deliver-or-refund), because abandoning a committed charge
     * is never an option. Suppliers must never throw; a throw fails closed
     * like a stale generation.
     */
    @FunctionalInterface
    public interface GenerationCheck {
        boolean isCurrent();
    }

    private static boolean isCurrent(GenerationCheck check) {
        if (check == null) {
            return true;
        }
        try {
            return check.isCurrent();
        } catch (Throwable t) {
            return false;
        }
    }

    public @NotNull CompletableFuture<WithdrawOutcome> withdraw(@NotNull UUID playerId,
                                                                 String currencyId,
                                                                 @NotNull Amount amount) {
        return withdraw(playerId, currencyId, amount, null);
    }

    public @NotNull CompletableFuture<WithdrawOutcome> withdraw(@NotNull UUID playerId,
                                                                 String currencyId,
                                                                 @NotNull Amount amount,
                                                                 GenerationCheck stillValid) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amount, "amount");
        String resolved = currencyId == null || currencyId.isBlank()
                ? display.get().defaultCurrencyId() : currencyId;
        if (!accepting.get()) {
            return CompletableFuture.completedFuture(WithdrawOutcome.pending(
                    PENDING_SHUTDOWN, "shutting down; nothing was deducted", null));
        }
        WithdrawOp op = new WithdrawOp(playerId, resolved, amount);
        if (isRecoveryBlocked()) {
            // New managed issuance waits on startup recovery instead of
            // silently passing while proven deductions may still need refunds.
            // Nothing was deducted; queries are unaffected.
            return completeOp(op, recoveryBlocked());
        }
        withdrawOps.add(op);
        // Three-state probe outcome: present=true has space, present=false full,
        // empty = the accepted region callback never ran (bounded wait fired).
        CompletableFuture<Optional<Boolean>> probed = new CompletableFuture<>();
        op.settler.set(() -> probed.complete(Optional.empty()));
        boolean dispatched;
        try {
            dispatched = folia.runForPlayer(playerId, player -> {
                try {
                    probed.complete(Optional.of(player.getInventory().firstEmpty() != -1));
                } catch (Throwable t) {
                    probed.completeExceptionally(t);
                }
            });
        } catch (Throwable dispatchFailure) {
            // The probe callback never ran: nothing was deducted, so refuse
            // without charging. A refund would fabricate compensation for a
            // charge that never happened.
            return completeOp(op, WithdrawOutcome.refused(
                    EconomyError.TRANSACTION_CANCELLED,
                    "player left before the withdraw could start"));
        }
        if (!dispatched) {
            // Nothing has happened yet: refuse without deducting.
            return completeOp(op, WithdrawOutcome.refused(
                    EconomyError.TRANSACTION_CANCELLED,
                    "player left before the withdraw could start"));
        }
        // Bind the watchdog to an explicit Runnable: a value-compatible lambda would
        // bind to schedule(Callable, ...) and schedulers with only the Runnable seam
        // would silently never fire it.
        Runnable probeTimeout = () -> probed.complete(Optional.empty());
        op.settler.set(probeTimeout);
        try {
            watchdogs.schedule(probeTimeout, dispatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Throwable scheduleFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "withdraw probe watchdog could not be armed: " + scheduleFailure, scheduleFailure);
        }
        if (op.isShutdownFired()) {
            // Shutdown swept past before this watchdog was armed: fire now so
            // the probe settles while storage is still up instead of waiting
            // out the full timeout past the storage close.
            op.fireSettler();
        }
        CompletableFuture<Optional<Boolean>> safeProbed = probed.exceptionally(probeFailure -> {
            // The probe runs before any deduction: an inventory read failure
            // means nothing was charged, so refuse instead of failing the
            // future. Errors escape: a broken runtime must surface, never
            // masquerade as a business refusal.
            Throwable cause = probeFailure instanceof java.util.concurrent.CompletionException completed
                    && completed.getCause() != null ? completed.getCause() : probeFailure;
            if (cause instanceof Error error) {
                throw error;
            }
            return Optional.empty();
        });
        CompletableFuture<WithdrawOutcome> result = safeProbed.thenCompose(probe -> {
            if (probe.isEmpty()) {
                return CompletableFuture.completedFuture(op.isShutdownFired()
                        ? WithdrawOutcome.refused(EconomyError.TRANSACTION_CANCELLED,
                                "shutting down; nothing was deducted")
                        : WithdrawOutcome.refused(EconomyError.TRANSACTION_CANCELLED,
                                "player left before the withdraw could start"));
            }
            if (!probe.get()) {
                return CompletableFuture.completedFuture(WithdrawOutcome.refusedFull());
            }
            if (!isCurrent(stillValid)) {
                op.stage = "stale";
                return CompletableFuture.completedFuture(WithdrawOutcome.refused(
                        EconomyError.TRANSACTION_CANCELLED,
                        "stale generation; please try again"));
             }
             op.stage = "deduct";
             return CompletableFuture.supplyAsync(() -> {
                         if (!isCurrent(stillValid)) {
                             op.stage = "stale";
                             return new MintDecision.NoCharge(
                                     EconomyError.TRANSACTION_CANCELLED,
                                     "stale generation; please try again");
                         }
                         return deductAndMint(playerId, resolved, amount);
                     }, io)
                     .thenCompose(minted -> switch (minted) {
                        case MintDecision.Minted ok -> {
                            op.minted = ok.note();
                            op.stage = "deliver";
                            yield deliver(playerId, ok.note(), stillValid, op);
                        }
                        case MintDecision.NoCharge refused ->
                                CompletableFuture.completedFuture(WithdrawOutcome.refused(
                                        refused.error(), refused.message()));
                        case MintDecision.Compensated compensated ->
                                CompletableFuture.completedFuture(WithdrawOutcome.refunded(
                                        EconomyError.INVALID_AMOUNT, REASON_MINT_FAILED,
                                        compensated.detail(), true));
                        case MintDecision.Undecided undecided -> {
                            auditMintPending(undecided);
                            yield CompletableFuture.completedFuture(WithdrawOutcome.pending(
                                    undecided.claim(), PENDING_MINT_UNCERTAIN,
                                    undecided.detail(), undecided.cause()));
                        }
                    })
                    .handle((outcome, failure) -> {
                        if (failure == null) {
                            return outcome;
                        }
                        Throwable cause = failure instanceof java.util.concurrent.CompletionException completed
                                && completed.getCause() != null ? completed.getCause() : failure;
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        if (cause instanceof IndeterminatePersistenceException indeterminate) {
                            return WithdrawOutcome.pending(PENDING_WITHDRAW_INDETERMINATE,
                                    "the deduction may or may not have committed;"
                                            + " it was not retried and nothing was delivered",
                                    indeterminate);
                        }
                        if (cause instanceof PersistenceException uncommitted
                                && !uncommitted.isCommitted()) {
                            return WithdrawOutcome.refused(EconomyError.AUDIT_FAILURE,
                                    "storage unavailable; nothing was changed,"
                                            + " please try again later");
                        }
                        if (cause instanceof PersistenceException committed) {
                            return WithdrawOutcome.pending(PENDING_WITHDRAW_INDETERMINATE,
                                    "the deduction committed but the result is uncertain;"
                                            + " it was not retried and nothing was delivered",
                                    committed);
                        }
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new java.util.concurrent.CompletionException(cause);
                    });
        });
        CompletableFuture<WithdrawOutcome> tracked = track(result);
        tracked.whenComplete((outcome, failure) -> {
            withdrawOps.remove(op);
            op.settler.set(null);
        });
        return tracked;
    }

    private <T> CompletableFuture<T> completeOp(WithdrawOp op, T outcome) {
        withdrawOps.remove(op);
        op.settler.set(null);
        return track(CompletableFuture.completedFuture(outcome));
    }

    public @NotNull CompletableFuture<WithdrawOutcome> withdraw(@NotNull UUID playerId,
                                                                 String currencyId,
                                                                 long value) {
        String requested = currencyId == null || currencyId.isBlank()
                ? display.get().defaultCurrencyId() : currencyId;
        Currency currency;
        try {
            currency = display.get().get(requested);
        } catch (IllegalArgumentException unknownCurrency) {
            return CompletableFuture.completedFuture(WithdrawOutcome.refused(
                    EconomyError.CURRENCY_NOT_FOUND, "unknown currency"));
        }
        Amount amount;
        try {
            amount = Amount.of(value, currency.scale());
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(WithdrawOutcome.refused(
                    EconomyError.INVALID_AMOUNT, "invalid amount"));
        }
        return withdraw(playerId, currency.id(), amount);
    }

    public @NotNull CompletableFuture<WithdrawOutcome> withdraw(@NotNull UUID playerId,
                                                                 String currencyId,
                                                                 long value,
                                                                 GenerationCheck stillValid) {
        String requested = currencyId == null || currencyId.isBlank()
                ? display.get().defaultCurrencyId() : currencyId;
        Currency currency;
        try {
            currency = display.get().get(requested);
        } catch (IllegalArgumentException unknownCurrency) {
            return CompletableFuture.completedFuture(WithdrawOutcome.refused(
                    EconomyError.CURRENCY_NOT_FOUND, "unknown currency"));
        }
        Amount amount;
        try {
            amount = Amount.of(value, currency.scale());
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(WithdrawOutcome.refused(
                    EconomyError.INVALID_AMOUNT, "invalid amount"));
        }
        return withdraw(playerId, currency.id(), amount, stillValid);
    }

    /** Internal carrier for a minted note plus everything compensation needs. */
    private record MintedNote(BanknoteClaim claim, ItemStack note,
                              UUID playerId, String currencyId, Amount amount,
                              Throwable auditError,
                              /**
                               * The durable issuance nonce when the deduction
                               * went through the atomic issuance core; null on
                               * the legacy path. A non-null nonce means every
                               * compensation must void+refund atomically — a
                               * plain refund would leave the nonce redeemable
                               * for a second payout.
                               */
                              UUID issuanceNonce) {
        MintedNote(BanknoteClaim claim, ItemStack note,
                   UUID playerId, String currencyId, Amount amount,
                   Throwable auditError) {
            this(claim, note, playerId, currencyId, amount, auditError, null);
        }
    }

    /**
     * What the deduct-and-mint step decided. A materialised note continues to
     * delivery; anything else already settled its money movement, so the
     * pipeline maps it straight to a typed outcome instead of deducting or
     * delivering again.
     */
    private sealed interface MintDecision permits MintDecision.Minted, MintDecision.NoCharge,
            MintDecision.Compensated, MintDecision.Undecided {
        /** The deduction committed and the note materialised. */
        record Minted(MintedNote note) implements MintDecision {
        }

        /** Nothing was deducted: unknown currency, unissuable amount, refused debit. */
        record NoCharge(EconomyError error, String message) implements MintDecision {
        }

        /** The deduction committed, minting failed, and the refund succeeded. */
        record Compensated(String detail) implements MintDecision {
        }

        /**
         * The deduction committed, minting failed, and the refund cannot be
         * proven: failed decidedly ({@code cause == null}) or uncertainly.
         * The claim is carried for diagnosis; the note never existed, so no
         * voiding is needed, but the charge may still stand.
         */
        record Undecided(BanknoteClaim claim, String detail, Throwable cause)
                implements MintDecision {
        }
    }

    private MintDecision deductAndMint(UUID playerId, String currencyId, Amount amount) {
        // Both fallible inputs resolve BEFORE the deduction commits: an unknown
        // currency or an amount that cannot back a whole-number banknote claim
        // refuses without charging. Only minting, which runs after the commit,
        // may still fail — and that path compensates below.
        Currency currency;
        try {
            currency = display.get().get(currencyId);
        } catch (IllegalArgumentException unknownCurrency) {
            return new MintDecision.NoCharge(EconomyError.CURRENCY_NOT_FOUND, "unknown currency");
        }
        return deductAndMintResolved(playerId, currency, amount);
    }

    /**
     * Synchronous withdraw commit core shared with the bank GUI use case: deduct
     * and mint on the calling thread, which must be an IO thread, never a region
     * thread. The GUI entry delegates its sync core here so both entries deduct,
     * mint and compensate through the same steps; the async withdraw pipeline
     * above adds the region probe, delivery and bounded waits around it.
     */
    public @NotNull com.smile.aceeconomy.ports.WithdrawResult withdrawCommit(
            @NotNull UUID playerId, @NotNull Currency currency, long value) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currency, "currency");
        if (isRecoveryBlocked()) {
            return com.smile.aceeconomy.ports.WithdrawResult.rejected(
                    "banknote issuance is paused while startup recovery is incomplete;"
                            + " please try again later");
        }
        Amount amount = Amount.of(value, currency.scale());
        MintDecision decision = deductAndMintResolved(playerId, currency, amount);
        if (decision instanceof MintDecision.Minted ok) {
            return com.smile.aceeconomy.ports.WithdrawResult.success(ok.note().note());
        }
        if (decision instanceof MintDecision.NoCharge refused) {
            return com.smile.aceeconomy.ports.WithdrawResult.rejected(refused.message());
        }
        if (decision instanceof MintDecision.Compensated compensated) {
            return com.smile.aceeconomy.ports.WithdrawResult.rejected(compensated.detail());
        }
        MintDecision.Undecided undecided = (MintDecision.Undecided) decision;
        if (undecided.cause() != null) {
            if (undecided.cause() instanceof Error error) {
                throw error;
            }
            if (undecided.cause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new java.util.concurrent.CompletionException(undecided.cause());
        }
        return com.smile.aceeconomy.ports.WithdrawResult.rejected(
                "banknote.mint-refund-failed: " + undecided.detail());
    }

    private MintDecision deductAndMintResolved(UUID playerId, Currency currency,
                                               Amount amount) {
        BanknoteClaim claim;
        try {
            claim = claim(playerId, currency, amount.value().longValueExact());
        } catch (ArithmeticException unissuable) {
            return new MintDecision.NoCharge(EconomyError.INVALID_AMOUNT,
                    "amount cannot be issued as a banknote");
        }
        com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomic =
                atomicIssuanceStore;
        if (atomic != null && economy != null) {
            return deductAndMintAtomic(playerId, currency, amount, claim, atomic);
        }
        EconomyResult<Amount> result = api.withdraw(playerId, currency.id(), amount);
        if (result.isFailure()) {
            return new MintDecision.NoCharge(result.error(), result.message());
        }
        ItemStack note;
        try {
            note = banknotes.mint(claim).orElse(null);
        } catch (RuntimeException mintThrew) {
            // A throwing mint is the same money-safety hole as an empty one: the
            // deduction committed, so the charge is compensated and the caller sees
            // a closed outcome, not an exceptional future. Errors are deliberately
            // not caught — a broken VM must surface, never masquerade as a failed
            // issuance.
            LOG.log(System.Logger.Level.WARNING,
                    "banknote mint threw: player={0}, currency={1}, amount={2}, type={3}",
                    new Object[]{playerId, currency.id(), amount,
                            mintThrew.getClass().getName()});
            return compensateFailedMint(playerId, currency.id(), amount,
                    claim, "banknote mint failed: " + mintThrew.getClass().getSimpleName());
        }
        if (note == null) {
            return compensateFailedMint(playerId, currency.id(), amount,
                    claim, "banknote could not be created");
        }
        return new MintDecision.Minted(new MintedNote(claim, note, playerId, currency.id(), amount,
                result.auditFailure().orElse(null)));
    }

    /**
     * Deduct and mint through the durable atomic issuance core shared with the
     * core service: the {@code PREPARED} record plus the deduction commit in
     * ONE storage transaction via
     * {@link EconomyService#issueBanknote}, then minting. Runs on the calling
     * (IO) thread, never a region thread. A mint failure voids+refunds through
     * the same durable core; the note never existed, but its issuance record
     * does, so a plain refund would leave the nonce redeemable.
     *
     * <p>Persistence failures propagate: the async pipeline maps an
     * undecided failure to refused and an indeterminate or committed-unknown
     * failure to pending, never a blind retry.</p>
     */
    private MintDecision deductAndMintAtomic(UUID playerId, Currency currency,
                                             Amount amount, BanknoteClaim claim,
                                             com.smile.aceeconomy.ports.persistence
                                                     .AtomicBanknoteIssuanceStore atomic) {
        EconomyResult<Amount> result =
                economy.issueBanknote(playerId, currency.id(), amount, claim.nonce(), atomic);
        if (result.isFailure()) {
            return new MintDecision.NoCharge(result.error(), result.message());
        }
        ItemStack note;
        try {
            note = banknotes.mint(claim).orElse(null);
        } catch (RuntimeException mintThrew) {
            LOG.log(System.Logger.Level.WARNING,
                    "banknote mint threw: player={0}, currency={1}, amount={2}, type={3}",
                    new Object[]{playerId, currency.id(), amount,
                            mintThrew.getClass().getName()});
            return compensateAtomicMintFailure(playerId, currency.id(), amount,
                    claim, atomic,
                    "banknote mint failed: " + mintThrew.getClass().getSimpleName());
        }
        if (note == null) {
            return compensateAtomicMintFailure(playerId, currency.id(), amount,
                    claim, atomic, "banknote could not be created");
        }
        return new MintDecision.Minted(new MintedNote(claim, note, playerId, currency.id(), amount,
                result.auditFailure().orElse(null), claim.nonce()));
    }

    /**
     * Compensate an atomically committed deduction whose note never
     * materialised: void the issuance nonce and refund in the SAME storage
     * transaction through the durable core. A decided void+refund closes as
     * compensated; anything else stays undecided with the claim for diagnosis
     * and startup recovery — never a blind retry and never a plain refund
     * that would leave the nonce redeemable next to the refund.
     */
    private MintDecision compensateAtomicMintFailure(UUID playerId, String currencyId,
                                                     Amount amount, BanknoteClaim claim,
                                                     com.smile.aceeconomy.ports.persistence
                                                             .AtomicBanknoteIssuanceStore atomic,
                                                     String failureDetail) {
        EconomyResult<Amount> refund;
        try {
            refund = economy.refundIssuanceForRecovery(claim.nonce(), atomic);
        } catch (Error error) {
            throw error;
        } catch (IndeterminatePersistenceException indeterminate) {
            LOG.log(System.Logger.Level.ERROR,
                    "withdraw compensation uncertain: player={0}, currency={1}, amount={2},"
                            + " nonce={3}, reason={4}",
                    new Object[]{playerId, currencyId, amount, claim.nonce(),
                            indeterminate.getClass().getSimpleName()});
            return new MintDecision.Undecided(claim, failureDetail, indeterminate);
        } catch (RuntimeException refundFailure) {
            LOG.log(System.Logger.Level.ERROR,
                    "withdraw compensation threw: player={0}, currency={1}, amount={2},"
                            + " nonce={3}, reason={4}",
                    new Object[]{playerId, currencyId, amount, claim.nonce(),
                            refundFailure.getClass().getSimpleName()});
            return new MintDecision.Undecided(claim, failureDetail, refundFailure);
        }
        if (refund.isSuccess()) {
            return new MintDecision.Compensated(failureDetail);
        }
        LOG.log(System.Logger.Level.ERROR,
                "withdraw compensation failed: player={0}, currency={1}, amount={2},"
                        + " nonce={3}, reason={4}",
                new Object[]{playerId, currencyId, amount, claim.nonce(),
                        refund == null ? "no result" : refund.message()});
        return new MintDecision.Undecided(claim, failureDetail, null);
    }

    /**
     * Compensate a committed deduction whose note was never materialised (mint
     * returned empty or threw): refund the withdrawn amount exactly once. The
     * note never existed, so its nonce was never exposed and no voiding is
     * needed. A decided refund closes as compensated; a failed or uncertain
     * refund stays undecided with the claim for manual reconciliation and is
     * never retried here.
     *
     * <p>When a trusted-refund ledger is bound, the refund travels the
     * single-use trusted path: this method authorizes exactly this committed
     * deduction (a real commit receipt only the pipeline holds, never a
     * caller-supplied flag), then the core credits it exempt from the freeze
     * and read-only gate. Mint success, already-redeemed notes, and tampered
     * parameters can never authorize here because this method only runs for
     * an observably failed mint whose note never left memory. Without a
     * bound ledger the legacy ordinary deposit is used.
     */
    private MintDecision compensateFailedMint(UUID playerId, String currencyId,
                                               Amount amount, BanknoteClaim claim,
                                               String failureDetail) {
        com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger = refundLedger;
        if (ledger != null) {
            try {
                ledger.authorize(claim.nonce(), playerId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT);
            } catch (Throwable authorizeFailure) {
                if (authorizeFailure instanceof Error error) {
                    throw error;
                }
                LOG.log(System.Logger.Level.ERROR,
                        "withdraw compensation threw: player={0}, currency={1}, amount={2},"
                                + " nonce={3}, reason={4}",
                        new Object[]{playerId, currencyId, amount, claim.nonce(),
                                authorizeFailure.getClass().getSimpleName()});
                return new MintDecision.Undecided(claim, failureDetail, authorizeFailure);
            }
            EconomyResult<Amount> refund;
            try {
                refund = economy.refundFailedMint(
                        FailedMintRefund.proven(playerId, currencyId, amount, claim.nonce()));
            } catch (Throwable refundFailure) {
                if (refundFailure instanceof Error error) {
                    throw error;
                }
                LOG.log(System.Logger.Level.ERROR,
                        "withdraw compensation threw: player={0}, currency={1}, amount={2},"
                                + " nonce={3}, reason={4}",
                        new Object[]{playerId, currencyId, amount, claim.nonce(),
                                refundFailure.getClass().getSimpleName()});
                return new MintDecision.Undecided(claim, failureDetail, refundFailure);
            }
            if (refund.isSuccess()) {
                return new MintDecision.Compensated(failureDetail);
            }
            LOG.log(System.Logger.Level.ERROR,
                    "withdraw compensation failed: player={0}, currency={1}, amount={2},"
                            + " nonce={3}, reason={4}",
                    new Object[]{playerId, currencyId, amount, claim.nonce(),
                            refund == null ? "no result" : refund.message()});
            return new MintDecision.Undecided(claim, failureDetail, null);
        }
        EconomyResult<Amount> refund;
        try {
            refund = api.deposit(playerId, currencyId, amount);
        } catch (Throwable refundFailure) {
            if (refundFailure instanceof Error error) {
                throw error;
            }
            LOG.log(System.Logger.Level.ERROR,
                    "withdraw compensation threw: player={0}, currency={1}, amount={2},"
                            + " nonce={3}, reason={4}",
                    new Object[]{playerId, currencyId, amount, claim.nonce(),
                            refundFailure.getClass().getSimpleName()});
            return new MintDecision.Undecided(claim, failureDetail, refundFailure);
        }
        if (refund.isSuccess()) {
            return new MintDecision.Compensated(failureDetail);
        }
        LOG.log(System.Logger.Level.ERROR,
                "withdraw compensation failed: player={0}, currency={1}, amount={2},"
                        + " nonce={3}, reason={4}",
                new Object[]{playerId, currencyId, amount, claim.nonce(),
                        refund == null ? "no result" : refund.message()});
        return new MintDecision.Undecided(claim, failureDetail, null);
    }

    private void auditMintPending(MintDecision.Undecided undecided) {
        if (undecided.cause() == null) {
            LOG.log(System.Logger.Level.ERROR,
                    "uncertain banknote mint kept pending: player={0}, currency={1},"
                            + " amount={2}, nonce={3}, detail={4}",
                    new Object[]{undecided.claim().issuer(), undecided.claim().currency(),
                            undecided.claim().value(), undecided.claim().nonce(),
                            undecided.detail()});
        } else {
            LOG.log(System.Logger.Level.ERROR,
                    "uncertain banknote mint kept pending: player=" + undecided.claim().issuer()
                            + ", currency=" + undecided.claim().currency()
                            + ", amount=" + undecided.claim().value()
                            + ", nonce=" + undecided.claim().nonce(),
                    undecided.cause());
        }
    }

    /**
     * Hand the minted note over on the player's region thread and complete only
     * after the outcome is known. Any path that leaves the note undelivered
     * after the deduction refunds the amount and completes with a typed
     * outcome; the future never stays pending.
     *
     * <p>Settlement fence: the region callback and the bounded wait race for
     * one one-shot claim. Exactly one of them settles the delivery — the
     * callback hands over the note, or the watchdog refunds; the loser is a
     * no-op. A retired task that finally runs after the timeout can therefore
     * neither deliver the note nor trigger a second refund. A callback that
     * runs after the caller's generation went stale refunds the same way:
     * the note object never left memory, so a plain refund is safe and no
     * stale window can place an item.
     */
    private CompletableFuture<WithdrawOutcome> deliver(UUID playerId, MintedNote minted,
                                                       GenerationCheck stillValid, WithdrawOp op) {
        CompletableFuture<WithdrawOutcome> done = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        if (minted.issuanceNonce() != null) {
            // Durable handover mark before the region dispatch runs, on this
            // IO thread (never a region thread). A crash after this mark
            // refunds+voids atomically, so the player is never left charged
            // without recourse.
            com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomic =
                    atomicIssuanceStore;
            if (atomic == null) {
                String diagnosis = "banknote delivery store unbound after an atomic"
                        + " deduction; keeping the outcome pending for recovery";
                LOG.log(System.Logger.Level.ERROR, diagnosis + ": player=" + playerId
                        + ", nonce=" + minted.issuanceNonce());
                done.complete(WithdrawOutcome.pending(minted.claim(), PENDING_DELIVERY_UNCERTAIN,
                        diagnosis, null));
                return done;
            }
            try {
                atomic.markDeliveryStarted(minted.issuanceNonce());
            } catch (Throwable markFailure) {
                // The deduction stands with the note still in memory: void+refund
                // now instead of delivering a note the recovery would kill.
                LOG.log(System.Logger.Level.ERROR,
                        "banknote delivery mark failed before handover: player=" + playerId
                                + ", nonce=" + minted.issuanceNonce(),
                        markFailure);
                EconomyResult<Amount> refund;
                try {
                    refund = economy.refundIssuanceForRecovery(
                            minted.issuanceNonce(), atomic);
                } catch (Throwable refundFailure) {
                    LOG.log(System.Logger.Level.ERROR,
                            "banknote delivery compensation threw: player=" + playerId
                                    + ", nonce=" + minted.issuanceNonce(),
                            refundFailure);
                    done.complete(WithdrawOutcome.pending(minted.claim(),
                            PENDING_DELIVERY_UNCERTAIN,
                            "the delivery mark failed and the atomic void+refund threw;"
                                    + " nothing was refunded blindly",
                            refundFailure instanceof Error error ? error : null));
                    return done;
                }
                if (refund != null && refund.isSuccess()) {
                    done.complete(WithdrawOutcome.refunded(EconomyError.TRANSACTION_CANCELLED,
                            "banknote.mark-failed",
                            "the delivery mark failed; the amount was refunded", true));
                } else {
                    done.complete(WithdrawOutcome.pending(minted.claim(),
                            PENDING_DELIVERY_UNCERTAIN,
                            "the delivery mark failed and the atomic void+refund was refused"
                                    + " (" + (refund == null ? "no result" : refund.message()) + ");"
                                    + " nothing was refunded blindly",
                            null));
                }
                return done;
            }
        }
        boolean dispatched;
        try {
            dispatched = folia.runForPlayer(playerId, player -> {
                if (!settled.compareAndSet(false, true)) {
                    // The bounded wait already refunded and closed the reply;
                    // delivering the note now would leave the player compensated
                    // and paid at the same time.
                    return;
                }
                if (!isCurrent(stillValid)) {
                    // Reload or generation change while the handover was
                    // queued: the note never left memory, so refund instead of
                    // placing it under rules that no longer apply.
                    plainRefundOnIo(minted, EconomyError.TRANSACTION_CANCELLED,
                            REASON_STALE_GENERATION,
                            "stale generation; the note was never delivered", done);
                    return;
                }
                try {
                    if (player.getInventory().firstEmpty() == -1) {
                        // Filled between the pre-check and this dispatch: fail
                        // closed with compensation instead of forcing a paid note
                        // into a full inventory. Proven undelivered: the note
                        // object never left memory.
                        plainRefundOnIo(minted, EconomyError.INVENTORY_FULL,
                                REASON_INVENTORY_FULL, "inventory full", done);
                        return;
                    }
                    Map<Integer, ItemStack> leftover;
                    try {
                        leftover = player.getInventory().addItem(minted.note());
                    } catch (Throwable deliveryFailure) {
                        // Uncertain: the note may or may not have landed. Refund
                        // only together with voiding the nonce atomically.
                        uncertainRefundOnIo(minted, deliveryFailure, done);
                        return;
                    }
                    if (leftover != null && !leftover.isEmpty()) {
                        // The note is in the leftover: proven undelivered, the
                        // nonce was never exposed, so a plain refund is safe.
                        plainRefundOnIo(minted, EconomyError.INVENTORY_FULL,
                                REASON_INVENTORY_FULL, "inventory full", done);
                        return;
                    }
                    if (minted.issuanceNonce() != null && atomicIssuanceStore != null) {
                        // The note landed: mark it durable on the IO executor
                        // (storage never runs on this region thread), then
                        // complete. A crash before the mark refunds+voids
                        // atomically; a mark failure is logged loud and the
                        // delivered outcome still stands while recovery
                        // converges it safely.
                        UUID deliveredNonce = minted.issuanceNonce();
                        com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore
                                deliveredStore = atomicIssuanceStore;
                        Runnable markDelivered = () -> {
                            try {
                                deliveredStore.markDelivered(deliveredNonce);
                            } catch (Throwable markFailure) {
                                LOG.log(System.Logger.Level.ERROR,
                                        "banknote delivery mark failed after the note landed:"
                                                + " player=" + playerId + ", nonce=" + deliveredNonce
                                                + "; startup recovery converges it safely",
                                        markFailure);
                            }
                        };
                        try {
                            CompletableFuture.runAsync(markDelivered, io).whenComplete(
                                    (ignored, markFailure) -> {
                                        if (markFailure != null) {
                                            LOG.log(System.Logger.Level.ERROR,
                                                    "banknote delivery mark failed after the note"
                                                            + " landed: player=" + playerId
                                                            + ", nonce=" + deliveredNonce,
                                                    markFailure);
                                        }
                                        done.complete(WithdrawOutcome.delivered(minted.claim(),
                                                minted.note(), minted.amount(),
                                                minted.auditError()));
                                    });
                        } catch (Throwable submitFailure) {
                            LOG.log(System.Logger.Level.ERROR,
                                    "banknote delivery mark could not be scheduled after the note"
                                            + " landed: player=" + playerId
                                            + ", nonce=" + deliveredNonce
                                            + "; startup recovery converges it safely",
                                    submitFailure);
                            done.complete(WithdrawOutcome.delivered(minted.claim(), minted.note(),
                                    minted.amount(), minted.auditError()));
                        }
                        return;
                    }
                    done.complete(WithdrawOutcome.delivered(minted.claim(), minted.note(),
                            minted.amount(), minted.auditError()));
                } catch (Throwable t) {
                    // A throw anywhere in the region context leaves the note's
                    // placement unknown: void and refund atomically, never blindly.
                    uncertainRefundOnIo(minted, t, done);
                }
            });
        } catch (Throwable dispatchFailure) {
            plainRefundOnIo(minted, EconomyError.TRANSACTION_CANCELLED,
                    REASON_DISPATCH_REJECTED,
                    "player left before the banknote could be delivered", done);
            return done;
        }
        if (!dispatched) {
            plainRefundOnIo(minted, EconomyError.TRANSACTION_CANCELLED,
                    REASON_DISPATCH_REJECTED,
                    "player left before the banknote could be delivered", done);
            return done;
        }
        Runnable deliveryTimeout = () -> {
            // Accepted but never executed (player left, Folia retired the
            // task): refund the committed deduction so no charge is left
            // without a note. The note object only exists in memory and is
            // dropped, so the nonce was never exposed: a plain refund is
            // safe. If the region callback already settled, this loses.
            // Shutdown fires this early so the refund still runs while
            // storage is up.
            if (settled.compareAndSet(false, true)) {
                plainRefundOnIo(minted, EconomyError.TRANSACTION_CANCELLED,
                        REASON_DISPATCH_TIMEOUT,
                        "banknote delivery timed out before the region task ran", done);
            }
        };
        op.settler.set(deliveryTimeout);
        done.whenComplete((outcome, failure) -> op.settler.compareAndSet(deliveryTimeout, null));
        try {
            watchdogs.schedule(deliveryTimeout, dispatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Throwable scheduleFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "withdraw delivery watchdog could not be armed: " + scheduleFailure,
                    scheduleFailure);
        }
        if (op.isShutdownFired()) {
            // Armed after the shutdown sweep: fire now so the stranded
            // deduction is compensated while storage is still up.
            op.fireSettler();
        }
        return done;
    }

    /**
     * Compensate a committed deduction whose note was proven undelivered, then
     * complete with the typed outcome. The refund runs on the IO executor; its
     * own failure is logged with the nonce for manual reconciliation — it is
     * surfaced, never swallowed, and never retried.
     *
     * <p>Every caller proves the note never left memory (stale generation,
     * full inventory, rejected or timed-out dispatch), so when a
     * trusted-refund ledger is bound the refund travels the single-use
     * trusted path exempt from the freeze and read-only gate instead of
     * stranding a charged player. Without a bound ledger the legacy ordinary
     * deposit is used.
     */
    private void plainRefundOnIo(MintedNote minted, EconomyError error, String reason,
                                 String message,
                                 CompletableFuture<WithdrawOutcome> done) {
        CompletableFuture.supplyAsync(() -> {
            try {
                com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger = refundLedger;
                if (ledger != null) {
                    try {
                        ledger.authorize(minted.claim().nonce(), minted.playerId(),
                                minted.currencyId(), minted.amount(),
                                com.smile.aceeconomy.operations.internal.TrustedRefundLedger
                                        .RefundPurpose.FAILED_MINT);
                    } catch (Throwable authorizeFailure) {
                        LOG.log(System.Logger.Level.ERROR,
                                "withdraw compensation threw: player=" + minted.playerId()
                                        + ", currency=" + minted.currencyId()
                                        + ", amount=" + minted.amount()
                                        + ", nonce=" + minted.claim().nonce(),
                                authorizeFailure);
                        return null;
                    }
                    return economy.refundFailedMint(FailedMintRefund.proven(
                            minted.playerId(), minted.currencyId(), minted.amount(),
                            minted.claim().nonce()));
                }
                return api.deposit(minted.playerId(), minted.currencyId(), minted.amount());
            } catch (Throwable refundFailure) {
                LOG.log(System.Logger.Level.ERROR,
                        "withdraw compensation threw: player=" + minted.playerId()
                                + ", currency=" + minted.currencyId()
                                + ", amount=" + minted.amount()
                                + ", nonce=" + minted.claim().nonce(),
                        refundFailure);
                return null;
            }
        }, io).whenComplete((refund, failure) -> {
            boolean refundOk = failure == null && refund != null && refund.isSuccess();
            if (!refundOk) {
                LOG.log(System.Logger.Level.ERROR,
                        "withdraw compensation failed: player={0}, currency={1}, amount={2},"
                                + " nonce={3}, reason={4}",
                        new Object[]{minted.playerId(), minted.currencyId(), minted.amount(),
                                minted.claim().nonce(),
                                failure == null
                                        ? (refund == null ? "no result" : refund.message())
                                        : failure.getClass().getSimpleName()});
            }
            done.complete(WithdrawOutcome.refunded(error, reason, message, refundOk));
        });
    }

    /**
     * Compensate a committed deduction whose note placement is uncertain: void
     * the nonce and refund in the SAME storage transaction. When that atomic
     * step cannot be decided, the outcome stays pending with diagnostics —
     * never a blind refund that could sit next to a redeemable note.
     *
     * <p>When a trusted-refund ledger is bound, this method first authorizes
     * exactly this committed deduction (a real commit receipt only the
     * pipeline holds, never a caller-supplied flag) and the core voids and
     * refunds through its single-use trusted path exempt from the freeze and
     * read-only gate instead of stranding a charged player. Without a bound
     * ledger the legacy direct call is used, which stays gate-checked.</p>
     */
    private void uncertainRefundOnIo(MintedNote minted, Throwable deliveryFailure,
                                     CompletableFuture<WithdrawOutcome> done) {
        CompletableFuture.supplyAsync(() -> {
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger = refundLedger;
            if (ledger == null) {
                return economy.compensateUndeliveredNote(
                        minted.claim().nonce(), minted.playerId(), minted.currencyId(),
                        minted.amount(), redemptions, "withdraw-compensation");
            }
            try {
                ledger.authorize(minted.claim().nonce(), minted.playerId(),
                        minted.currencyId(), minted.amount(),
                        com.smile.aceeconomy.operations.internal.TrustedRefundLedger
                                .RefundPurpose.DELIVERY_UNCERTAIN);
            } catch (Throwable authorizeFailure) {
                if (authorizeFailure instanceof Error error) {
                    throw error;
                }
                throw new java.util.concurrent.CompletionException(authorizeFailure);
            }
            return economy.compensateUndeliveredNote(
                    com.smile.aceeconomy.operations.internal.UncertainDeliveryCompensation.proven(
                            minted.playerId(), minted.currencyId(), minted.amount(),
                            minted.claim().nonce()),
                    redemptions, "withdraw-compensation");
        }, io).whenComplete((refund, failure) -> {
            if (failure == null && refund != null && refund.isSuccess()) {
                done.complete(WithdrawOutcome.voidRefunded(minted.claim()));
                return;
            }
            Throwable cause = failure instanceof java.util.concurrent.CompletionException completed
                    && completed.getCause() != null ? completed.getCause() : failure;
            if (cause == null && refund != null) {
                // A decided non-success: replay, missing account, debt or an
                // uncommitted storage failure. No second attempt: the nonce
                // state is exactly what the store reports.
                LOG.log(System.Logger.Level.ERROR,
                        "uncertain banknote delivery kept pending: player={0}, currency={1},"
                                + " amount={2}, nonce={3}, error={4}, message={5}",
                        new Object[]{minted.playerId(), minted.currencyId(), minted.amount(),
                                minted.claim().nonce(), refund.error(), refund.message()});
                done.complete(WithdrawOutcome.pending(minted.claim(), PENDING_DELIVERY_UNCERTAIN,
                        "the handover threw (" + deliveryFailure.getClass().getSimpleName() + ")"
                                + " and the atomic void+refund was refused"
                                + " (" + refund.error() + "); nothing was refunded blindly",
                        null));
                return;
            }
            if (cause instanceof Error error) {
                done.completeExceptionally(error);
                return;
            }
            LOG.log(System.Logger.Level.ERROR,
                    "uncertain banknote delivery kept pending: player=" + minted.playerId()
                            + ", currency=" + minted.currencyId()
                            + ", amount=" + minted.amount()
                            + ", nonce=" + minted.claim().nonce(),
                    cause == null ? deliveryFailure : cause);
            done.complete(WithdrawOutcome.pending(minted.claim(), PENDING_DELIVERY_UNCERTAIN,
                    "the handover threw (" + deliveryFailure.getClass().getSimpleName() + ")"
                            + " and the atomic void+refund could not be decided;"
                            + " nothing was refunded blindly",
                    cause));
        });
    }

    // ---------------- redeem (deposit) ----------------

    public @NotNull CompletableFuture<RedeemOutcome> redeem(@NotNull UUID playerId,
            @NotNull ItemStack snapshot, @NotNull Hand hand, @NotNull RedeemCommit commit) {
        return redeem(playerId, snapshot, hand, commit, null);
    }

    public @NotNull CompletableFuture<RedeemOutcome> redeem(@NotNull UUID playerId,
                                                            @NotNull ItemStack snapshot,
                                                            @NotNull Hand hand,
                                                            @NotNull RedeemCommit commit, GenerationCheck stillValid) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(commit, "commit");
        if (!accepting.get()) {
            return CompletableFuture.completedFuture(RedeemOutcome.pending(
                    PENDING_SHUTDOWN, "shutting down; the item was kept", null));
        }
        if (!isCurrent(stillValid)) {
            return CompletableFuture.completedFuture(
                    RedeemOutcome.rejected("transaction.cancelled"));
        }
        RedeemOp op = new RedeemOp(playerId, snapshot);
        redeemOps.add(op);
        CompletableFuture<RedeemOutcome> result =
                 CompletableFuture.supplyAsync(() -> {
                             if (!isCurrent(stillValid)) {
                                 op.stage = "stale";
                                 return DepositResult.rejected("transaction.cancelled");
                             }
                             return commit.commit(playerId, snapshot);
                         }, io)
                         .thenCompose(committed -> {
                            if (committed == null || !committed.success()) {
                                String reason = committed == null
                                        ? "credit.failed" : committed.reason();
                                return CompletableFuture.completedFuture(
                                        RedeemOutcome.rejected(reason));
                            }
                            op.stage = "remove";
                            return removeOnRegion(playerId, snapshot, hand,
                                    committed.value(), committed.currencyId(), stillValid, op);
                        })
                        .handle((outcome, failure) -> {
                            if (failure == null) {
                                return outcome;
                            }
                            Throwable cause = failure instanceof java.util.concurrent.CompletionException
                                    completed && completed.getCause() != null
                                    ? completed.getCause() : failure;
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            if (cause instanceof IndeterminatePersistenceException indeterminate) {
                                auditPending(playerId, snapshot,
                                        "banknote credit indeterminate; item kept", indeterminate);
                                return RedeemOutcome.pending(PENDING_CREDIT_INDETERMINATE,
                                        "the credit may or may not have committed; the item was"
                                                + " kept and nothing was retried",
                                        indeterminate);
                            }
                            if (cause instanceof PersistenceException committed
                                    && committed.isCommitted()) {
                                // The credit is durable but the post-commit cleanup
                                // failed: the item must still be removed. Decode
                                // the snapshot for the outcome details.
                                return removeAfterCommittedFailure(playerId, snapshot, hand,
                                        committed, stillValid, op);
                            }
                            if (cause instanceof PersistenceException) {
                                return RedeemOutcome.rejected("credit.failed");
                            }
                            if (cause instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            throw new java.util.concurrent.CompletionException(cause);
                        });
        CompletableFuture<RedeemOutcome> tracked = track(result);
        tracked.whenComplete((outcome, failure) -> {
            redeemOps.remove(op);
            op.settler.set(null);
        });
        return tracked;
    }

    /**
     * Remove exactly one item from the redeemed hand after a committed credit,
     * on the player's region thread. Only the hand that still matches the
     * credited snapshot is touched; a swapped hand is left alone and a missing
     * hand stays a retained credit with an audit record.
     */
    private CompletableFuture<RedeemOutcome> removeOnRegion(UUID playerId, ItemStack snapshot,
            Hand hand, long value, String currencyId, GenerationCheck stillValid, RedeemOp op) {
        CompletableFuture<RedeemOutcome> done = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        boolean dispatched;
        try {
            dispatched = folia.runForPlayer(playerId, player -> {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                if (!isCurrent(stillValid)) {
                    // Reload or generation change while the removal was queued:
                    // the credit already committed, so the item stays kept with
                    // an audit record instead of being removed under new rules.
                    done.complete(retained(playerId, snapshot, value, currencyId,
                            "stale generation; credit kept, item left untouched for manual review"));
                    return;
                }
                done.complete(removeNow(player, snapshot, hand, value, currencyId));
            });
        } catch (Throwable dispatchFailure) {
            return CompletableFuture.completedFuture(
                    retained(playerId, snapshot, value, currencyId,
                            "removal dispatch rejected: " + dispatchFailure));
        }
        if (!dispatched) {
            return CompletableFuture.completedFuture(
                    retained(playerId, snapshot, value, currencyId,
                            "player left before the redeemed note could be removed"));
        }
        Runnable removalTimeout = () -> {
            if (settled.compareAndSet(false, true)) {
                done.complete(retained(playerId, snapshot, value, currencyId,
                        "removal task accepted but never ran before the bounded wait"));
            }
        };
        op.settler.set(removalTimeout);
        done.whenComplete((outcome, failure) -> op.settler.compareAndSet(removalTimeout, null));
        try {
            watchdogs.schedule(removalTimeout, dispatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Throwable scheduleFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "redeem removal watchdog could not be armed: " + scheduleFailure,
                    scheduleFailure);
        }
        if (op.isShutdownFired()) {
            // Armed after the shutdown sweep: fire now so the removal settles
            // while the scheduler is still up.
            op.fireSettler();
        }
        return done;
    }

    private RedeemOutcome removeAfterCommittedFailure(UUID playerId, ItemStack snapshot, Hand hand,
            PersistenceException committed, GenerationCheck stillValid, RedeemOp op) {
        Optional<BanknoteClaim> decoded = safeDecode(snapshot);
        if (decoded.isEmpty()) {
            auditPending(playerId, snapshot,
                    "banknote credit committed but the note cannot be decoded for removal",
                    committed);
            return RedeemOutcome.creditRetained(0L, "unknown",
                    "credit committed; removal undecided, item kept for manual review");
        }
        BanknoteClaim claim = decoded.get();
        // The credit is durable: removal still runs on the region thread, and a
        // rejected dispatch or timeout degrades to retained, never to a retry.
        try {
            return removeOnRegion(playerId, snapshot, hand, claim.value(), claim.currency(),
                    stillValid, op).join();
        } catch (Throwable removalFailure) {
            return retained(playerId, snapshot, claim.value(), claim.currency(),
                    "credit committed; removal failed: " + removalFailure);
        }
    }

    private RedeemOutcome removeNow(Player player, ItemStack snapshot, Hand hand,
                                    long value, String currencyId) {
        ItemStack current;
        try {
            current = hand == Hand.MAIN_HAND
                    ? player.getInventory().getItemInMainHand()
                    : player.getInventory().getItemInOffHand();
        } catch (Throwable readFailure) {
            return retained(player, snapshot, value, currencyId,
                    "hand read failed: " + readFailure);
        }
        if (current == null) {
            return retained(player, snapshot, value, currencyId,
                    "hand no longer holds the redeemed note");
        }
        try {
            if (current.getType() == null || current.getType().isAir() || current.getAmount() <= 0) {
                return retained(player, snapshot, value, currencyId,
                        "hand no longer holds the redeemed note");
            }
        } catch (Throwable readFailure) {
            return retained(player, snapshot, value, currencyId,
                    "hand read failed: " + readFailure);
        }
        if (!matches(current, snapshot)) {
            return RedeemOutcome.itemMismatch();
        }
        try {
            int amount = current.getAmount();
            if (amount <= 1) {
                setHand(player, hand, null);
            } else {
                ItemStack updated = current.clone();
                if (updated == null || updated == current) {
                    throw new IllegalStateException(
                            "banknote clone unavailable; item left untouched for manual review");
                }
                updated.setAmount(amount - 1);
                setHand(player, hand, updated);
            }
        } catch (Throwable writeFailure) {
            return retained(player, snapshot, value, currencyId,
                    "item could not be removed after credit: " + writeFailure);
        }
        return RedeemOutcome.credited(value, currencyId);
    }

    private RedeemOutcome retained(UUID playerId, ItemStack snapshot, long value, String currencyId,
                                   String detail) {
        auditRetained(playerId, snapshot, value, currencyId, detail, null);
        return RedeemOutcome.creditRetained(value, currencyId, detail);
    }

    private RedeemOutcome retained(Player player, ItemStack snapshot, long value, String currencyId,
                                   String detail) {
        auditRetained(player.getUniqueId(), snapshot, value, currencyId, detail, null);
        return RedeemOutcome.creditRetained(value, currencyId, detail);
    }

    private void auditRetained(UUID playerId, ItemStack snapshot, long value, String currencyId,
                               String detail, Throwable cause) {
        Optional<BanknoteClaim> decoded = safeDecode(snapshot);
        String nonce = decoded.map(claim -> claim.nonce().toString()).orElse("unknown");
        String countedCurrency = decoded.map(BanknoteClaim::currency).orElse(currencyId);
        long countedValue = decoded.map(BanknoteClaim::value).orElse(value);
        if (cause == null) {
            LOG.log(System.Logger.Level.ERROR,
                    "AceEconomy banknote credit retained: nonce={0} player={1} value={2}"
                            + " currency={3} — {4}",
                    new Object[]{nonce, playerId, countedValue, countedCurrency, detail});
        } else {
            LOG.log(System.Logger.Level.ERROR,
                    "AceEconomy banknote credit retained: nonce=" + nonce
                            + " player=" + playerId
                            + " value=" + countedValue
                            + " currency=" + countedCurrency
                            + " — " + detail,
                    cause);
        }
    }

    private void auditPending(UUID playerId, ItemStack snapshot, String detail, Throwable cause) {
        Optional<BanknoteClaim> decoded = safeDecode(snapshot);
        String nonce = decoded.map(claim -> claim.nonce().toString()).orElse("unknown");
        LOG.log(System.Logger.Level.ERROR,
                "AceEconomy banknote operation pending: nonce=" + nonce
                        + " player=" + playerId + " — " + detail,
                cause);
    }

    // ---------------- small helpers ----------------

    private static BanknoteClaim claim(UUID playerId, Currency currency, long value) {
        return new BanknoteClaim(new com.smile.acelib.item.ItemIdentity(BanknoteClaim.V2_NAMESPACE,
                BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, value, playerId, UUID.randomUUID(), currency.id());
    }

    private Optional<BanknoteClaim> safeDecode(ItemStack snapshot) {
        try {
            Optional<BanknoteClaim> decoded = banknotes.decode(snapshot);
            return decoded == null ? Optional.empty() : decoded;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static boolean matches(ItemStack current, ItemStack snapshot) {
        if (current == snapshot) {
            return true;
        }
        try {
            return current.isSimilar(snapshot);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setHand(Player player, Hand hand, ItemStack stack) {
        if (hand == Hand.MAIN_HAND) {
            player.getInventory().setItemInMainHand(stack);
        } else {
            player.getInventory().setItemInOffHand(stack);
        }
    }
}
