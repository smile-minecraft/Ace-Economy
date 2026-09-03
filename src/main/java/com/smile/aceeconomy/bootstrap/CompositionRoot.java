package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.command.BukkitCommandBridge;
import com.smile.acelib.command.BukkitReplySink;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.event.SafeEventRegistry;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.aceeconomy.acelib.AceLibAccess;
import com.smile.aceeconomy.acelib.AceLibModule;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.commands.v2.CommandServices;
import com.smile.aceeconomy.commands.v2.MainCommandAliasPolicy;
import com.smile.aceeconomy.commands.v2.V2CommandRegistry;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.SafeSchedulerFoliaContext;
import com.smile.aceeconomy.infrastructure.integration.acelib.AceLibExternalServiceReadiness;
import com.smile.aceeconomy.infrastructure.integration.acelib.ExternalIntegrationCoordinator;
import com.smile.aceeconomy.infrastructure.integration.acelib.IntegrationModule;
import com.smile.aceeconomy.infrastructure.integration.placeholder.AceEconomyExpansion;
import com.smile.aceeconomy.infrastructure.integration.placeholder.BukkitPlaceholderRegistration;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderIntegrationModule;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderLifecycle;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderResolver;
import com.smile.aceeconomy.infrastructure.integration.vault.BukkitVaultRegistration;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultEconomyLifecycle;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultEconomyProvider;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultIntegrationModule;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
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
import com.smile.aceeconomy.gui.v2.BankGuiAction;
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
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The single v2 production composition root. Modules are deliberately registered in dependency order;
 * {@link ModuleLifecycle} supplies reverse stop order, rollback after partial start, and idempotency.
 */
public final class CompositionRoot {
    private static final long SESSION_SHUTDOWN_DEADLINE_MILLIS = 5_000L;

    private final JavaPlugin plugin;
    private final ModuleLifecycle lifecycle = new ModuleLifecycle();
    private final ConfigLangAdapter config;

    private ExecutorService ioExecutor;
    private PersistenceLifecycle persistence;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private AtomicReversalStore reversals;
    private AtomicRedemptionStore redemptions;
    private NonceStore nonces;
    private CurrencyRegistry currencies;
    private EconomyService economy;
    private EconomyApiImpl api;
    private InMemoryTransactionEventPublisher publisher;
    private SafeScheduler scheduler;
    private GuiService runtimeGui;
    private FoliaContextExecutor folia;
    private PlayerSessionManager sessions;
    private V2BankGuiSession bankGui;
    private CommandRegistryImpl commandRegistry;
    private ExternalIntegrationCoordinator integrations;
    private V2BanknoteFactory banknotes;
    private RollbackService rollbacks;
    private BanknoteValidator banknoteValidator;
    // Shared with the PlaceholderAPI resolver so /baltop and PAPI rank/top placeholders
    // always read the same cached ranking (same instance, same TTL).
    private LeaderboardCache leaderboardCache;
    private Duration leaderboardTtl;

    public CompositionRoot(JavaPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.config = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
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
        lifecycle.add(new NamedModule("configuration", resources -> config.load(), resources -> { }));
        lifecycle.add(new NamedModule("persistence", this::startPersistence, resources -> stopPersistence()));
        lifecycle.add(new NamedModule("application", this::startApplication, resources -> { }));
        lifecycle.add(new RuntimeModule());
        lifecycle.add(new NamedModule("sessions", this::startSessions, resources -> stopSessions()));
        lifecycle.add(new NamedModule("presentation", this::startPresentation, resources -> stopPresentation()));
        lifecycle.add(new NamedModule("integrations", this::startIntegrations, resources -> stopIntegrations()));
    }

