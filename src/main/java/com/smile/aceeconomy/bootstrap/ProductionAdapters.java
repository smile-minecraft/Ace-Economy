package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.commands.v2.CommandModels;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.RollbackCommandService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.ValidationResult;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;
import com.smile.aceeconomy.operations.HistoryService;
import com.smile.aceeconomy.operations.LeaderboardPage;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.WithdrawResult;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import com.smile.aceeconomy.ports.operations.LeaderboardSource;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class ProductionAdapters {
    private ProductionAdapters() { }

    static final class Economy implements EconomyCommandService {
        private final EconomyApi api; private final CurrencyDisplayHolder display; private final Executor executor;
        Economy(EconomyApi api, CurrencyDisplayHolder display, Executor executor) {
            this.api = api;
            this.display = java.util.Objects.requireNonNull(display, "display");
            this.executor = executor;
        }
        /**
         * Hot-swap display-only currency metadata after a validated reload. The guard
         * rejects structural changes so command output can never diverge from the
         * transactional registry. All display surfaces share one holder, so this
         * publish is observed atomically together with Vault and placeholder reads.
         */
        void replaceCurrencyDisplay(CurrencyRegistry candidate) {
            com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan
                    .requireDisplayOnlyChange(display.get(), candidate);
            display.publish(candidate);
        }
        public CompletableFuture<EconomyResult<Amount>> getBalance(UUID id, String c) {
            return CompletableFuture.supplyAsync(() -> api.getBalance(id, c), executor);
        }
        public CompletableFuture<EconomyResult<Amount>> withdraw(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(() -> api.withdraw(id, c, a), executor);
        }
        public CompletableFuture<EconomyResult<com.smile.aceeconomy.application.TransferResult>> transfer(
                UUID from, UUID to, String c, Amount a) {
            return CompletableFuture.supplyAsync(() -> api.transfer(from, to, c, a), executor);
        }
        public CompletableFuture<EconomyResult<AccountSnapshot>> loadAccount(UUID id) {
            return CompletableFuture.supplyAsync(() -> api.loadAccount(id), executor);
        }
        public Optional<CommandModels.CurrencyInfo> resolveCurrency(String id) {
            CurrencyRegistry currencies = display.get();
            if (!currencies.contains(id)) return Optional.empty();
            Currency c = currencies.get(id);
            return Optional.of(new CommandModels.CurrencyInfo(c.id(), c.displayName(), c.symbol(), c.scale(), c.isDefault()));
        }
        public List<String> knownCurrencyIds() { return display.get().all().stream().map(Currency::id).toList(); }
        public String defaultCurrencyId() { return display.get().defaultCurrencyId(); }
    }

    static final class Admin implements AdminCommandService {
        private final EconomyService service; private final Executor executor;
        private final Supplier<com.smile.aceeconomy.infrastructure.acelib.ReloadResult> reload;
        Admin(EconomyService service, Executor executor,
              Supplier<com.smile.aceeconomy.infrastructure.acelib.ReloadResult> reload) {
            this.service = java.util.Objects.requireNonNull(service, "service");
            this.executor = executor; this.reload = reload;
        }
        public CompletableFuture<EconomyResult<Amount>> give(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(
                    () -> service.deposit(id, c, a, com.smile.aceeconomy.domain.AdminAudit.unrecorded()),
                    executor);
        }
        public CompletableFuture<EconomyResult<Amount>> take(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(
                    () -> service.withdraw(id, c, a, com.smile.aceeconomy.domain.AdminAudit.unrecorded()),
                    executor);
        }
        public CompletableFuture<EconomyResult<Amount>> setBalance(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(
                    () -> service.setBalance(id, c, a, com.smile.aceeconomy.domain.AdminAudit.unrecorded()),
                    executor);
        }
        @Override
        public CompletableFuture<EconomyResult<Amount>> give(UUID id, String c, Amount a,
                                                             com.smile.aceeconomy.domain.AdminAudit audit) {
            return CompletableFuture.supplyAsync(() -> service.deposit(id, c, a, audit), executor);
        }
        @Override
        public CompletableFuture<EconomyResult<Amount>> take(UUID id, String c, Amount a,
                                                             com.smile.aceeconomy.domain.AdminAudit audit) {
            return CompletableFuture.supplyAsync(() -> service.withdraw(id, c, a, audit), executor);
        }
        @Override
        public CompletableFuture<EconomyResult<Amount>> setBalance(UUID id, String c, Amount a,
                                                                   com.smile.aceeconomy.domain.AdminAudit audit) {
            return CompletableFuture.supplyAsync(() -> service.setBalance(id, c, a, audit), executor);
        }
        public CompletableFuture<EconomyResult<Void>> reload() {
            return CompletableFuture.supplyAsync(() -> {
                com.smile.aceeconomy.infrastructure.acelib.ReloadResult result = reload.get();
                if (result.success()) {
                    return EconomyResult.success(null);
                }
                return EconomyResult.failure(EconomyError.INVALID_AMOUNT, result.detail());
            }, executor);
        }
    }

    static final class Players implements PlayerLookupService {
        private final Executor executor;
        Players(Executor executor) { this.executor = executor; }
        public CompletableFuture<Optional<CommandModels.PlayerIdentity>> resolve(String name) {
            return CompletableFuture.supplyAsync(() -> {
                Player online = Bukkit.getPlayerExact(name);
                OfflinePlayer p = online == null ? Bukkit.getOfflinePlayer(name) : online;
                if (online == null && !p.hasPlayedBefore()) return Optional.empty();
                return Optional.of(new CommandModels.PlayerIdentity(p.getUniqueId(), p.getName(), online != null));
            }, executor);
        }
        public List<String> onlinePlayerNames() { return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); }
    }

    static final class Withdrawals implements WithdrawCommandService {
        private final com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow flow;

        Withdrawals(com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow flow) {
            this.flow = java.util.Objects.requireNonNull(flow, "flow");
        }

        /**
         * Withdraw a banknote through the shared pipeline: region probe, IO
         * deduction and mint, region handover with bounded waits. The probe
         * refuses a full inventory before any deduction (GUI parity); an
         * undeliverable note is compensated (plain refund when proven
         * undelivered, atomic nonce-void+refund when uncertain, diagnosable
         * pending otherwise), so no charge is ever left without a note.
         *
         * <p>Dispatch acceptance is not execution: an accepted-but-never-run
         * region task completes the reply through the flow's bounded wait
         * instead of leaving it pending forever. An indeterminate deduction
         * completes exceptionally with its cause so the command layer reports
         * committed-unknown and never retries blindly.
         */
        public CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> withdraw(UUID id, String c, Amount a) {
            return flow.withdraw(id, c, a).thenCompose(outcome -> mapOutcome(id, outcome));
        }

        private static CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> mapOutcome(
                UUID id, com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow.WithdrawOutcome outcome) {
            if (outcome.isDelivered()) {
                com.smile.aceeconomy.ports.BanknoteClaim claim = outcome.claim();
                Amount amount = outcome.amount();
                CommandModels.WithdrawReceipt receipt = new CommandModels.WithdrawReceipt(
                        claim.nonce(), id.toString(), claim.currency(), amount.value());
                Throwable auditError = outcome.auditError();
                if (auditError != null) {
                    return CompletableFuture.completedFuture(
                            EconomyResult.success(receipt, auditError));
                }
                return CompletableFuture.completedFuture(EconomyResult.success(receipt));
            }
            if (outcome.isRefusedFull()) {
                return CompletableFuture.completedFuture(EconomyResult.failure(
                        EconomyError.INVENTORY_FULL, "inventory full"));
            }
            if (outcome.isRefused() || outcome.isRefunded()) {
                return CompletableFuture.completedFuture(EconomyResult.failure(
                        outcome.error(), outcome.message()));
            }
            if (outcome.isVoidRefunded()) {
                return CompletableFuture.completedFuture(EconomyResult.failure(
                        EconomyError.TRANSACTION_CANCELLED,
                        "banknote delivery uncertain; the note was voided and the amount refunded"));
            }
            // Pending: an indeterminate deduction must surface through the
            // established committed-unknown path (never a blind retry); other
            // pending outcomes fail closed with their diagnosis.
            Throwable cause = outcome.pendingCause();
            if (cause instanceof com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException
                    || cause instanceof com.smile.aceeconomy.ports.persistence.PersistenceException committed
                    && committed.isCommitted()) {
                CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(cause);
                return failed;
            }
            if (cause instanceof RuntimeException runtime) {
                CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(runtime);
                return failed;
            }
            if (cause instanceof Error error) {
                CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(error);
                return failed;
            }
            return CompletableFuture.completedFuture(EconomyResult.failure(
                    EconomyError.TRANSACTION_CANCELLED,
                    outcome.message() == null ? "withdraw pending" : outcome.message()));
        }
    }

    static final class Leaderboards implements LeaderboardQueryService {
        private final LeaderboardService service; private final int pageSize; private final Executor executor;
        Leaderboards(LeaderboardService service, int pageSize, Executor executor) {
            this.service = service; this.pageSize = pageSize; this.executor = executor;
        }
        public CompletableFuture<List<CommandModels.LeaderboardEntry>> top(String c, int page, int size) {
            return CompletableFuture.supplyAsync(() -> {
                LeaderboardPage result = service.query(c, page, size);
                return result.entries().stream().map(e -> new CommandModels.LeaderboardEntry(e.rank(), e.ownerName(), e.balance().value(), c)).toList();
            }, executor);
        }
        public int pageSize() { return pageSize; }
    }

    static final class History implements HistoryQueryService {
        private final HistoryService service; private final Executor executor;
        History(HistoryService service, Executor executor) {
            this.service = service; this.executor = executor;
        }
        public CompletableFuture<AuditPage> query(AuditQuery query) {
            return CompletableFuture.supplyAsync(() -> service.query(query), executor);
        }
        @Override
        public Executor callbackExecutor() {
            return executor;
        }
    }

    static final class Rollback implements RollbackCommandService {
        private final RollbackService service; private final Executor executor;
        Rollback(RollbackService service, Executor executor) {
            this.service = service; this.executor = executor;
        }
        public CompletableFuture<RollbackResult> rollback(UUID transactionId) {
            return CompletableFuture.supplyAsync(() -> service.rollback(transactionId), executor);
        }
        public CompletableFuture<RollbackResult> rollback(UUID transactionId,
                                                          com.smile.aceeconomy.domain.AdminAudit audit) {
            return CompletableFuture.supplyAsync(() -> service.rollback(transactionId, audit), executor);
        }
    }

    /**
     * Backup/restore command boundary: moves the blocking, safety-gated service calls onto
     * the IO executor. The controlled backup directory, preflight, online-player gate,
     * safety snapshot and the publish protocol all stay inside {@code BackupRestoreService}:
     * the snapshot target is created handle-relative with {@code CREATE_NEW}, written
     * completely and forced, then a handle-relative {@code .ready} marker is created with
     * {@code CREATE_NEW} as an application-level logical commit — not an operating-system
     * atomic rename. This adapter adds no policy of its own.
     */
    static final class BackupRestore implements com.smile.aceeconomy.commands.v2.ports.BackupCommandService {
        private final com.smile.aceeconomy.operations.BackupRestoreService service;
        private final Executor executor;
        BackupRestore(com.smile.aceeconomy.operations.BackupRestoreService service, Executor executor) {
            this.service = service; this.executor = executor;
        }
        public CompletableFuture<com.smile.aceeconomy.operations.BackupResult> createBackup(String label) {
            return CompletableFuture.supplyAsync(() -> service.createBackup(label), executor);
        }
        public CompletableFuture<com.smile.aceeconomy.operations.RestoreResult> restore(String backupId) {
            return CompletableFuture.supplyAsync(() -> service.restore(backupId), executor);
        }
        public CompletableFuture<com.smile.aceeconomy.operations.RestoreResult> restore(
                String backupId, com.smile.aceeconomy.domain.AdminAudit audit) {
            return CompletableFuture.supplyAsync(() -> service.restore(backupId, audit), executor);
        }
    }

    /**
     * Import command boundary: moves the blocking gate/parse/backup/apply
     * sequence onto the IO executor. Preview versus apply is decided by the
     * command layer (exact {@code apply confirm} pair); this adapter adds no
     * policy of its own and never turns a preview into writes.
     */
    static final class Import implements com.smile.aceeconomy.commands.v2.ports.ImportCommandService {
        private final com.smile.aceeconomy.operations.ImportRunner runner;
        private final Executor executor;
        Import(com.smile.aceeconomy.operations.ImportRunner runner, Executor executor) {
            this.runner = runner; this.executor = executor;
        }
        public CompletableFuture<com.smile.aceeconomy.operations.ImportOutcome> preview(
                com.smile.aceeconomy.ports.operations.ImportSource source, String path, String currencyId) {
            return CompletableFuture.supplyAsync(() -> runner.preview(source, path, currencyId), executor);
        }
        public CompletableFuture<com.smile.aceeconomy.operations.ImportOutcome> apply(
                com.smile.aceeconomy.ports.operations.ImportSource source, String path, String currencyId) {
            return CompletableFuture.supplyAsync(() -> runner.apply(source, path, currencyId), executor);
        }
        public CompletableFuture<com.smile.aceeconomy.operations.ImportOutcome> apply(
                com.smile.aceeconomy.ports.operations.ImportSource source, String path, String currencyId,
                com.smile.aceeconomy.domain.AdminAudit audit) {
            return CompletableFuture.supplyAsync(
                    () -> runner.apply(source, path, currencyId, audit), executor);
        }
    }

    /**
     * Bank command boundary: opens the GUI from the layout that is current
     * at open time through the plugin-owned scope. The layout is read through
     * a supplier (the successfully reloaded reference in production), so a
     * reload that swaps the layout changes what newly opened interfaces show;
     * already-open views were rebuilt in place by the reload itself, so only
     * new opens matter here.
     *
     * <p>Opens serialize against the reload swap inside the session, so an
     * open can never leak a stale view past a reload and no retry exists.
     * The declarative view denies every non-button slot upstream, and every
     * button is fully built before the scope call: a button that cannot be
     * built refuses the open and, where the notice can be delivered, tells the
     * player. Placement after the handoff is not covered by that refusal: if
     * upstream fails to clone or set one slot, only that slot is skipped and
     * the interface still opens with that button missing.
     */
    static final class Bank implements BankCommandService {
        private final V2BankGuiSession gui;
        private final Supplier<com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout> layouts;
        private final Executor executor;
        Bank(V2BankGuiSession gui,
             Supplier<com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout> layouts,
             Executor executor) {
            this.gui = gui;
            this.layouts = java.util.Objects.requireNonNull(layouts, "layouts");
            this.executor = executor;
        }
        Bank(V2BankGuiSession gui,
             com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout,
             Executor executor) {
            this(gui, () -> layout, executor);
        }
        public void open(UUID id, String name) { executor.execute(() -> {
            openOnce(id);
        }); }

        private void openOnce(UUID id) {
            com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout = layouts.get();
            if (layout == null || !layout.enabled()) {
                return;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                gui.open(player);
            }
        }
    }

    static final class BankUseCase implements BankGuiUseCase {
        private static final System.Logger LOGGER = System.getLogger(ProductionAdapters.class.getName());
        private final EconomyApi api; private final EconomyService economy; private final CurrencyRegistry currencies;
        private final BanknoteFactory banknotes; private final BanknoteValidator validator;
        private final AtomicRedemptionStore redemptions;
        private final com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow flow;
        private volatile com.smile.aceeconomy.operations.internal.TrustedRefundLedger refundLedger;
        /**
         * Phase-1 seam for the durable atomic issuance core. Null means the
         * GUI entry runs the legacy inline path (existing tests); production
         * Bootstrap binds the SAME backend-backed atomic instance as the core
         * service and the banknote flow in phase 2, so the GUI entry deducts
         * through the same durable core as the command entry with no UI
         * change. Binding alone changes no behaviour yet.
         */
        private volatile com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomicIssuanceStore;
        /**
         * Recovery barrier for new managed redemption. Null means the gate stays
         * open (existing tests and unconnected flows); production binds the
         * startup runner closed and it opens only when the startup recovery
         * run finishes. Legacy v2 notes with no issuance row stay allowed.
         */
        private volatile com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner startupRecovery;
        BankUseCase(EconomyApi api, EconomyService economy, CurrencyRegistry currencies,
                    BanknoteFactory banknotes, BanknoteValidator validator,
                    AtomicRedemptionStore redemptions,
                    com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow flow) {
            this.api = api; this.economy = economy; this.currencies = currencies; this.banknotes = banknotes;
            this.validator = validator; this.redemptions = redemptions;
            this.flow = flow;
        }
        /**
         * Backward-compatible constructor for offline tests that bypass the economy lock/validation.
         * Without a flow the withdraw core runs inline (legacy path); production always wires the
         * shared flow so the GUI entry deducts through the same steps as the command entry.
         */
        BankUseCase(EconomyApi api, CurrencyRegistry currencies, BanknoteFactory banknotes,
                    BanknoteValidator validator, AtomicRedemptionStore redemptions) {
            this(api, null, currencies, banknotes, validator, redemptions, null);
        }

        BankUseCase(EconomyApi api, EconomyService economy, CurrencyRegistry currencies,
                    BanknoteFactory banknotes, BanknoteValidator validator,
                    AtomicRedemptionStore redemptions) {
            this(api, economy, currencies, banknotes, validator, redemptions, null);
        }
        /**
         * Bind the single-use trusted-refund ledger shared with the core
         * service and the banknote flow. A GUI mint failure with a bound
         * ledger refunds through the trusted path exempt from the freeze
         * gate; without it the legacy ordinary deposit is used and a frozen
         * refund stays refused instead of stranding the charge silently.
         */
        void bindRefundLedger(
                com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger) {
            this.refundLedger = ledger;
        }

        /**
         * Bind the durable atomic issuance store shared with the core service
         * and the banknote flow. Phase-1 seam only: binding alone changes no
         * behaviour; phase 2 routes the GUI deduct through the same atomic
         * core as the command entry.
         */
        void bindAtomicIssuanceStore(
                com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore store) {
            this.atomicIssuanceStore = store;
        }

        /** Skeleton accessor for the wired atomic store; null when unconnected. */
        com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomicIssuanceStore() {
            return atomicIssuanceStore;
        }

        /**
         * Bind the startup recovery barrier. While the barrier is closed, managed
         * redemptions (notes with a durable issuance row) wait instead of racing
         * recovery; legacy v2 notes with no row stay allowed.
         */
        void bindStartupRecovery(
                com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner runner) {
            this.startupRecovery = runner;
        }

        /** Skeleton accessor for the wired recovery barrier; null when unconnected. */
        com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner startupRecovery() {
            return startupRecovery;
        }
        public WithdrawResult withdraw(UUID id, long value) {
            return withdraw(id, value, null);
        }
        @Override
        public WithdrawResult withdraw(UUID id, long value, String currencyId) {
            Currency c = currencyId == null || currencyId.isBlank()
                    ? currencies.get(currencies.defaultCurrencyId())
                    : currencies.get(currencyId);
            if (flow != null) {
                // Shared commit core: the GUI entry deducts, mints and
                // compensates through the same steps as the command entry.
                // Callers must invoke this off the region thread; only the
                // async flow entry points add the region handover around it.
                return flow.withdrawCommit(id, c, value);
            }
            Amount amount = Amount.of(value, c.scale());
            EconomyResult<Amount> result = api.withdraw(id, c.id(), amount);
            if (result.isFailure()) return WithdrawResult.rejected(result.message());
            // Keep the requested amount: a successful withdrawal resolves to the AFTER
            // balance (before − amount), which must never be mistaken for the refund.
            BanknoteClaim claim = claim(id, c, value);
            ItemStack note;
            try {
                note = banknotes.mint(claim).orElse(null);
            } catch (RuntimeException mintThrew) {
                // A throwing mint is the same money-safety hole as an empty one: the
                // deduction has already committed, so the account must be compensated
                // exactly once and the reply must stay a typed rejection. Errors are
                // deliberately not caught — a broken VM must surface, never masquerade
                // as a failed issuance.
                LOGGER.log(System.Logger.Level.WARNING,
                        "banknote mint threw: player={0}, currency={1}, amount={2}, type={3}",
                        new Object[] { id, c.id(), amount, mintThrew.getClass().getName() });
                return refundAndReject(id, c.id(), amount, claim.nonce(),
                        "banknote mint failed: " + mintThrew.getClass().getSimpleName());
            }
            if (note == null) {
                // The deduction committed but the item could not be materialised: compensate
                // exactly once with the amount that actually left the account, in the same
                // currency — the same money-safety rule the command path applies. A failed
                // refund is logged at ERROR and stays observable; it is never retried here,
                // so a refund outage can neither silently lose the money nor double-refund.
                return refundAndReject(id, c.id(), amount, claim.nonce(), "banknote could not be created");
            }
            return WithdrawResult.success(note);
        }

        /**
         * Compensate a committed deduction after a mint failure, then close the withdraw
         * with a typed rejection. The refund carries the withdrawn amount (never the
         * after-balance a withdrawal result resolves to), runs at most once and is never
         * retried; a refund outage is logged at ERROR with full context so it stays
         * observable for manual reconciliation instead of silently losing the money.
         *
         * <p>When the trusted-refund ledger is bound, the proven-undelivered
         * note refunds through the single-use trusted path exempt from the
         * freeze and read-only gate (the note never left memory). Without a
         * bound ledger the legacy ordinary deposit is used.
         */
        private WithdrawResult refundAndReject(UUID id, String currencyId, Amount withdrawn,
                                               UUID nonce, String reason) {
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger = refundLedger;
            if (ledger != null && economy != null && nonce != null) {
                try {
                    ledger.authorize(nonce, id, currencyId, withdrawn,
                            com.smile.aceeconomy.operations.internal.TrustedRefundLedger
                                    .RefundPurpose.FAILED_MINT);
                } catch (Throwable authorizeFailure) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            "gui withdraw compensation failed: player={0}, currency={1}, amount={2}, reason={3}",
                            new Object[] {id, currencyId, withdrawn,
                                    authorizeFailure.getClass().getSimpleName()});
                    return WithdrawResult.rejected(reason);
                }
                EconomyResult<Amount> trusted;
                try {
                    trusted = economy.refundFailedMint(
                            com.smile.aceeconomy.operations.internal.FailedMintRefund.proven(
                                    id, currencyId, withdrawn, nonce));
                } catch (Throwable refundFailure) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            "gui withdraw compensation failed: player={0}, currency={1}, amount={2}, reason={3}",
                            new Object[] {id, currencyId, withdrawn,
                                    refundFailure.getClass().getSimpleName()});
                    return WithdrawResult.rejected(reason);
                }
                if (trusted.isFailure()) {
                    LOGGER.log(System.Logger.Level.ERROR,
                            "gui withdraw compensation failed: player={0}, currency={1}, amount={2}, reason={3}",
                            new Object[] {id, currencyId, withdrawn, trusted.message()});
                }
                return WithdrawResult.rejected(reason);
            }
            EconomyResult<Amount> refund = api.deposit(id, currencyId, withdrawn);
            if (refund.isFailure()) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "gui withdraw compensation failed: player={0}, currency={1}, amount={2}, reason={3}",
                        new Object[] { id, currencyId, withdrawn, refund.message() });
            }
            return WithdrawResult.rejected(reason);
        }

        /**
         * Redeem the held item: decode → structural validation → currency check → durable
         * atomic redemption via the application service (lock, pre-commit event, debt policy
         * and all-or-none balance/audit/nonce), then observe the managed issuance as redeemed
         * when the note carries one. Every rejection leaves the physical item untouched; only
         * a committed redemption lets the caller remove it. Storage failures are mapped to the
         * stable {@code credit.failed} reason instead of throwing into the scheduler dispatch,
         * where they would surface as an indistinguishable accepted click. The redeem observation
         * never overturns a committed credit; an observation failure is logged loudly and the
         * record staying delivered keeps refusing an empty-candidate rollback.
         */
        public DepositResult deposit(UUID id, ItemStack heldItem) {
            Optional<BanknoteClaim> decoded = banknotes.decode(heldItem);
            if (decoded.isEmpty()) {
                return DepositResult.rejected("banknote.invalid");
            }
            ValidationResult structural = validator.validateStructure(decoded.get());
            if (structural.rejected()) {
                return DepositResult.rejected(structural.reasonCode());
            }
            BanknoteClaim claim = structural.claim();
            if (!currencies.contains(claim.currency())) {
                return DepositResult.rejected("currency.unknown");
            }
            Currency c = currencies.get(claim.currency());
            Amount amount;
            try {
                amount = Amount.of(claim.value(), c.scale());
            } catch (IllegalArgumentException e) {
                return DepositResult.rejected("value.nonpositive");
            }
            com.smile.aceeconomy.application.BanknoteIssuanceStartupRunner barrier = startupRecovery;
            if (barrier != null && !barrier.isRecoveryComplete()) {
                com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomic =
                        atomicIssuanceStore;
                if (atomic != null) {
                    boolean managed;
                    try {
                        managed = atomic.findByNonce(claim.nonce()).isPresent();
                    } catch (Exception lookupFailure) {
                        return DepositResult.rejected("credit.failed");
                    }
                    if (managed) {
                        return DepositResult.rejected(
                                "banknote issuance is paused while startup recovery is incomplete;"
                                        + " please try again later");
                    }
                }
            }
            // Production path preserves the full EconomyService contract (lock, pre-commit,
            // debt, audit semantics) and delegates durability to the prepared atomic store.
            if (economy != null) {
                EconomyResult<Amount> r = economy.redeemBanknote(claim.nonce(), id, c.id(), amount, redemptions);
                if (r.isSuccess()) {
                    observeIssuanceRedeemed(claim.nonce());
                    return DepositResult.success(claim.value(), c.id());
                }
                EconomyError err = r.error();
                if (err == EconomyError.REPLAY_DETECTED) {
                    return DepositResult.rejected("replay.detected");
                }
                if (err == EconomyError.ACCOUNT_NOT_FOUND) {
                    return DepositResult.rejected("credit.account-missing");
                }
                if (err == EconomyError.CURRENCY_NOT_FOUND) {
                    return DepositResult.rejected("currency.unknown");
                }
                if (err == EconomyError.TRANSACTION_CANCELLED) {
                    return DepositResult.rejected("transaction.cancelled");
                }
                if (err == EconomyError.INVALID_AMOUNT) {
                    return DepositResult.rejected("value.nonpositive");
                }
                if (err == EconomyError.ACCOUNT_FROZEN) {
                    return DepositResult.rejected("account.frozen");
                }
                if (err == EconomyError.GLOBAL_READ_ONLY) {
                    return DepositResult.rejected("server.readonly");
                }
                if (err == EconomyError.CONTROL_UNAVAILABLE) {
                    return DepositResult.rejected("control.unavailable");
                }
                return DepositResult.rejected("credit.failed");
            }
            // Legacy fallback for offline unit tests that inject only the raw store.
            try {
                RedemptionResult r = redemptions.redeem(claim.nonce(), id, c.id(), amount);
                if (r.isCommitted()) {
                    observeIssuanceRedeemed(claim.nonce());
                    return DepositResult.success(claim.value(), c.id());
                }
                if (r.isReplay()) {
                    return DepositResult.rejected("replay.detected");
                }
                return DepositResult.rejected("credit.account-missing");
            } catch (com.smile.aceeconomy.ports.persistence.PersistenceException e) {
                return DepositResult.rejected("credit.failed");
            }
        }

        /**
         * Observe a committed redemption on the managed issuance, when one exists.
         *
         * <p>A note without a managed record (issued before issuance tracking) redeems
         * exactly as before. When a record exists, the committed credit flips it to
         * redeemed so a later empty-candidate rollback is no longer blocked by it.
         * The observation never overturns the committed credit: a lookup or mark
         * failure is logged loudly, the nonce is already consumed so the note cannot
         * be reused, and a record staying delivered keeps refusing an empty-candidate
         * rollback (conservative).</p>
         */
        private void observeIssuanceRedeemed(java.util.UUID nonce) {
            com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore atomic =
                    atomicIssuanceStore;
            if (atomic == null) {
                return;
            }
            java.util.Optional<com.smile.aceeconomy.domain.BanknoteIssuance> existing;
            try {
                existing = atomic.findByNonce(nonce);
            } catch (Throwable lookupFailure) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "banknote redeem observation failed for nonce {0}: {1}; credit stays"
                                + " committed, the nonce is already consumed so the note cannot be"
                                + " reused, and a delivered record keeps refusing an empty-candidate"
                                + " rollback",
                        new Object[]{nonce, lookupFailure.getClass().getSimpleName()});
                return;
            }
            if (existing.isEmpty()) {
                return;
            }
            if (existing.get().status()
                    == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.REDEEMED) {
                return;
            }
            try {
                atomic.markRedeemed(nonce);
            } catch (Throwable markFailure) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "banknote redeem observation failed for nonce {0}: {1}; credit stays"
                                + " committed, the nonce is already consumed so the note cannot be"
                                + " reused, and a delivered record keeps refusing an empty-candidate"
                                + " rollback",
                        new Object[]{nonce, markFailure.getClass().getSimpleName()});
            }
        }
    }

    static final class RepositoryLeaderboardSource implements LeaderboardSource {
        private final AccountRepository accounts;
        RepositoryLeaderboardSource(AccountRepository accounts) { this.accounts = accounts; }
        public List<LeaderboardRow> rows(String c) {
            String cid = com.smile.aceeconomy.domain.Currency.normalizeId(c);
            if (accounts instanceof com.smile.aceeconomy.ports.operations.LeaderboardRepository repo) {
                return repo.leaderboardRows(cid);
            }
            return accounts.listAll().stream().map(a -> {
                Amount amount = a.balanceOf(c); return amount == null ? null : new LeaderboardRow(a.owner(), a.ownerName(), amount);
            }).filter(java.util.Objects::nonNull).toList();
        }
    }

    private static BanknoteClaim claim(UUID id, Currency c, long value) {
        return new BanknoteClaim(new com.smile.acelib.item.ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, value, id, UUID.randomUUID(), c.id());
    }
}
