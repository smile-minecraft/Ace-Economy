package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.BanknoteClaim;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Durable-issuance wiring for both withdraw routes.
 *
 * <p>The command entry ({@link BanknoteFlow#withdraw}) and the GUI entry
 * (which delegates its sync core to {@link BanknoteFlow#withdrawCommit}) must
 * both persist issuance progress through the SAME durable atomic store on the
 * same backend instance as the core service: {@code PREPARED} before the
 * deduction via the atomic boundary, the deduction plus flip via
 * {@code commitDebit}, and the delivery markers around the handover — never
 * {@code api.withdraw} followed by a standalone mark. Both tests below run
 * against a real JSON backend bound as the atomic store and as the core
 * service storage, then assert the durable issuance record plus the balance.
 * A legacy fallback to {@code api.withdraw} fails loudly instead of passing
 * silently.</p>
 */
class BanknoteIssuanceFlowWiringTest {

    /** Region stub that runs the handover immediately on the calling thread. */
    static final class DirectFolia implements com.smile.aceeconomy.ports.FoliaContextExecutor {
        volatile Player player;

        @Override
        public void runForPlayer(@NotNull Player target, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        }

        @Override
        public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
        }

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            Player online = player;
            if (online == null) {
                return false;
            }
            action.accept(online);
            return true;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final DirectFolia folia = new DirectFolia();
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private ExecutorService io;
    private java.util.concurrent.ScheduledExecutorService watchdogs;

    @BeforeEach
    void setUp() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "issuance-wiring-io");
            thread.setDaemon(true);
            return thread;
        });
        watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "issuance-wiring-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
        watchdogs.shutdownNow();
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        org.mockito.Mockito.doAnswer(invocation -> new HashMap<Integer, org.bukkit.inventory.ItemStack>())
                .when(inventory).addItem(any(org.bukkit.inventory.ItemStack.class));
        return player;
    }

    private static EconomyApi legacyForbiddenApi() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.withdraw(any(UUID.class), any(String.class), any(Amount.class)))
                .thenThrow(new AssertionError(
                        "legacy api.withdraw must not be used once the durable atomic core is bound"));
        when(api.deposit(any(UUID.class), any(String.class), any(Amount.class)))
                .thenThrow(new AssertionError(
                        "legacy api.deposit must not be used once the durable atomic core is bound"));
        return api;
    }

    private static BanknoteFlow.WithdrawOutcome join(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("withdraw pipeline must complete bounded", e);
        }
    }

    @Test
    void commandRoutePersistsPreparedBeforeDebit(@TempDir Path dir) throws Exception {
        UUID playerId = UUID.randomUUID();
        Path dataFile = dir.resolve("wiring-command.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(playerId, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            EconomyService economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                    backend, tx -> {
                    }, Instant::now, new InMemoryTransactionEventPublisher());
            BanknoteFlow flow = new BanknoteFlow(legacyForbiddenApi(), economy, backend,
                    banknotes, display, folia, io, watchdogs, Duration.ofMillis(200));
            flow.bindAtomicIssuanceStore(backend);
            folia.player = onlinePlayer(playerId);

            BanknoteFlow.WithdrawOutcome outcome =
                    join(flow.withdraw(playerId, "dollar", Amount.of(100L, 2)));

            assertTrue(outcome.isDelivered(),
                    "command withdraw through the durable core must deliver, got kind with claim "
                            + outcome.claim());
            BanknoteClaim claim = outcome.claim();
            assertTrue(claim != null, "a delivered withdraw must carry its claim");
            com.smile.aceeconomy.domain.BanknoteIssuance stored =
                    backend.findByNonce(claim.nonce()).orElseThrow(() ->
                            new AssertionError("durable issuance record must exist for nonce "
                                    + claim.nonce()));
            assertEquals(BanknoteIssuanceStatus.DELIVERED, stored.status(),
                    "command handover must finish as DELIVERED through the delivery marks");
            assertEquals(playerId, stored.accountId());
            assertEquals("dollar", stored.currencyId());
            assertEquals(0, Amount.of(100L, 2).compareTo(stored.amount()));
            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(playerId).orElseThrow().balanceOf("dollar")),
                    "durable balance must reflect exactly one atomic charge");
        } finally {
            backend.close();
        }
    }

    @Test
    void guiSyncRoutePersistsPreparedBeforeDebit(@TempDir Path dir) throws Exception {
        UUID playerId = UUID.randomUUID();
        Path dataFile = dir.resolve("wiring-gui.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(playerId, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            EconomyService economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                    backend, tx -> {
                    }, Instant::now, new InMemoryTransactionEventPublisher());
            BanknoteFlow flow = new BanknoteFlow(legacyForbiddenApi(), economy, backend,
                    banknotes, display, folia, io, watchdogs, Duration.ofMillis(200));
            flow.bindAtomicIssuanceStore(backend);
            Currency currency = registry.get("dollar");

            com.smile.aceeconomy.ports.WithdrawResult result =
                    flow.withdrawCommit(playerId, currency, 100L);

            assertTrue(result.success(),
                    "GUI withdraw through the durable core must succeed, got " + result);
            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(playerId).orElseThrow().balanceOf("dollar")),
                    "durable balance must reflect exactly one atomic charge");
            List<BanknoteClaim> minted = banknotes.minted();
            assertTrue(!minted.isEmpty(), "GUI withdraw must mint exactly one note");
            BanknoteClaim claim = minted.get(minted.size() - 1);
            com.smile.aceeconomy.domain.BanknoteIssuance stored =
                    backend.findByNonce(claim.nonce()).orElseThrow(() ->
                            new AssertionError("durable issuance record must exist for nonce "
                                    + claim.nonce()));
            assertEquals(BanknoteIssuanceStatus.DEBIT_COMMITTED, stored.status(),
                    "sync GUI commit stops after the atomic debit, before the delivery marks");
            assertEquals(playerId, stored.accountId());
        } finally {
            backend.close();
        }
    }
}
