package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Old-data compatibility plus issuance-section fail-fast (mixed green/red).
 *
 * <p>Green guards (must keep passing): files written before issuance tracking
 * load with empty progress, and old v2 notes redeem exactly as before — new
 * persistence must never strand existing value. Red goals (fail until phase 2
 * lands the additive section): a malformed {@code issuances} section must fail
 * fast at both the model and the backend level instead of being silently
 * ignored, otherwise a corrupted recovery queue could be mistaken for empty.
 */
class BanknoteIssuanceCompatTest {

    @Test
    void oldJsonWithoutIssuancesLoadsEmpty(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("old.json");
        Files.writeString(dataFile,
                "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[]}");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        backend.close();
    }

    @Test
    void oldV2NoteStillRedeems(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("old-redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        CurrencyRegistry registry = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true)));
        EconomyService economy = new EconomyService(registry, DebtPolicy.disabled(),
                Amount.zero(2), backend, tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> result =
                economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), backend);

        assertTrue(result.isSuccess(), "old v2 notes must still redeem, got " + result);
        assertEquals(0, Amount.of(110L, 2).compareTo(
                        backend.load(owner).orElseThrow().balanceOf("dollar")),
                "redeemed value must land exactly once");
        backend.close();
    }

    @Test
    void malformedIssuancesSectionFailsFastAtModelLevel() {
        assertThrows(PersistenceException.class, () -> JsonModel.fromJson(
                        "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],"
                                + "\"issuances\":{\"not-a-uuid\":{}}}"),
                "a malformed issuance record must fail fast, never load as empty progress");
    }

    @Test
    void corruptIssuancesSectionFailsClosedAtBackendLevel(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("corrupt.json");
        Files.writeString(dataFile,
                "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],"
                        + "\"issuances\":{\"not-a-uuid\":{\"status\":\"DEBIT_COMMITTED\"}}}");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        assertThrows(PersistenceException.class, backend::initialize,
                "a corrupt issuance section must keep the backend uninitialized"
                        + " with the bytes retained for diagnosis, never silently dropped");
    }
}
