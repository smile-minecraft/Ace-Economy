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
        private final EconomyApi api; private final Executor executor;
        private final Supplier<com.smile.aceeconomy.infrastructure.acelib.ReloadResult> reload;
        Admin(EconomyApi api, Executor executor,
              Supplier<com.smile.aceeconomy.infrastructure.acelib.ReloadResult> reload) {
            this.api = api; this.executor = executor; this.reload = reload;
        }
        public CompletableFuture<EconomyResult<Amount>> give(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(() -> api.deposit(id, c, a), executor);
        }
        public CompletableFuture<EconomyResult<Amount>> take(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(() -> api.withdraw(id, c, a), executor);
        }
        public CompletableFuture<EconomyResult<Amount>> setBalance(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(() -> api.setBalance(id, c, a), executor);
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
        private static final System.Logger LOGGER = System.getLogger(ProductionAdapters.class.getName());

        private final EconomyApi api; private final CurrencyDisplayHolder display; private final BanknoteFactory banknotes;
        private final Executor executor; private final FoliaContextExecutor folia;
        private final ScheduledExecutorService dispatchWatchdogs; private final Duration dispatchTimeout;
        Withdrawals(EconomyApi api, CurrencyDisplayHolder display, BanknoteFactory banknotes,
                    Executor executor, FoliaContextExecutor folia,
                    ScheduledExecutorService dispatchWatchdogs, Duration dispatchTimeout) {
            this.api = api;
            this.display = java.util.Objects.requireNonNull(display, "display");
            this.banknotes = banknotes; this.executor = executor;
            this.folia = java.util.Objects.requireNonNull(folia, "folia");
            this.dispatchWatchdogs = java.util.Objects.requireNonNull(dispatchWatchdogs, "dispatchWatchdogs");
            this.dispatchTimeout = java.util.Objects.requireNonNull(dispatchTimeout, "dispatchTimeout");
        }
        /**
         * Hot-swap display-only currency metadata after a validated reload. Shares one
         * holder with every other display surface, so the publish is observed atomically.
         */
        void replaceCurrencyDisplay(CurrencyRegistry candidate) {
            com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan
                    .requireDisplayOnlyChange(display.get(), candidate);
            display.publish(candidate);
        }
        /**
         * Withdraw a banknote: probe inventory space on the player's region thread, deduct and
         * mint on the IO executor, then hand the note over on the region thread again. A full
         * inventory is refused before any deduction (GUI parity); the same holds for a currency
         * the display registry cannot resolve and for an amount that cannot back a whole-number
         * banknote claim. If the note cannot be placed after the deduction — inventory filled in
         * between, player gone, delivery error — the deduction is compensated with a refund so
         * no charge is ever left without a note.
         *
         * <p>Dispatch acceptance is not execution: on Folia a region task whose player leaves
         * after acceptance may be retired without ever running its callback. Both the probe
         * and the delivery arm a bounded wait when the scheduler accepts the dispatch, so an
         * accepted-but-never-executed callback completes the reply future with a typed failure
         * instead of leaving it pending forever.
         */
        public CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> withdraw(UUID id, String c, Amount a) {
            // Three-state probe outcome: present=true has space, present=false full,
            // empty = the accepted region callback never ran (bounded wait fired).
            CompletableFuture<Optional<Boolean>> probed = new CompletableFuture<>();
            boolean dispatched = folia.runForPlayer(id, player -> {
                try {
                    probed.complete(Optional.of(player.getInventory().firstEmpty() != -1));
                } catch (Throwable t) {
                    probed.completeExceptionally(t);
                }
            });
            if (!dispatched) {
                // Nothing has happened yet: the player is unresolvable, so refuse without deducting.
                return CompletableFuture.completedFuture(EconomyResult.failure(
                        EconomyError.TRANSACTION_CANCELLED, "player left before the withdraw could start"));
            }
            // If the region callback already completed the probe this is a no-op; if the task
            // was accepted but retired without running, refuse before any deduction. The
            // lambda completes a future (value-compatible), so bind it to an explicit Runnable:
            // otherwise javac binds the call to schedule(Callable, ...) and schedulers that
            // only implement the Runnable seam silently never fire the watchdog.
            Runnable probeTimeout = () -> probed.complete(Optional.empty());
            dispatchWatchdogs.schedule(probeTimeout, dispatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return probed.thenCompose(probe -> {
                if (probe.isEmpty()) {
                    return CompletableFuture.completedFuture(EconomyResult.failure(
                            EconomyError.TRANSACTION_CANCELLED, "player left before the withdraw could start"));
                }
                if (!probe.get()) {
                    return CompletableFuture.completedFuture(EconomyResult.failure(
                            EconomyError.INVENTORY_FULL, "inventory full"));
                }
                return CompletableFuture.supplyAsync(() -> deductAndMint(id, c, a), executor)
                        .thenCompose(minted -> minted.isFailure()
                                ? CompletableFuture.completedFuture(
                                        EconomyResult.<CommandModels.WithdrawReceipt>failure(minted.error(), minted.message()))
                                : deliver(id, minted.value()));
            });
        }

        /**
         * Compensate a committed deduction whose note was never materialised (mint returned
         * empty or threw): refund the withdrawn amount exactly once, in the deduction's
         * currency. The amount to put back is the requested withdrawal {@code a} — never the
         * post-withdrawal balance that the withdraw result carries. A failed refund is logged
         * at ERROR with full context for manual reconciliation and is never retried here, so
         * a refund outage can neither silently lose the money nor double-refund.
         */
        private EconomyResult<MintedNote> compensateFailedMint(
                UUID id, String currencyId, Amount a, String failureDetail) {
            EconomyResult<Amount> refund = api.deposit(id, currencyId, a);
            if (refund.isFailure()) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "withdraw compensation failed: player={0}, currency={1}, amount={2}, reason={3}",
                        new Object[] { id, currencyId, a, refund.message() });
            }
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, failureDetail);
        }

        /** Internal carrier for a minted note plus everything compensation needs. */
        private record MintedNote(CommandModels.WithdrawReceipt receipt, ItemStack note,
                                  UUID playerId, String currencyId, Amount amount, Throwable auditError) {
        }

        private EconomyResult<MintedNote> deductAndMint(UUID id, String c, Amount a) {
            // Both fallible inputs are resolved BEFORE the deduction commits: a currency
            // the display registry cannot resolve, or an amount that cannot back a
            // whole-number banknote claim, must refuse without charging. Resolving them
            // only after the committed withdrawal would leave the account deducted with
            // neither a note nor a refund (charge-then-throw). Only minting, which runs
            // after the commit, may still fail — and that path compensates below.
            Currency currency;
            try {
                currency = display.get().get(c);
            } catch (IllegalArgumentException unknownCurrency) {
                return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, "unknown currency");
            }
            BanknoteClaim claim;
            try {
                claim = claim(id, currency, a.value().longValueExact());
            } catch (ArithmeticException unissuable) {
                return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                        "amount cannot be issued as a banknote");
            }
            EconomyResult<Amount> result = api.withdraw(id, c, a);
            if (result.isFailure()) return EconomyResult.failure(result.error(), result.message());
            ItemStack note;
            try {
                note = banknotes.mint(claim).orElse(null);
            } catch (RuntimeException mintThrew) {
                // Minting that throws an unchecked exception is the same money-safety hole
                // as an empty result: the deduction has already committed, so the charge
                // must be compensated and the caller must see a closed typed failure, not
                // an exceptional future. The type goes into the message and the stack into
                // the log; Errors are deliberately not caught — a broken VM must surface,
                // never masquerade as a failed issuance.
                LOGGER.log(System.Logger.Level.WARNING,
                        "banknote mint threw: player={0}, currency={1}, amount={2}, type={3}",
                        new Object[] { id, currency.id(), a, mintThrew.getClass().getName() });
                return compensateFailedMint(id, currency.id(), a,
                        "banknote mint failed: " + mintThrew.getClass().getSimpleName());
            }
            if (note == null) {
                // The deduction committed but the item could not be materialised: compensate on
                // the same IO executor so the account is never charged without a note. A failed
                // refund is logged at SEVERE, never swallowed.
                return compensateFailedMint(id, currency.id(), a, "banknote could not be created");
            }
            return EconomyResult.success(new MintedNote(
                    new CommandModels.WithdrawReceipt(claim.nonce(), id.toString(), currency.id(), a.value()),
                    note, id, currency.id(), a, result.auditFailure().orElse(null)));
        }

        /**
         * Hand the minted note over on the player's region thread and complete only after the
         * outcome is known, so the command reply reflects the delivery. Any path that leaves the
         * note undelivered after the deduction refunds the amount and completes with a typed
         * failure; the reply future never stays pending.
         *
         * <p>Settlement fence: the region callback and the bounded wait race for one one-shot
         * claim. Exactly one of them settles the delivery — the callback hands over the note,
         * or the watchdog refunds; the loser is a no-op. A retired task that finally runs after
         * the timeout can therefore neither deliver the note (which would pay the player twice:
         * refund plus item) nor trigger a second refund.
         */
        private CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> deliver(UUID playerId, MintedNote minted) {
            CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> done = new CompletableFuture<>();
            AtomicBoolean settled = new AtomicBoolean(false);
            boolean dispatched = folia.runForPlayer(playerId, player -> {
                if (!settled.compareAndSet(false, true)) {
                    // The bounded wait already refunded and failed the reply; delivering the
                    // note now would leave the player compensated and paid at the same time.
                    return;
                }
                try {
                    if (player.getInventory().firstEmpty() == -1) {
                        // Filled between the pre-check and this dispatch: fail closed with
                        // compensation instead of forcing a paid note into a full inventory.
                        refundAndFail(minted, EconomyError.INVENTORY_FULL, "inventory full", done);
                        return;
                    }
                    player.getInventory().addItem(minted.note());
                    done.complete(EconomyResult.success(minted.receipt(), minted.auditError()));
                } catch (Throwable t) {
                    // A throw inside the region context must not leave the reply pending or the
                    // charge uncompensated; the note's placement is unknown, so refund. The
                    // stack is logged for diagnosis; the typed failure carries only the class.
                    LOGGER.log(System.Logger.Level.WARNING,
                            "banknote delivery failed: player=" + playerId, t);
                    refundAndFail(minted, EconomyError.TRANSACTION_CANCELLED,
                            "banknote delivery failed: " + t.getClass().getSimpleName(), done);
                }
            });
            if (!dispatched) {
                refundAndFail(minted, EconomyError.TRANSACTION_CANCELLED,
                        "player left before the banknote could be delivered", done);
                return done;
            }
            dispatchWatchdogs.schedule(() -> {
                // Accepted but never executed (player left, Folia retired the task): refund
                // the committed deduction so no charge is left without a note. If the region
                // callback already settled the delivery this claim loses and does nothing.
                if (settled.compareAndSet(false, true)) {
                    refundAndFail(minted, EconomyError.TRANSACTION_CANCELLED,
                            "banknote delivery timed out before the region task ran", done);
                }
            }, dispatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return done;
        }

        /**
         * Compensate a committed deduction whose note was not handed over, then complete the
         * caller's future with the typed failure. The refund runs on the IO executor; its own
         * failure is logged at SEVERE with enough context for manual reconciliation — it is
         * surfaced, never swallowed.
         */
        private void refundAndFail(MintedNote minted, EconomyError error, String message,
                                   CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> done) {
            CompletableFuture.supplyAsync(
                            () -> api.deposit(minted.playerId(), minted.currencyId(), minted.amount()), executor)
                    .whenComplete((refund, failure) -> {
                        if (failure != null || refund == null || refund.isFailure()) {
                            LOGGER.log(System.Logger.Level.ERROR,
                                    "withdraw compensation failed: player={0}, currency={1}, amount={2}, nonce={3}, reason={4}",
                                    new Object[] { minted.playerId(), minted.currencyId(), minted.amount(),
                                            minted.receipt().noteId(),
                                            failure == null ? (refund == null ? "no result" : refund.message())
                                                    : failure.getClass().getSimpleName() });
                        }
                        done.complete(EconomyResult.failure(error, message));
                    });
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
    }

    static final class Rollback implements RollbackCommandService {
        private final RollbackService service; private final Executor executor;
        Rollback(RollbackService service, Executor executor) {
            this.service = service; this.executor = executor;
        }
        public CompletableFuture<RollbackResult> rollback(UUID transactionId) {
            return CompletableFuture.supplyAsync(() -> service.rollback(transactionId), executor);
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
    }

    /**
     * Bank command boundary: opens the GUI through the layout that is current
     * at open time. The layout is read through a supplier (the successfully
     * reloaded reference in production), so a reload that swaps the layout
     * changes what newly opened interfaces show; already-open sessions were
     * dropped by {@code invalidateAll}, so only new opens matter here.
     *
     * <p>Open-vs-reload race: the layout generation is sampled before the layout
     * itself (the reload publishes the layout reference before bumping the
     * generation), and the bound generation travels into the session open. An
     * open whose layout read raced a reload is rejected instead of building an
     * old-version session the invalidation snapshot already missed; it retries
     * once from the fresh layout so the player still gets the current GUI.
     *
     * <p>Click protection split: consumer action slots are guarded by the
     * consumer click listener (which cancels every bank top click and dispatches
     * only configured actions), never by AceLib. The AceLib protected set passed
     * here therefore stays empty: registering an action slot as AceLib-protected
     * would make {@code validateClick} reject the click before any business logic
     * runs. After a successful open the configured buttons are painted through
     * the generation-bound async-update render; the open inventory would otherwise
     * stay empty because AceLib only creates the shell.
     */
    static final class Bank implements BankCommandService {
        private final V2BankGuiSession gui;
        private final Supplier<com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout> layouts;
        private final java.util.function.LongSupplier layoutGenerations;
        private final com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages;
        private final Executor executor;
        Bank(V2BankGuiSession gui,
             Supplier<com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout> layouts,
             com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
             Executor executor) {
            this(gui, layouts, gui::layoutGeneration, messages, executor);
        }
        Bank(V2BankGuiSession gui,
             Supplier<com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout> layouts,
             java.util.function.LongSupplier layoutGenerations,
             com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
             Executor executor) {
            this.gui = gui;
            this.layouts = java.util.Objects.requireNonNull(layouts, "layouts");
            this.layoutGenerations = java.util.Objects.requireNonNull(layoutGenerations, "layoutGenerations");
            this.messages = messages; this.executor = executor;
        }
        Bank(V2BankGuiSession gui,
             com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout,
             com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
             Executor executor) {
            this(gui, () -> layout, gui::layoutGeneration, messages, executor);
        }
        public void open(UUID id, String name) { executor.execute(() -> {
            openOnce(id, false);
        }); }

        private void openOnce(UUID id, boolean isRetry) {
            long expected = layoutGenerations.getAsLong();
            com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout = layouts.get();
            if (layout == null || !layout.enabled()) {
                return;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                String title = messages.plainMessage(layout.titleKey(), java.util.Map.of());
                V2BankGuiSession.OpenOutcome outcome =
                        gui.open(player, title, layout.size(), java.util.Set.of(), expected);
                boolean stale = outcome != null && !outcome.success()
                        && "stale-layout".equals(outcome.errorCode());
                if (!isRetry && stale) {
                    openOnce(id, true);
                    return;
                }
                // Paint the configured buttons into the freshly opened shell. The
                // render re-validates the generation on both begin and apply and
                // only binds the view tag after every planned button was written,
                // so a failed paint reports render.failed and leaves the shell
                // inoperable instead of reporting success with an empty GUI.
                if (outcome != null && outcome.success() && outcome.session() != null) {
                    gui.renderLayout(id, outcome.session().generation(), layout, messages);
                }
            }
        }
    }

    static final class BankUseCase implements BankGuiUseCase {
        private static final System.Logger LOGGER = System.getLogger(ProductionAdapters.class.getName());
        private final EconomyApi api; private final EconomyService economy; private final CurrencyRegistry currencies;
        private final BanknoteFactory banknotes; private final BanknoteValidator validator;
        private final AtomicRedemptionStore redemptions;
        BankUseCase(EconomyApi api, EconomyService economy, CurrencyRegistry currencies,
                    BanknoteFactory banknotes, BanknoteValidator validator,
                    AtomicRedemptionStore redemptions) {
            this.api = api; this.economy = economy; this.currencies = currencies; this.banknotes = banknotes;
            this.validator = validator; this.redemptions = redemptions;
        }
        /** Backward-compatible constructor for offline tests that bypass the economy lock/validation. */
        BankUseCase(EconomyApi api, CurrencyRegistry currencies, BanknoteFactory banknotes,
                    BanknoteValidator validator, AtomicRedemptionStore redemptions) {
            this(api, null, currencies, banknotes, validator, redemptions);
        }
        public WithdrawResult withdraw(UUID id, long value) {
            return withdraw(id, value, null);
        }
        @Override
        public WithdrawResult withdraw(UUID id, long value, String currencyId) {
            Currency c = currencyId == null || currencyId.isBlank()
                    ? currencies.get(currencies.defaultCurrencyId())
                    : currencies.get(currencyId);
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
                return refundAndReject(id, c.id(), amount,
                        "banknote mint failed: " + mintThrew.getClass().getSimpleName());
            }
            if (note == null) {
                // The deduction committed but the item could not be materialised: compensate
                // exactly once with the amount that actually left the account, in the same
                // currency — the same money-safety rule the command path applies. A failed
                // refund is logged at ERROR and stays observable; it is never retried here,
                // so a refund outage can neither silently lose the money nor double-refund.
                return refundAndReject(id, c.id(), amount, "banknote could not be created");
            }
            return WithdrawResult.success(note);
        }

        /**
         * Compensate a committed deduction after a mint failure, then close the withdraw
         * with a typed rejection. The refund carries the withdrawn amount (never the
         * after-balance a withdrawal result resolves to), runs at most once and is never
         * retried; a refund outage is logged at ERROR with full context so it stays
         * observable for manual reconciliation instead of silently losing the money.
         */
        private WithdrawResult refundAndReject(UUID id, String currencyId, Amount withdrawn, String reason) {
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
         * and all-or-none balance/audit/nonce). Every rejection leaves the physical item
         * untouched; only a committed redemption lets the caller remove it. Storage failures
         * are mapped to the stable {@code credit.failed} reason instead of throwing into the
         * scheduler dispatch, where they would surface as an indistinguishable accepted click.
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
            // Production path preserves the full EconomyService contract (lock, pre-commit,
            // debt, audit semantics) and delegates durability to the prepared atomic store.
            if (economy != null) {
                EconomyResult<Amount> r = economy.redeemBanknote(claim.nonce(), id, c.id(), amount, redemptions);
                if (r.isSuccess()) {
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
                return DepositResult.rejected("credit.failed");
            }
            // Legacy fallback for offline unit tests that inject only the raw store.
            try {
                RedemptionResult r = redemptions.redeem(claim.nonce(), id, c.id(), amount);
                if (r.isCommitted()) {
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
