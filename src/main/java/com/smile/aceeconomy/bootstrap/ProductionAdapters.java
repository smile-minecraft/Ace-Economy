package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.commands.v2.CommandModels;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.operations.LeaderboardPage;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.WithdrawResult;
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

    static final class Bank implements BankCommandService {
        private final V2BankGuiSession gui; private final Executor executor;
        Bank(V2BankGuiSession gui, Executor executor) { this.gui = gui; this.executor = executor; }
        public void open(UUID id, String name) { executor.execute(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) gui.open(player, "AceEconomy", 27, java.util.Set.of(22, 23, 24));
        }); }
    }

    static final class BankUseCase implements BankGuiUseCase {
        private final EconomyApi api; private final CurrencyRegistry currencies; private final BanknoteFactory banknotes;
        BankUseCase(EconomyApi api, CurrencyRegistry currencies, BanknoteFactory banknotes) {
            this.api = api; this.currencies = currencies; this.banknotes = banknotes;
        }
        public WithdrawResult withdraw(UUID id, long value) {
            Currency c = currencies.get(currencies.defaultCurrencyId());
            EconomyResult<Amount> result = api.withdraw(id, c.id(), Amount.of(value, c.scale()));
            if (result.isFailure()) return WithdrawResult.rejected(result.message());
            BanknoteClaim claim = claim(id, c, value);
            return banknotes.mint(claim).map(WithdrawResult::success).orElseGet(() -> WithdrawResult.rejected("banknote could not be created"));
        }
    }

    static final class RepositoryLeaderboardSource implements LeaderboardSource {
        private final AccountRepository accounts;
        RepositoryLeaderboardSource(AccountRepository accounts) { this.accounts = accounts; }
        public List<LeaderboardRow> rows(String c) { return accounts.listAll().stream().map(a -> {
            Amount amount = a.balanceOf(c); return amount == null ? null : new LeaderboardRow(a.owner(), a.ownerName(), amount);
        }).filter(java.util.Objects::nonNull).toList(); }
    }

    private static BanknoteClaim claim(UUID id, Currency c, long value) {
        return new BanknoteClaim(new com.smile.acelib.item.ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, value, id, UUID.randomUUID(), c.id());
    }
}
