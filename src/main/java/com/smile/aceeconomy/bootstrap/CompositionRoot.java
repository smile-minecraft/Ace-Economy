package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.form.FormService;
import com.smile.acelib.command.BukkitCommandBridge;
import com.smile.acelib.command.BukkitReplySink;
import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.event.SafeEventRegistry;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiScopes;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.aceeconomy.acelib.AceLibAccess;
import com.smile.aceeconomy.acelib.AceLibModule;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.ports.CommittedTransactionListener;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.commands.v2.CommandServices;
import com.smile.aceeconomy.commands.v2.MainCommandAliasPolicy;
import com.smile.aceeconomy.commands.v2.V2CommandRegistry;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.SafeSchedulerFoliaContext;
import com.smile.aceeconomy.gui.v2.BankFormSession;
import com.smile.aceeconomy.gui.v2.BankOpenRouter;
import com.smile.aceeconomy.infrastructure.integration.acelib.AceLibExternalServiceReadiness;
import com.smile.aceeconomy.infrastructure.integration.acelib.ExternalIntegrationCoordinator;
import com.smile.aceeconomy.infrastructure.integration.acelib.IntegrationModule;
import com.smile.aceeconomy.infrastructure.integration.placeholder.AceEconomyExpansion;
import com.smile.aceeconomy.infrastructure.integration.placeholder.BukkitPlaceholderRegistration;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderBalanceLoader;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderIntegrationModule;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderLifecycle;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderResolver;
import com.smile.aceeconomy.infrastructure.integration.vault.BukkitVaultRegistration;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultEconomyLifecycle;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultEconomyProvider;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultIntegrationModule;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.LocalizedBanknoteFace;
import com.smile.aceeconomy.infrastructure.item.V2BanknoteFactory;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.infrastructure.operations.StorageReversalExecutor;
import com.smile.aceeconomy.infrastructure.persistence.PersistenceBackendFactory;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.PersistentIdempotencyGuard;
import com.smile.aceeconomy.infrastructure.persistence.StorageConfig;
import com.smile.aceeconomy.infrastructure.persistence.StorageConfigParser;
import com.smile.aceeconomy.infrastructure.session.AsyncAccountSessionStore;
import com.smile.aceeconomy.infrastructure.session.PlayerSessionManager;
import com.smile.aceeconomy.gui.v2.BanknoteRedeemListener;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.operations.HistoryService;
import com.smile.aceeconomy.operations.BackupRestoreService;
import com.smile.aceeconomy.operations.ImportRunner;
import com.smile.aceeconomy.operations.ImportService;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.TransactionEventPublisher;
import com.smile.aceeconomy.ports.persistence.AtomicImportStore;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import com.smile.aceeconomy.ports.persistence.NonceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The single v2 production composition root. Modules are declared as a linear AceLib
 * lifecycle-host chain; the host supplies topological start order, reverse stop order,
 * rollback after partial start, and idempotency.
 */
public final class CompositionRoot {
    private static final long SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L;

    private final JavaPlugin plugin;
    private final HostModuleLifecycle lifecycle;
    private final ConfigLangAdapter config;

    private ExecutorService ioExecutor;
    private ScheduledExecutorService dispatchWatchdogs;
    private PersistenceLifecycle persistence;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private AtomicReversalStore reversals;
    private AtomicRedemptionStore redemptions;
    private AtomicImportStore importStore;
    private NonceStore nonces;
    private CurrencyRegistry currencies;
    private EconomyService economy;
    private EconomyApiImpl api;
    private InMemoryTransactionEventPublisher publisher;
    private SafeScheduler scheduler;
    private FoliaContextExecutor folia;
    private PlayerSessionManager sessions;
    private V2BankGuiSession bankGui;
    private BankFormSession bankForms;
    // Plugin-owned GUI scope for the bank surfaces. Created with the
    // presentation slice and closed on its stop; it never reaches the shared
    // service or its AceLib-owned listener.
    private GuiScope bankGuiScope;
    private com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow banknoteFlow;
    private CommandRegistry commandRegistry;
    // Bound to the transaction publisher and dropped on presentation stop. Every committed
    // balance mutation invalidates the shared leaderboard snapshot, so /baltop (and the PAPI
    // rank placeholders reading the same cache) never wait out the TTL to see a change.
    private CommittedTransactionListener leaderboardInvalidationListener;
    // Bound to the transaction publisher when Discord notifications are active and dropped on
    // presentation stop. Every committed transaction is announced exactly once through this
    // post-commit observer; the audit sink stays a plain persistence bridge.
    private CommittedTransactionListener discordPostCommitListener;
    private ExternalIntegrationCoordinator integrations;
    private V2BanknoteFactory banknotes;
    private RollbackService rollbacks;
    private BanknoteValidator banknoteValidator;
    // Shared with the PlaceholderAPI resolver so /baltop and PAPI rank/top placeholders
    // always read the same cached ranking (same instance, same TTL).
    private LeaderboardCache leaderboardCache;
    private Duration leaderboardTtl;
    // Single publish point for display-only currency metadata. Every display surface
    // (command adapters, Vault, placeholders) reads through this holder, so one validated
    // publish moves them all together and concurrent requests only ever see the whole old
    // registry or the whole new one.
    private CurrencyDisplayHolder displayHolder;
    // Last applied bank GUI layout. Snapshotted before every reload apply so a post-publish
    // failure can restore the previous resolver with the same infallible reference swap.
    // Volatile because the reload writer and the bank-open readers run on different executors.
    private volatile BankGuiLayout currentLayout;

