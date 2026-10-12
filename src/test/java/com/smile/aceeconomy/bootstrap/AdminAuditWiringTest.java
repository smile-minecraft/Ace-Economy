package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring for the audited admin mutations: the adapter must carry
 * the operator and reason from the command layer into the stored record
 * through the single atomic commit.
 * A mock-only check would pass while the live path silently drops the audit,
 * so these cases run the real service on a real JSON backend and read the
 * persisted record back.
 */
class AdminAuditWiringTest {

    private static Amount coins(String value) {
        java.math.BigDecimal bd = new java.math.BigDecimal(value);
        return Amount.of(bd, bd.scale());
    }

    private record Wiring(JsonPersistenceBackend backend,
                          InMemoryTransactionEventPublisher publisher,
                          ProductionAdapters.Admin admin) { }

    private Wiring wiring(Path dir, String fileName) {
        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve(fileName));
        backend.initialize();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService service = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), backend, new PersistentAuditSink(backend),
                new FixedClock(), publisher);
        ProductionAdapters.Admin admin = new ProductionAdapters.Admin(
                service, Runnable::run,
                () -> {
                    throw new AssertionError("reload must not be called by a mutation");
                });
        return new Wiring(backend, publisher, admin);
    }

    @Test
    void adminGiveCarriesAuditThroughTheProductionAdapter(@TempDir Path dir) throws Exception {
        Wiring wiring = wiring(dir, "give.json");
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            wiring.backend().create(target, "Alex", Map.of("dollar", coins("0.00")));

            EconomyResult<Amount> result = wiring.admin()
                    .give(target, "dollar", coins("10.00"),
                            AdminAudit.player(operator, "Morgan", "harbor-check"))
                    .join();

            assertTrue(result.isSuccess(), "audited give must succeed, got " + result);
            assertEquals(0, coins("10.00").compareTo(
                    wiring.backend().load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = wiring.backend().loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("Morgan", rows.get(0).operatorName());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            wiring.backend().close();
        }
    }

    @Test
    void adminTakeCarriesAuditThroughTheProductionAdapter(@TempDir Path dir) throws Exception {
        Wiring wiring = wiring(dir, "take.json");
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            wiring.backend().create(target, "Alex", Map.of("dollar", coins("10.00")));

            EconomyResult<Amount> result = wiring.admin()
                    .take(target, "dollar", coins("4.00"),
                            AdminAudit.player(operator, "Morgan", "harbor-check"))
                    .join();

            assertTrue(result.isSuccess(), "audited take must succeed, got " + result);
            assertEquals(0, coins("6.00").compareTo(
                    wiring.backend().load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = wiring.backend().loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            wiring.backend().close();
        }
    }

    @Test
    void adminSetCarriesAuditThroughTheProductionAdapter(@TempDir Path dir) throws Exception {
        Wiring wiring = wiring(dir, "set.json");
        try {
            UUID target = UUID.randomUUID();
            wiring.backend().create(target, "Alex", Map.of("dollar", coins("0.00")));

            EconomyResult<Amount> result = wiring.admin()
                    .setBalance(target, "dollar", coins("7.00"),
                            AdminAudit.console("harbor-check"))
                    .join();

            assertTrue(result.isSuccess(), "audited set must succeed, got " + result);
            assertEquals(0, coins("7.00").compareTo(
                    wiring.backend().load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = wiring.backend().loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME,
                    rows.get(0).operatorName());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            wiring.backend().close();
        }
    }
}
