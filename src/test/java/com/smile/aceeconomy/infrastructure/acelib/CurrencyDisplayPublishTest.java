package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.integration.placeholder.PlaceholderResolver;
import com.smile.aceeconomy.infrastructure.integration.vault.VaultEconomyProvider;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Display-publish contract: a display-only reload moves every display surface together through
 * one shared holder. Concurrent requests only ever see the whole old registry or the whole new
 * one, and a rejected candidate throws before anything is published, so a failed apply can
 * never leave holders on different versions.
 */
class CurrencyDisplayPublishTest {

    private final EconomyTestHarness harness = new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
    private final EconomyApi api = new EconomyApiImpl(harness.service(), harness.publisher());

    private static CurrencyRegistry displayOnlyVariant() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "€", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    private static CurrencyRegistry structurallyDiverged() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 3, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    private static CurrencyReloadPlan.Classification displayOnlyPlan(CurrencyRegistry candidate) {
        return new CurrencyReloadPlan.Classification(
                CurrencyReloadPlan.Disposition.DISPLAY_ONLY, candidate,
                List.of("currency display changed: dollar"));
    }

    private OfflinePlayer anyone() {
        OfflinePlayer p = mock(OfflinePlayer.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    private void assertAllSurfacesShow(String name, String symbol,
            VaultEconomyProvider vault, PlaceholderResolver papi) {
        OfflinePlayer player = anyone();
        assertEquals(name, vault.currencyNamePlural());
        assertEquals(name, papi.resolve(player, "currency_name_dollar"));
        assertEquals(symbol, papi.resolve(player, "currency_symbol_dollar"));
        assertTrue(vault.format(0.0).startsWith(symbol),
                "vault format must use the same symbol: " + vault.format(0.0));
    }

    @Test
    void sharedPublishMovesAllSurfacesTogether() {
        CurrencyRegistry live = harness.currencies();
        CurrencyDisplayHolder shared = new CurrencyDisplayHolder(live);
        VaultEconomyProvider vault = new VaultEconomyProvider(api, shared);
        PlaceholderResolver papi = new PlaceholderResolver(api, shared);
        assertAllSurfacesShow("Dollar", "$", vault, papi);

        CurrencyReloadPlan.publishDisplayOnly(shared, displayOnlyPlan(displayOnlyVariant()));

        assertAllSurfacesShow("Dollar", "€", vault, papi);
    }

    @Test
    void structuralPlanThrowsWithoutPublishingAnything() {
        CurrencyRegistry live = harness.currencies();
        CurrencyDisplayHolder shared = new CurrencyDisplayHolder(live);
        VaultEconomyProvider vault = new VaultEconomyProvider(api, shared);
        PlaceholderResolver papi = new PlaceholderResolver(api, shared);

        CurrencyReloadPlan.Classification dangerous = new CurrencyReloadPlan.Classification(
                CurrencyReloadPlan.Disposition.DANGEROUS, structurallyDiverged(),
                List.of("currency scale changed: dollar 2 -> 3 (restart required)"));
        assertThrows(IllegalArgumentException.class,
                () -> CurrencyReloadPlan.publishDisplayOnly(shared, dangerous));

        assertSame(live, shared.get(), "rejected publish must leave the holder untouched");
        assertAllSurfacesShow("Dollar", "$", vault, papi);
    }

    @Test
    void concurrentReadsSeeOnlyWholeOldOrWholeNew() throws Exception {
        CurrencyRegistry oldRegistry = harness.currencies();
        CurrencyRegistry newRegistry = displayOnlyVariant();
        CurrencyDisplayHolder shared = new CurrencyDisplayHolder(oldRegistry);
        int readers = 4;
        int flips = 100;
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        try {
            Future<?> publisher = pool.submit(() -> {
                try {
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    for (int i = 0; i < flips && failure.get() == null; i++) {
                        shared.publish(i % 2 == 0 ? newRegistry : oldRegistry);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            List<Future<?>> jobs = new java.util.ArrayList<>();
            jobs.add(publisher);
            for (int r = 0; r < readers; r++) {
                jobs.add(pool.submit(() -> {
                    try {
                        assertTrue(go.await(10, TimeUnit.SECONDS));
                        for (int i = 0; i < 5_000 && failure.get() == null; i++) {
                            // One sample, one registry: every derived value must belong
                            // to the same complete publish, old or new, never mixed.
                            CurrencyRegistry seen = shared.get();
                            String symbol = seen.get("dollar").symbol();
                            String name = seen.get("dollar").displayName();
                            String tokenSymbol = seen.get("token").symbol();
                            boolean allOld = symbol.equals("$") && name.equals("Dollar")
                                    && tokenSymbol.equals("T");
                            boolean allNew = symbol.equals("€") && name.equals("Dollar")
                                    && tokenSymbol.equals("T");
                            if (!allOld && !allNew) {
                                throw new AssertionError("mixed display versions observed: "
                                        + name + "/" + symbol + "/" + tokenSymbol);
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
                throw new AssertionError("concurrent read observed mixed versions", failure.get());
            }
        } finally {
            pool.shutdownNow();
        }
        // Every single surface only ever renders one complete registry as well.
        VaultEconomyProvider vault = new VaultEconomyProvider(api, shared);
        PlaceholderResolver papi = new PlaceholderResolver(api, shared);
        String papiSymbol = papi.resolve(anyone(), "currency_symbol_dollar");
        assertTrue(papiSymbol.equals("$") || papiSymbol.equals("€"));
        assertTrue(vault.format(0.0).startsWith("$") || vault.format(0.0).startsWith("€"));
    }
}