    public CompositionRoot(JavaPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.config = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        this.lifecycle = new HostModuleLifecycle(plugin, new AceLibAccess(plugin));
    }

    /** Start the complete v2 graph once. */
    public void start() throws Exception {
        registerModules();
        try {
            lifecycle.startAll();
        } catch (Exception failure) {
            plugin.getLogger().severe("AceEconomy v2 startup failed: " + failure.getMessage());
            throw failure;
        }
    }

    /** Stop the graph; repeated calls are no-ops and session flushing is bounded. */
    public void stop() {
        try {
            lifecycle.stopAll();
        } catch (Exception failure) {
            plugin.getLogger().severe("AceEconomy v2 shutdown completed with errors: " + failure.getMessage());
        }
    }

    private void registerModules() {
        // Linear dependency chain: the host's topological order is exactly this declaration
        // order, and its reverse disable order is exactly the previous reverse stop order.
        // Ids are namespaced per plugin so a second consumer sharing the same AceLib host
        // can never collide with (or be torn down by) this graph.
        lifecycle.add("aceeconomy:configuration", Set.of(),
                new NamedModule("configuration", resources -> config.load(), resources -> config.close()));
        lifecycle.add("aceeconomy:persistence", Set.of("aceeconomy:configuration"),
                new NamedModule("persistence", this::startPersistence, resources -> stopPersistence()));
        lifecycle.add("aceeconomy:application", Set.of("aceeconomy:persistence"),
                new NamedModule("application", this::startApplication, resources -> { }));
        lifecycle.add("aceeconomy:acelib-runtime", Set.of("aceeconomy:application"),
                new RuntimeModule());
        lifecycle.add("aceeconomy:sessions", Set.of("aceeconomy:acelib-runtime"),
                new NamedModule("sessions", this::startSessions, resources -> stopSessions()));
        lifecycle.add("aceeconomy:presentation", Set.of("aceeconomy:sessions"),
                new NamedModule("presentation", this::startPresentation, resources -> stopPresentation()));
        lifecycle.add("aceeconomy:integrations", Set.of("aceeconomy:presentation"),
                new NamedModule("integrations", this::startIntegrations, resources -> stopIntegrations()));
    }