    private void startPersistence(ResourceOwner resources) throws Exception {
        ioExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "aceeconomy-v2-io");
            thread.setDaemon(true);
            return thread;
        });
        resources.register(() -> ioExecutor.shutdown());

        // Config → typed StorageConfig → factory. The factory owns the JSON / SQLite /
        // MySQL backend selection and the connection / pool lifecycle. On any failure it
        // releases whatever it acquired and propagates the original exception, so the
        // ModuleLifecycle rollback tears down ioExecutor as usual.
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

        // Discord notification wiring: best-effort, post-commit via AuditSink decorator.
        // Active only when discord.enabled=true AND the webhook URL passes strict validation
        // (absolute, scheme http/https, host required, no userinfo, legal port). A failed
        // validation degrades gracefully: a fixed diagnostic is logged (the URL is never
        // echoed) and the audit sink stays the plain PersistentAuditSink.
        // Notification failures never become AuditException, so transaction results and
        // audit-failure semantics remain unpolluted.
        //
        // The wiring is delegated to the package-private {@link #wireDiscord} seam so tests
        // can drive the same path with fake dependencies (transactions, executor, transport
        // factory, diagnostics consumer) without booting Bukkit.
        DiscordWiring.Outcome discord = wireDiscord(
                transactions,
                ioExecutor,
                resources,
                bool("discord.enabled", false),
                string("discord.webhook-url", ""),
                plugin.getLogger());
        AuditSink auditSink = discord.auditSink();

        economy = new EconomyService(currencies, debt, startBalance, accounts,
                auditSink, clock, publisher);
        api = new EconomyApiImpl(economy, publisher);

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
                        }));
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
        banknotes = new V2BanknoteFactory();
        // The bank GUI use case binds to the SAME durable validator / redemption store and the
        // application economy service (lock, pre-commit, debt policy) so every redeem goes
        // through the prepared atomic path; no in-memory guard appears in this graph.
        ProductionAdapters.BankUseCase bankUseCase =
                new ProductionAdapters.BankUseCase(api, economy, currencies, banknotes,
                        banknoteValidator, redemptions);
        GuiService guiService = requireApi().getGuiService();
        bankGui = new V2BankGuiSession(guiService, folia, bankUseCase, slot -> switch (slot) {
            case 4 -> BankGuiAction.deposit();
            case 11 -> BankGuiAction.withdraw(100L);
            case 13 -> BankGuiAction.withdraw(500L);
            case 15 -> BankGuiAction.close();
            default -> BankGuiAction.none();
        });
        // Right-click redemption reuses the same atomic bank use case as the GUI deposit button
        // (durable nonce consumption + credit commit together); the listener owns its click-time
        // snapshot and region-context removal, so no other slice needs to know about interact events.
        BanknoteRedeemListener redeemListener = new BanknoteRedeemListener(
                bankUseCase, banknotes, folia, config, plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(redeemListener, plugin);
        resources.register(() -> HandlerList.unregisterAll(redeemListener));

        ProductionAdapters.Economy economyCommands =
                new ProductionAdapters.Economy(api, currencies, ioExecutor);
        ProductionAdapters.Withdrawals withdrawalCommands =
                new ProductionAdapters.Withdrawals(api, currencies, banknotes, ioExecutor);
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
        ProductionAdapters.Bank bankCommands = new ProductionAdapters.Bank(bankGui, ioExecutor);
        ProductionAdapters.Admin adminCommands = new ProductionAdapters.Admin(api, ioExecutor,
                () -> {
                    boolean ok = config.reload().success();
                    // Reload invalidation: configuration (currencies, balances) may have changed,
                    // so cached balances are dropped and re-primed by later persisted reads.
                    if (ok && economy != null) {
                        economy.invalidateAllBalances();
                    }
                    return ok;
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
        // gated on no online players plus a pre-restore safety snapshot, and a success clears
        // the shared leaderboard cache before the operator restarts the server.
        BackupRestoreService backupRestoreService = new BackupRestoreService(
                persistence,
                plugin.getDataFolder().toPath(),
                () -> !Bukkit.getOnlinePlayers().isEmpty(),
                () -> Set.copyOf(currencies.all().stream().map(Currency::id).toList()),
                leaderboardService::invalidateAll);
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
                });
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
        commandRegistry = new CommandRegistryImpl(new BukkitReplySink(plugin));
        v2Commands.register(commandRegistry);
        for (var spec : v2Commands.specs()) {
            new BukkitCommandBridge(commandRegistry).attach(plugin, spec.name());
        }
        resources.register(commandRegistry::onPluginDisable);
    }

    private void stopPresentation() {
        if (commandRegistry != null) {
            commandRegistry.onPluginDisable();
        }
    }

    private void startIntegrations(ResourceOwner resources) {
        List<IntegrationModule> modules = new ArrayList<>();
        AceLibApi ready = requireApi();
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomyProvider provider = new VaultEconomyProvider(api, currencies);
            modules.add(new VaultIntegrationModule("vault", "vault",
                    new VaultEconomyLifecycle(new BukkitVaultRegistration(plugin, ServicePriority.Normal), provider)));
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            // The resolver shares the leaderboard cache instance (and TTL) with the
            // /baltop command path, so PAPI rank/top placeholders sort exactly like
            // the command output. Placeholder callbacks must never do storage I/O,
            // so a missing snapshot simply resolves to null here.
            PlaceholderResolver resolver;
            if (leaderboardCache != null && leaderboardTtl != null) {
                Clock leaderboardClock = () -> Instant.now();
                resolver = new PlaceholderResolver(api, currencies, leaderboardCache,
                        leaderboardTtl, leaderboardClock);
            } else {
                resolver = new PlaceholderResolver(api, currencies);
            }
            AceEconomyExpansion expansion = new AceEconomyExpansion(resolver, plugin.getDescription().getVersion());
            modules.add(new PlaceholderIntegrationModule("placeholderapi", "placeholderapi",
                    new PlaceholderLifecycle(new BukkitPlaceholderRegistration(), expansion)));
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
            runtimeGui = api.getGuiService();
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
            if (runtimeGui.getListener() != null) {
                Listener listener = runtimeGui.getListener();
                Bukkit.getPluginManager().registerEvents(listener, plugin);
                resources.register(() -> HandlerList.unregisterAll(listener));
            }
        }

        @Override
        protected void onStop() {
            if (runtimeGui != null) {
                runtimeGui.shutdown();
                runtimeGui = null;
            }
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
            sessions.quit(event.getPlayer().getUniqueId(), SESSION_SHUTDOWN_DEADLINE_MILLIS);
            // Offline invalidation for the balance read cache: a later synchronous query
            // for this owner must miss (safe default) instead of serving the departed
            // player's balance until the next persisted read re-primes it. An EconomyService
            // write that started before this invalidation carries a stale stamp, so even if
            // it persists afterwards its cached value is discarded rather than resurrected.
            if (economy != null) {
                economy.invalidateBalance(event.getPlayer().getUniqueId());
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
            ResourceOwner resources,
            boolean enabled,
            String webhookUrl,
            Logger logger) {
        return wireDiscord(transactions, ioExecutor, resources, enabled, webhookUrl, logger,
                DiscordWiring.defaultTransportFactory(), logger::warning);
    }

    static DiscordWiring.Outcome wireDiscord(
            TransactionRepository transactions,
            ExecutorService ioExecutor,
            ResourceOwner resources,
            boolean enabled,
            String webhookUrl,
            Logger logger,
            DiscordWiring.TransportFactory transportFactory,
            Consumer<String> diagnosticsSink) {
        return DiscordWiring.wire(transactions, ioExecutor, enabled, webhookUrl, logger,
                resources, transportFactory, diagnosticsSink);
    }
}
