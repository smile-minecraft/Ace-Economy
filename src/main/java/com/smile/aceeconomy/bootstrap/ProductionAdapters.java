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
import java.util.function.Supplier;

final class ProductionAdapters {
    private ProductionAdapters() { }

    static final class Economy implements EconomyCommandService {
        private final EconomyApi api; private final CurrencyRegistry currencies; private final Executor executor;
        Economy(EconomyApi api, CurrencyRegistry currencies, Executor executor) {
            this.api = api; this.currencies = currencies; this.executor = executor;
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
            if (!currencies.contains(id)) return Optional.empty();
            Currency c = currencies.get(id);
            return Optional.of(new CommandModels.CurrencyInfo(c.id(), c.displayName(), c.symbol(), c.scale(), c.isDefault()));
        }
        public List<String> knownCurrencyIds() { return currencies.all().stream().map(Currency::id).toList(); }
        public String defaultCurrencyId() { return currencies.defaultCurrencyId(); }
    }

    static final class Admin implements AdminCommandService {
        private final EconomyApi api; private final Executor executor; private final Supplier<Boolean> reload;
        Admin(EconomyApi api, Executor executor, Supplier<Boolean> reload) {
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
            return CompletableFuture.supplyAsync(() -> reload.get() ? EconomyResult.success(null)
                    : EconomyResult.failure(EconomyError.INVALID_AMOUNT, "configuration reload failed"), executor);
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
        private final EconomyApi api; private final CurrencyRegistry currencies; private final BanknoteFactory banknotes;
        private final Executor executor;
        Withdrawals(EconomyApi api, CurrencyRegistry currencies, BanknoteFactory banknotes, Executor executor) {
            this.api = api; this.currencies = currencies; this.banknotes = banknotes; this.executor = executor;
        }
        public CompletableFuture<EconomyResult<CommandModels.WithdrawReceipt>> withdraw(UUID id, String c, Amount a) {
            return CompletableFuture.supplyAsync(() -> {
                EconomyResult<Amount> result = api.withdraw(id, c, a);
                if (result.isFailure()) return EconomyResult.failure(result.error(), result.message());
                Currency currency = currencies.get(c); BanknoteClaim claim = claim(id, currency, a.value().longValueExact());
                if (banknotes.mint(claim).isEmpty()) return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                        "banknote could not be created");
                return EconomyResult.success(new CommandModels.WithdrawReceipt(claim.nonce(), id.toString(), currency.id(), a.value()));
            }, executor);
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

    static final class Bank implements BankCommandService {
        private final V2BankGuiSession gui; private final Executor executor;
        Bank(V2BankGuiSession gui, Executor executor) { this.gui = gui; this.executor = executor; }
        public void open(UUID id, String name) { executor.execute(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) gui.open(player, "AceEconomy", 27, java.util.Set.of(22, 23, 24));
        }); }
    }

    static final class BankUseCase implements BankGuiUseCase {
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
            Currency c = currencies.get(currencies.defaultCurrencyId());
            EconomyResult<Amount> result = api.withdraw(id, c.id(), Amount.of(value, c.scale()));
            if (result.isFailure()) return WithdrawResult.rejected(result.message());
            BanknoteClaim claim = claim(id, c, value);
            return banknotes.mint(claim).map(WithdrawResult::success).orElseGet(() -> WithdrawResult.rejected("banknote could not be created"));
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