    private void startPersistence(ResourceOwner resources) throws Exception {
        ioExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "aceeconomy-v2-io");
            thread.setDaemon(true);
            return thread;
        });
        resources.register(() -> ioExecutor.shutdown());

        // One-shot dispatch watchdogs bound the withdraw probe/delivery wait for the region
        // callback: on Folia a task whose player leaves after acceptance may be retired
        // without ever running, so without a bounded wait the reply future would hang and a
        // committed deduction could never be compensated. Daemon thread, torn down with the
        // persistence module that owns the other IO threads.
        dispatchWatchdogs = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aceeconomy-v2-dispatch-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        resources.register(() -> dispatchWatchdogs.shutdownNow());

        // Config → typed StorageConfig → factory. The factory owns the JSON / SQLite /
        // MySQL backend selection and the connection / pool lifecycle. On any failure it
        // releases whatever it acquired and propagates the original exception, so the
        // host compensation tears down ioExecutor as usual.
        Object storageRaw = config.getConfig("storage");
        StorageConfig storageConfig = StorageConfigParser.parse(
                storageRaw, plugin.getDataFolder().toPath());
        PersistenceBackendFactory.ResourceRegistry registry = resources::register;
        PersistenceBackendFactory.WiringResult wiring =
                PersistenceBackendFactory.create(storageConfig, registry);

        persistence = wiring.lifecycle();
        accounts = wiring.accounts();
        transactions = wiring.transactions();
        // The atomic reversal store, the atomic redemption store and the durable nonce store
        // are the SAME backend instance (enforced by WiringResult), so rollback mutations,
        // banknote redemptions and nonce consumption share one storage transaction boundary
        // with ordinary reads and writes.
        reversals = wiring.reversals();
        redemptions = wiring.redemptions();
        importStore = wiring.imports();
        nonces = wiring.nonces();
    }

    private void stopPersistence() {
        if (persistence != null) {
            persistence.close();
        }
    }

    private void startApplication(ResourceOwner resources) {
        currencies = buildCurrencies();
        Clock clock = () -> Instant.now();
        publisher = new InMemoryTransactionEventPublisher();
        Amount startBalance = currencies.get(currencies.defaultCurrencyId()).amountOf(decimal("start-balance", 1000.0));
        DebtPolicy debt = bool("economy.allow-negative-balance", true)
                ? DebtPolicy.enabled(currencies.get(currencies.defaultCurrencyId()).amountOf(
                decimal("economy.default-debt-limit", 0.0)))
                : DebtPolicy.disabled();

        // Discord notification wiring: best-effort, post-commit via a dedicated observer.
        // Active only when discord.enabled=true AND the webhook URL passes strict validation
        // (absolute, scheme http/https, host required, no userinfo, legal port). A failed
        // validation degrades gracefully: a fixed diagnostic is logged (the URL is never
        // echoed) and the audit sink stays the plain PersistentAuditSink.
        // Notification travels only through the post-commit channel, never through a notifying
        // audit decorator, so atomically persisted transfers are announced exactly once and
        // never re-persisted. Notification failures never become AuditException, so transaction
        // results and audit-failure semantics remain unpolluted. The listener is unregistered
        // with the presentation slice below.
        //
        // The wiring is delegated to the package-private {@link #wireDiscord} seam so tests
        // can drive the same path with fake dependencies (transactions, executor, transport
        // factory, diagnostics consumer) without booting Bukkit.
        DiscordWiring.Outcome discord = wireDiscord(
                transactions,
                ioExecutor,
                publisher,
                resources,
                bool("discord.enabled", false),
                string("discord.webhook-url", ""),
                plugin.getLogger());
        AuditSink auditSink = discord.auditSink();
        discordPostCommitListener = discord.postCommitListener();

        economy = new EconomyService(currencies, debt, startBalance, accounts,
                auditSink, clock, publisher);
        api = new EconomyApiImpl(economy, publisher);
        // The shared display holder starts on the same registry instance every holder will
        // read through; display-only reloads advance it with a single atomic publish.
        displayHolder = new CurrencyDisplayHolder(currencies);

        // Production rollback boundary and banknote replay guard. Both are bound to the
        // durable persistence capabilities acquired above; the command surface binds its
        // entry points to them in its own slice. The in-memory executor / guard classes
        // are replacement stubs for verification only and never appear in this graph.
        rollbacks = new RollbackService(transactions,
                new StorageReversalExecutor(accounts, reversals, clock,
                        // Rollback persists directly and bypasses EconomyService: drop the
                        // affected read-cache entries so Vault never serves pre-rollback
                        // balances. Guarded for unit seams where economy is not built yet.
                        uuid -> {
                            EconomyService live = economy;
                            if (live != null) {
                                live.invalidateBalance(uuid);
                            }
                        },
                        // Rollback follows the same debt policy as ordinary transactions:
                        // economy.allow-negative-balance=false refuses a reversal that
                        // would drive a balance negative.
                        debt));
        banknoteValidator = new BanknoteValidator(new PersistentIdempotencyGuard(nonces));
    }

    private void startSessions(ResourceOwner resources) {
        folia = new SafeSchedulerFoliaContext(scheduler);
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(accounts, ioExecutor);
        sessions = new PlayerSessionManager(store, folia, SESSION_SHUTDOWN_DEADLINE_MILLIS);
        PlayerSessionListener listener = new PlayerSessionListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        resources.register(() -> HandlerList.unregisterAll(listener));
    }

    private void stopSessions() {
        if (sessions != null) {
            sessions.disable(SESSION_SHUTDOWN_DEADLINE_MILLIS);
        }
        // Disable invalidation for the balance read cache: shutdown drops every snapshot
        // with its session, so no cached balance may survive for a later synchronous query
        // (or a late async write, whose stamp is now stale and discarded) to resurrect.
        if (economy != null) {
            economy.invalidateAllBalances();
        }
    }

    private void startPresentation(ResourceOwner resources) {
        // The face resolves {currency} through the shared display holder, so the minted note shows
        // the operator-configured currency display name and follows display-only reloads like every
        // other display surface; an unknown id degrades to the raw id inside the face renderer.
        banknotes = new V2BanknoteFactory(LocalizedBanknoteFace.production(config, currencyId -> {
            CurrencyRegistry display = displayHolder.get();
            return display.contains(currencyId) ? display.get(currencyId).displayName() : null;
        }));
        // Component replies are handed to the region dispatch seam so the send runs on the
        // player's region thread; IO completion threads never resolve or message a Bukkit
        // Player directly. Installed once here, uninstalled with the presentation slice.
        com.smile.aceeconomy.commands.v2.CommandReply.installRegionDispatch(folia::runForPlayer);
        // The dispatch timeout bounds how long the shared banknote pipeline may wait for a
        // region callback that the scheduler accepted but retired without running (player
        // left). Beyond it the withdraw refuses or refunds instead of leaving the reply
        // pending forever. A zero or negative operator value is floored at the one-second
        // fail-safe minimum instead of timing every withdraw out immediately.
        Duration withdrawDispatchTimeout =
                effectiveDispatchTimeout(integer("withdraw.dispatch-timeout-seconds", 10));
        // Single shared banknote pipeline for the command, Java GUI and Bedrock form
        // entries: region probe first, money movement on the IO executor, item handover
        // back on the region thread. Storage never runs on a region thread.
        banknoteFlow = new com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow(
                api, economy, redemptions, banknotes, displayHolder, folia, ioExecutor,
                dispatchWatchdogs, withdrawDispatchTimeout);
        // The bank GUI use case binds to the SAME durable validator / redemption store and the
        // application economy service (lock, pre-commit, debt policy) so every redeem goes
        // through the prepared atomic path; no in-memory guard appears in this graph.
        ProductionAdapters.BankUseCase bankUseCase =
                new ProductionAdapters.BankUseCase(api, economy, currencies, banknotes,
                        banknoteValidator, redemptions, banknoteFlow);
        // The bank GUI layout is operator-owned: the whole section is parsed and
        // validated before any session or listener is built, so a malformed
        // bank-gui block aborts the presentation slice instead of leaving a
        // partially applied GUI behind. A missing block keeps the legacy
        // defaults (deposit 4, withdraw 100/500 on 11/13, close 15, size 27).
        BankGuiLayout bankGuiLayout = BankGuiConfigParser.parse(
                value("bank-gui"), Set.copyOf(currencies.all().stream().map(Currency::id).toList()));
        GuiService guiService = requireApi().getGuiService();
        // Plugin-owned GUI scope: the single handle this plugin opens bank
        // views through and closes on stop. The bank screen below opens only
        // through this scope, so the shared service and its AceLib-owned
        // listener are never shut down or removed here. Click protection,
        // stale-click rejection and drag protection are owned upstream; the
        // tracked views are still drained explicitly on stop.
        bankGuiScope = GuiScopes.create(plugin, guiService);
        resources.register(this::dropBankGuiScope);
        bankGui = new V2BankGuiSession(bankGuiScope, folia, bankUseCase,
                bankGuiLayout, config, banknoteFlow);
        currentLayout = bankGuiLayout;
        // Right-click redemption reuses the same atomic bank use case as the GUI deposit button
        // (durable nonce consumption + credit commit together); the listener owns its click-time
        // snapshot and region-context removal, so no other slice needs to know about interact events.
        BanknoteRedeemListener redeemListener = new BanknoteRedeemListener(
                bankUseCase, banknotes, folia, config, plugin.getLogger(), banknoteFlow);
        Bukkit.getPluginManager().registerEvents(redeemListener, plugin);
        resources.register(() -> HandlerList.unregisterAll(redeemListener));

        ProductionAdapters.Economy economyCommands =
                new ProductionAdapters.Economy(api, displayHolder, ioExecutor);
        ProductionAdapters.Withdrawals withdrawalCommands =
                new ProductionAdapters.Withdrawals(banknoteFlow);
        // Hoisted so the backup/restore service can invalidate the SAME leaderboard cache
        // after a successful restore (single instance, single invalidation boundary).
        // The fields keep the instance and TTL alive for the PAPI resolver wiring below,
        // so /baltop and rank/top placeholders sort from the same snapshot.
        leaderboardCache = new LeaderboardCache();
        leaderboardTtl = Duration.ofSeconds(integer("leaderboard.cache-time-seconds", 300));
        LeaderboardService leaderboardService = new LeaderboardService(
                new ProductionAdapters.RepositoryLeaderboardSource(accounts),
                () -> Instant.now(), leaderboardCache, leaderboardTtl);
        ProductionAdapters.Leaderboards leaderboards = new ProductionAdapters.Leaderboards(
                leaderboardService,
                integer("leaderboard.page-size", 10), ioExecutor);
        // Every committed balance mutation drops the shared ranking snapshot, so /baltop and the
        // PAPI rank placeholders never wait out the TTL after give/take/set/pay/withdraw/deposit.
        // The listener is a pure cache clear (no IO, no Bukkit), so it may run inline on the
        // committing thread; it is unregistered with the presentation slice below.
        leaderboardInvalidationListener = wireLeaderboardInvalidation(publisher, leaderboardService);
        ProductionAdapters.Bank javaBankCommands =
                new ProductionAdapters.Bank(bankGui, () -> currentLayout, ioExecutor);
        // Bedrock native-form bank surface. The form service is resolved
        // defensively: an unavailable Bedrock facade (Floodgate absent) leaves
        // a null transport, and the session fails closed at send time while
        // the detector keeps every player on the Java chest path.
        FormService bedrockForms = null;
        try {
            BedrockService bedrock = requireApi().getBedrockService();
            if (bedrock != null) {
                bedrockForms = bedrock.forms();
            }
        } catch (Throwable failure) {
            plugin.getLogger().warning("Bedrock form service unavailable, bank forms disabled: "
                    + failure.getClass().getSimpleName());
        }
        bankForms = new BankFormSession(bedrockForms, folia, bankUseCase, api,
                () -> currencies, config, banknoteFlow);
        BankOpenRouter bankCommands = new BankOpenRouter(
                javaBankCommands, bankForms::open, config.bedrockDetector(), ioExecutor);
        ProductionAdapters.Admin adminCommands = new ProductionAdapters.Admin(economy, ioExecutor,
                () -> {
                    com.smile.aceeconomy.infrastructure.acelib.ReloadResult result =
                            config.reloadWithRuntime(reloadRuntime());
                    // Reload invalidation: configuration (currencies, balances) may have changed,
                    // so cached balances are dropped and re-primed by later persisted reads.
                    if (result.success() && economy != null) {
                        economy.invalidateAllBalances();
                    }
                    return result;
                });
        ProductionAdapters.History historyCommands = new ProductionAdapters.History(
                new HistoryService(transactions), ioExecutor);
        // The rollback command surface binds to the SAME RollbackService created in the
        // application slice (atomic StorageReversalExecutor + durable marker ownership);
        // the adapter only moves the blocking call onto the IO executor.
        ProductionAdapters.Rollback rollbackCommands =
                new ProductionAdapters.Rollback(rollbacks, ioExecutor);
        // The backup/restore command surface binds to the SAME PersistenceLifecycle acquired
        // in the persistence slice. Snapshots live under <dataFolder>/backups; restore is
        // gated on no online players plus a pre-restore safety snapshot, and a success drops
        // both the shared leaderboard rankings and the cached balances (the world underneath
        // was replaced) before the operator restarts the server.
        BackupRestoreService backupRestoreService = new BackupRestoreService(
                persistence,
                plugin.getDataFolder().toPath(),
                () -> !Bukkit.getOnlinePlayers().isEmpty(),
                () -> Set.copyOf(currencies.all().stream().map(Currency::id).toList()),
                restoreCacheReset(leaderboardService, economy));
        ProductionAdapters.BackupRestore backupCommands =
                new ProductionAdapters.BackupRestore(backupRestoreService, ioExecutor);
        // The import command surface binds to an ImportService over the SAME
        // accounts/transactions/nonce stores as the rest of the graph, plus the
        // backup service above for the pre-import safety snapshot. Vendor files
        // are read only from <dataFolder>/import through the path gate; the
        // directory is created here so a missing folder is a setup note, not a
        // per-command failure. Imported balances bypass EconomyService the same
        // way rollback does, so the same balance-cache invalidation applies.
        ImportService importService = new ImportService(currencies, accounts, transactions,
                () -> Instant.now(), new PersistentIdempotencyGuard(nonces),
                uuid -> {
                    EconomyService live = economy;
                    if (live != null) {
                        live.invalidateBalance(uuid);
                    }
                }, importStore, publisher);
        try {
            java.nio.file.Files.createDirectories(plugin.getDataFolder().toPath().resolve("import"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not create the import directory; "
                    + "/aceeco import will reject every path until it exists: " + e.getMessage());
        }
        ImportRunner importRunner = new ImportRunner(importService, backupRestoreService,
                plugin.getDataFolder().toPath(), currencies);
        ProductionAdapters.Import importCommands =
                new ProductionAdapters.Import(importRunner, ioExecutor);
        CommandServices services = new CommandServices(economyCommands,
                new ProductionAdapters.Players(ioExecutor), withdrawalCommands, leaderboards, bankCommands,
                adminCommands, historyCommands, rollbackCommands, backupCommands, importCommands, config);
        // Command-surface flags are startup-only wiring: the leaderboard toggle decides whether
        // an executable baltop spec exists at all, and the main-command alias is validated
        // against every label plugin.yml declares plus the sibling v2 specs. Bukkit only routes
        // statically declared labels and AceLib bridges attach to those roots, so changing
        // either value requires a restart; reload never re-registers commands.
        boolean leaderboardEnabled = bool("leaderboard.enabled", true);
        String configuredAlias = string("settings.main-command-alias", "aceeco");
        Map<String, Map<String, Object>> declaredCommands = plugin.getDescription().getCommands();
        V2CommandRegistry v2Commands = V2CommandRegistry.create(services, configuredAlias,
                leaderboardEnabled,
                MainCommandAliasPolicy.declaredBukkitLabels(declaredCommands),
                MainCommandAliasPolicy.declaredAliasesByRoot(declaredCommands));
        // The decorator maps a bare player /money (and its /balance, /bal aliases) to the
        // balance subcommand; AceLib v1.4.0 answers an empty argument list with help, so the
        // intended self-balance UX needs this default-subcommand layer. All other labels and
        // explicit subcommands flow through untouched.
        commandRegistry = new com.smile.aceeconomy.commands.v2.DefaultSubcommandRegistry(
                new CommandRegistryImpl(new BukkitReplySink(plugin,
                        new com.smile.aceeconomy.infrastructure.acelib.FoliaRegionReplyBackend(folia))),
                Map.of("money", "balance"));
        v2Commands.register(commandRegistry);
        for (var spec : v2Commands.specs()) {
            new BukkitCommandBridge(commandRegistry).attach(plugin, spec.name());
        }
        resources.register(commandRegistry::onPluginDisable);
    }

    private void stopPresentation() {
        // Stop accepting new banknote operations first, then drain the accepted ones
        // within the shared deadline. Late completions never message a player: the
        // flow itself stays silent and every caller's region reply is best-effort
        // through a scheduler that is still alive at this point. Persistence and
        // its executors stop later in reverse order, so the drain still has IO and
        // watchdogs available. A forced crash may still strand operations, which is
        // why crash recovery is explicitly out of scope.
        if (banknoteFlow != null) {
            try {
                banknoteFlow.shutdown(SESSION_SHUTDOWN_DEADLINE_MILLIS);
            } catch (Throwable failure) {
                plugin.getLogger().warning("Banknote flow shutdown completed with errors: "
                        + failure.getClass().getSimpleName());
            }
        }
        // Drop the reply seam first: after this point no reply may dispatch through a
        // scheduler that is about to be torn down.
        com.smile.aceeconomy.commands.v2.CommandReply.installRegionDispatch(null);
        // Unregister the transaction listeners before the publisher's owner stops: a late
        // transaction during shutdown must not touch a leaderboard slice that is going away
        // or enqueue Discord deliveries into a drained notifier.
        if (publisher != null && leaderboardInvalidationListener != null) {
            publisher.unregisterPostCommit(leaderboardInvalidationListener);
            leaderboardInvalidationListener = null;
        }
        if (publisher != null && discordPostCommitListener != null) {
            publisher.unregisterPostCommit(discordPostCommitListener);
            discordPostCommitListener = null;
        }
        // Close the plugin-owned scope first (it ends every bank view it
        // opened), then drop the locally tracked views and form entries.
        // Both steps are idempotent, so a repeated stop is a no-op.
        dropBankGuiScope();
        invalidateBankSessions(bankGui, bankForms);
        if (commandRegistry != null) {
            commandRegistry.onPluginDisable();
        }
    }

    /**
     * Release the plugin-owned GUI scope created with the presentation slice.
     *
     * <p>Only this plugin's scope is closed; the shared {@code GuiService}
     * and its AceLib-owned listener stay untouched, so other consumers of
     * the same AceLib instance are unaffected. Null-safe and idempotent:
     * the field is cleared first, so a repeated call — from a second stop
     * or from resource compensation after a failed start — closes nothing.
     */
    private void dropBankGuiScope() {
        GuiScope scope = bankGuiScope;
        bankGuiScope = null;
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Throwable failure) {
            plugin.getLogger().warning("Bank GUI scope close completed with errors: "
                    + failure.getClass().getSimpleName());
        }
    }

    /**
     * Drop every self-owned bank view so no pre-disable click can act afterwards.
     *
     * <p>Each tracked chest view is closed through the owning scope and each
     * form entry is discarded locally; the shared {@code GuiService} itself is never shut down
     * or released, so other consumers of the same AceLib instance are unaffected. Null-safe
     * and idempotent: a repeated call finds no tracked views and drops nothing. A scope
     * close that throws still clears tracking, and the warning is already logged
     * by the scope release above, so later clicks find no entry instead of a
     * dead one.
     *
     * @return the number of locally tracked views dropped
     */
    static int invalidateBankSessions(V2BankGuiSession bankGui, BankFormSession bankForms) {
        int dropped = 0;
        if (bankGui != null) {
            dropped += bankGui.invalidateAll();
        }
        if (bankForms != null) {
            dropped += bankForms.invalidateAll();
        }
        return dropped;
    }

    private void startIntegrations(ResourceOwner resources) {
        List<IntegrationModule> modules = new ArrayList<>();
        AceLibApi ready = requireApi();
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomyProvider provider = new VaultEconomyProvider(api, displayHolder);
            modules.add(new VaultIntegrationModule("vault", "vault",
                    new VaultEconomyLifecycle(new BukkitVaultRegistration(plugin, ServicePriority.Normal), provider)));
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            // The resolver shares the leaderboard cache instance (and TTL) with the
            // /baltop command path, so PAPI rank/top placeholders sort exactly like
            // the command output. Placeholder callbacks must never do storage I/O,
            // so a missing snapshot simply resolves to null here. Balance misses
            // enqueue a bounded, de-duplicated background prime on the shared IO
            // executor and likewise resolve to null immediately.
            PlaceholderBalanceLoader balanceLoads =
                    new PlaceholderBalanceLoader(api, ioExecutor);
            PlaceholderResolver resolver;
            if (leaderboardCache != null && leaderboardTtl != null) {
                Clock leaderboardClock = () -> Instant.now();
                resolver = new PlaceholderResolver(api, displayHolder, leaderboardCache,
                        leaderboardTtl, leaderboardClock, balanceLoads);
            } else {
                resolver = new PlaceholderResolver(api, displayHolder, balanceLoads);
            }
            AceEconomyExpansion expansion = new AceEconomyExpansion(resolver, plugin.getDescription().getVersion());
            modules.add(new PlaceholderIntegrationModule("placeholderapi", "placeholderapi",
                    new PlaceholderLifecycle(new BukkitPlaceholderRegistration(), expansion,
                            balanceLoads)));
        }
        integrations = new ExternalIntegrationCoordinator(
                new AceLibExternalServiceReadiness(ready.getExternalIntegrationService()), modules);
        integrations.start();
        resources.register(integrations::stop);
    }

    private void stopIntegrations() {
        if (integrations != null) {
            integrations.stop();
        }
    }

    private AceLibApi requireApi() {
        return new AceLibAccess(plugin).resolveReadyApi()
                .orElseThrow(() -> new IllegalStateException("AceLib is missing or not ready"));
    }

    private CurrencyRegistry buildCurrencies() {
        // The currencies section is operator-owned: any legal currency map loads, and the
        // parser validates the whole section before constructing a registry, so a malformed
        // config aborts startup instead of leaving a partially applied economy behind.
        return CurrencyConfigParser.parse(value("currencies"));
    }

    /**
     * Production side of the reload transaction. Only identical or display-only currency
     * plans are approved: added ids need batch account initialization no backend offers,
     * and removed ids / scale / default changes would reinterpret stored balances, so both
     * are refused with a restart notice instead of a silent half-application.
     */
    private com.smile.aceeconomy.infrastructure.acelib.ReloadRuntime reloadRuntime() {
        return new com.smile.aceeconomy.infrastructure.acelib.ReloadRuntime() {
            @Override
            public CurrencyRegistry liveCurrencies() {
                return currencies;
            }

            @Override
            public java.util.List<String> reviewCurrencyCandidate(
                    com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan.Classification plan) {
                return switch (plan.disposition()) {
                    case IDENTICAL, DISPLAY_ONLY -> java.util.List.of();
                    case ADDED, DANGEROUS, INVALID -> java.util.List.of(
                            plan.summary() + " :: " + String.join("; ", plan.details()));
                };
            }

            @Override
            public void applyApproved(
                    com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan.Classification plan,
                    BankGuiLayout layout) {
                // Snapshot before the swap window so a mid-apply failure can roll every
                // reference back: without this, a published holder with a failed economy
                // or layout step would report failure while memory already runs the new
                // version and the next reload would diff against the wrong baseline.
                CurrencyRegistry oldDisplay = displayHolder.get();
                CurrencyRegistry oldCurrencies = currencies;
                BankGuiLayout oldLayout = currentLayout;
                boolean displaySwapped = false;
                boolean economySwapped = false;
                boolean layoutSwapped = false;
                try {
                    if (plan.disposition()
                            == com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan.Disposition
                                    .DISPLAY_ONLY) {
                        // Single atomic publish: every display surface (commands, Vault,
                        // placeholders) reads through the shared holder, so concurrent requests
                        // see the whole old registry or the whole new one, never a mixture.
                        // The publish re-validates first and throws before writing anything,
                        // so a rejected candidate cannot leave a half-applied state behind.
                        // The economy service keeps its own transactional copy (ids, scales
                        // and default never change in a display-only diff); its guard passes
                        // deterministically for the same candidate.
                        com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan
                                .publishDisplayOnly(displayHolder, plan);
                        displaySwapped = true;
                        CurrencyRegistry candidate = plan.candidate();
                        economy.replaceCurrencyDisplay(candidate);
                        economySwapped = true;
                        currencies = candidate;
                    }
                    // The layout candidate was fully parsed before the swap window.
                    // Every tracked view is rebuilt from the new layout and reopened
                    // in place, so a player with a bank view open sees the new layout
                    // without reopening it. A view that cannot be rebuilt or reopened
                    // is closed and its player is told, both on a best-effort basis.
                    // Opens serialize against the swap inside the session, so no open
                    // can leak a stale view past it.
                    if (bankGui != null && layout != null) {
                        currentLayout = layout;
                        layoutSwapped = true;
                        bankGui.applyLayout(layout);
                        if (bankForms != null) {
                            bankForms.invalidateAll();
                        }
                    }
                } catch (Throwable failure) {
                    // Rollback is reference swaps back to the snapshots above: the
                    // display-only pair passes the guard in both directions, so restoring
                    // cannot fail and the rethrow keeps the outer reload reporting failure.
                    // The layout restore itself is tolerant: even if swapping the resolver
                    // back throws, the layout reference is still restored so later opens
                    // cannot keep reading the failed candidate.
                    if (layoutSwapped && bankGui != null && oldLayout != null) {
                        // Restore the previous reference first so later opens
                        // cannot keep reading the failed candidate, then reopen
                        // every tracked view with it. The reopen is tolerant:
                        // a view that cannot be rebuilt has its tracking dropped
                        // and is closed on a best-effort basis. Best-effort: it
                        // never masks the original failure, which stays recorded below.
                        currentLayout = oldLayout;
                        try {
                            bankGui.applyLayout(oldLayout);
                        } catch (Throwable rollbackFailure) {
                            // A stubbed test double may throw the same instance twice;
                            // self-suppression is illegal, so only attach distinct causes.
                            if (rollbackFailure != failure) {
                                failure.addSuppressed(rollbackFailure);
                            }
                        }
                    }
                    if (economySwapped) {
                        economy.replaceCurrencyDisplay(oldDisplay);
                    }
                    if (displaySwapped) {
                        displayHolder.publish(oldDisplay);
                    }
                    currencies = oldCurrencies;
                    throw failure;
                }
            }
        };
    }

    /**
     * Fail-safe floor for the withdraw dispatch timeout. A zero or negative configured
     * value would arm every bounded wait with a zero delay and time out every withdraw
     * immediately, so the effective timeout is never shorter than one second; positive
     * configured values pass through unchanged.
     */
    static Duration effectiveDispatchTimeout(int configuredSeconds) {
        return Duration.ofSeconds(Math.max(1, configuredSeconds));
    }

    /**
     * Register the leaderboard invalidation listener on the transaction publisher and return it
     * so the caller can unregister on shutdown. The listener observes post-commit transactions
     * only: cancelled or uncommitted operations never clear the cache, and a failed observer
     * can never change the committed result. The committed record carries no currency id, so
     * the listener drops every cached currency ranking; that is intentionally coarser than a
     * per-currency invalidation but keeps the listener free of IO and of any Bukkit access, so
     * it is safe to run inline on whatever thread commits the transaction.
     */
    static CommittedTransactionListener wireLeaderboardInvalidation(
            InMemoryTransactionEventPublisher publisher, LeaderboardService leaderboard) {
        CommittedTransactionListener listener = transaction -> leaderboard.invalidateAll();
        publisher.registerPostCommit(listener);
        return listener;
    }

    /**
     * Post-restore cache reset: a successful restore replaces the world underneath the
     * caches, so both the shared leaderboard rankings and every cached balance are
     * dropped. Balance entries that survive would keep serving pre-restore values, and a
     * pre-restore background prime settling afterwards is discarded by the service's
     * stamp fence. Null-safe for unit seams where either side is not built yet.
     */
    static Runnable restoreCacheReset(LeaderboardService leaderboard, EconomyService economy) {
        return () -> {
            if (leaderboard != null) {
                leaderboard.invalidateAll();
            }
            EconomyService live = economy;
            if (live != null) {
                live.invalidateAllBalances();
            }
        };
    }

    private Object value(String path) {
        return config.getConfig(path);
    }

    private String string(String path, String fallback) {
        Object value = value(path);
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean bool(String path, boolean fallback) {
        Object value = value(path);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private int integer(String path, int fallback) {
        Object value = value(path);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private double decimal(String path, double fallback) {
        Object value = value(path);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private final class RuntimeModule extends AceLibModule {
        RuntimeModule() {
            super(new AceLibAccess(plugin));
        }

        @Override
        public String name() {
            return "acelib-runtime";
        }

        @Override
        protected void onStart(ResourceOwner resources, AceLibApi api, SafeScheduler scheduler,
                               SafeEventRegistry events) {
            CompositionRoot.this.scheduler = scheduler;
            // The shared GuiService listener is owned by AceLib: AceLibPlugin registers it
            // on enable and removes it with HandlerList#unregisterAll on disable/reload.
            // Registering it here again would add a redundant registration, and removing it
            // on stop would also wipe AceLib's own registration (unregisterAll removes every
            // registration of that instance), breaking every other plugin that shares the
            // same AceLib instance. This module therefore never touches the shared listener;
            // session calls go through a freshly resolved GuiService instead.
            // Bedrock click-fallback wiring: hand the ready facade to the message
            // adapter so player chat degrades click actions for Bedrock clients.
            // Floodgate absent (or any lookup failure) keeps the original
            // Components — attach is null-safe and reload preserves it.
            try {
                BedrockService bedrock = api.getBedrockService();
                config.attachBedrockService(bedrock);
            } catch (Throwable failure) {
                plugin.getLogger().warning("Bedrock lookup unavailable, messages keep Java behaviour: "
                        + failure.getClass().getSimpleName());
                config.attachBedrockService(null);
            }
        }

        @Override
        protected void onStop() {
            // The scheduler reference is ours to drop; the shared GuiService and its
            // listener stay with AceLib, so nothing here may shut them down or remove them.
            scheduler = null;
        }
    }

    private final class PlayerSessionListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            ioExecutor.execute(() -> {
                economy.createAccount(event.getPlayer().getUniqueId(), event.getPlayer().getName());
                scheduler.runForPlayer(event.getPlayer(), () -> sessions.login(
                        event.getPlayer().getUniqueId(), event.getPlayer()));
            });
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            try {
                sessions.quit(event.getPlayer().getUniqueId(), SESSION_SHUTDOWN_DEADLINE_MILLIS);
            } finally {
                // Offline invalidation for the balance read cache: a later synchronous query
                // for this owner must miss (safe default) instead of serving the departed
                // player's balance until the next persisted read re-primes it. In a finally
                // block so even a capacity refusal from the quit path above cannot skip it:
                // the session itself is already cleaned up when that refusal throws.
                // An EconomyService write that started before this invalidation carries a
                // stale stamp, so even if it persists afterwards its cached value is
                // discarded rather than resurrected.
                if (economy != null) {
                    economy.invalidateBalance(event.getPlayer().getUniqueId());
                }
            }
        }
    }

    private static final class NamedModule implements LifecycleModule {
        private final String name;
        private final ModuleAction start;
        private final ModuleAction stop;

        NamedModule(String name, ModuleAction start, ModuleAction stop) {
            this.name = name;
            this.start = start;
            this.stop = stop;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void start(ResourceOwner resources) throws Exception {
            start.run(resources);
        }

        @Override
        public void stop() throws Exception {
            stop.run(null);
        }
    }

    @FunctionalInterface
    private interface ModuleAction {
        void run(ResourceOwner resources) throws Exception;
    }

    // -----------------------------------------------------------------------
    // Discord wiring seam — package-private so unit tests can drive the same
    // path the production {@link #startApplication} uses, without booting
    // Bukkit or running a real storage start. The two overloads mirror the
    // DiscordWiring.wire signatures; the simple form uses the production
    // defaults (HttpClient-backed transport factory + logger diagnostics).
    // -----------------------------------------------------------------------

    static DiscordWiring.Outcome wireDiscord(
            TransactionRepository transactions,
            ExecutorService ioExecutor,
            InMemoryTransactionEventPublisher publisher,
            ResourceOwner resources,
            boolean enabled,
            String webhookUrl,
            Logger logger) {
        return wireDiscord(transactions, ioExecutor, publisher, resources, enabled, webhookUrl, logger,
                DiscordWiring.defaultTransportFactory(), logger::warning);
    }

    static DiscordWiring.Outcome wireDiscord(
            TransactionRepository transactions,
            ExecutorService ioExecutor,
            InMemoryTransactionEventPublisher publisher,
            ResourceOwner resources,
            boolean enabled,
            String webhookUrl,
            Logger logger,
            DiscordWiring.TransportFactory transportFactory,
            Consumer<String> diagnosticsSink) {
        return DiscordWiring.wire(transactions, ioExecutor, publisher, enabled, webhookUrl, logger,
                resources, transportFactory, diagnosticsSink);
    }
}
