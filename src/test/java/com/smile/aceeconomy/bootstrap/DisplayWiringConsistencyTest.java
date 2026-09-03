package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderResolver;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultEconomyProvider;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring contract: every display surface (commands, Vault, placeholders) reads through
 * one shared holder, so a validated publish moves them together and concurrent readers
 * only ever observe a whole old or whole new registry on every surface.
 */
class DisplayWiringConsistencyTest {

    private final EconomyTestHarness harness =
            new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));

    private static CurrencyRegistry newRegistry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "€", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    private static OfflinePlayer anyone() {
        OfflinePlayer p = Mockito.mock(OfflinePlayer.class);
        Mockito.when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    private void assertAllSurfacesShow(String symbol,
            VaultEconomyProvider vault, PlaceholderResolver papi,
            ProductionAdapters.Economy commands) {
        OfflinePlayer player = anyone();
        assertTrue(vault.format(0.0).startsWith(symbol),
                "vault must format with the published symbol: " + vault.format(0.0));
        assertEquals(symbol, papi.resolve(player, "currency_symbol_dollar"));
        assertEquals(symbol, commands.resolveCurrency("dollar").orElseThrow().symbol());
    }

    @Test
    void sharedPublishMovesVaultPapiAndCommandsTogether() {
        CurrencyDisplayHolder shared = new CurrencyDisplayHolder(harness.currencies());
        var api = new EconomyApiImpl(harness.service(), harness.publisher());
        VaultEconomyProvider vault = new VaultEconomyProvider(api, shared);
        PlaceholderResolver papi = new PlaceholderResolver(api, shared);
        ProductionAdapters.Economy commands =
                new ProductionAdapters.Economy(api, shared, Runnable::run);

        assertAllSurfacesShow("$", vault, papi, commands);

        CurrencyReloadPlan.publishDisplayOnly(shared,
                new CurrencyReloadPlan.Classification(
                        CurrencyReloadPlan.Disposition.DISPLAY_ONLY, newRegistry(),
                        List.of("currency display changed: dollar")));

        assertAllSurfacesShow("€", vault, papi, commands);
    }

    @Test
    void concurrentMultiSurfaceReadsSeeOnlyWholeVersions() throws Exception {
        CurrencyRegistry oldReg = harness.currencies();
        CurrencyRegistry newReg = newRegistry();
        CurrencyDisplayHolder shared = new CurrencyDisplayHolder(oldReg);
        var api = new EconomyApiImpl(harness.service(), harness.publisher());
        VaultEconomyProvider vault = new VaultEconomyProvider(api, shared);
        PlaceholderResolver papi = new PlaceholderResolver(api, shared);
        ProductionAdapters.Economy commands =
                new ProductionAdapters.Economy(api, shared, Runnable::run);

        int readersPerSurface = 2;
        int flips = 100;
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(readersPerSurface * 3 + 1);
        try {
            Future<?> publisher = pool.submit(() -> {
                try {
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    for (int i = 0; i < flips && failure.get() == null; i++) {
                        shared.publish(i % 2 == 0 ? newReg : oldReg);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            List<Future<?>> jobs = new java.util.ArrayList<>();
            jobs.add(publisher);
            for (int r = 0; r < readersPerSurface; r++) {
                jobs.add(pool.submit(() -> {
                    try {
                        assertTrue(go.await(10, TimeUnit.SECONDS));
                        for (int i = 0; i < 2_000 && failure.get() == null; i++) {
                            String formatted = vault.format(0.0);
                            if (!formatted.startsWith("$") && !formatted.startsWith("€")) {
                                throw new AssertionError("vault saw mixed display: " + formatted);
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
                jobs.add(pool.submit(() -> {
                    try {
                        assertTrue(go.await(10, TimeUnit.SECONDS));
                        OfflinePlayer player = anyone();
                        for (int i = 0; i < 2_000 && failure.get() == null; i++) {
                            String symbol = papi.resolve(player, "currency_symbol_dollar");
                            if (!"$".equals(symbol) && !"€".equals(symbol)) {
                                throw new AssertionError("papi saw mixed display: " + symbol);
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
                jobs.add(pool.submit(() -> {
                    try {
                        assertTrue(go.await(10, TimeUnit.SECONDS));
                        for (int i = 0; i < 2_000 && failure.get() == null; i++) {
                            String symbol = commands.resolveCurrency("dollar")
                                    .orElseThrow().symbol();
                            if (!"$".equals(symbol) && !"€".equals(symbol)) {
                                throw new AssertionError("command saw mixed display: " + symbol);
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }
            go.countDown();
            for (Future<?> job : jobs) {
                job.get(30, TimeUnit.SECONDS);
            }
            if (failure.get() != null) {
                throw new AssertionError("multi-surface read saw mixed versions", failure.get());
            }
        } finally {
            pool.shutdownNow();
        }
        // After the flip storm every surface converges on the final publish together.
        shared.publish(newReg);
        assertAllSurfacesShow("€", vault, papi, commands);
    }
}
