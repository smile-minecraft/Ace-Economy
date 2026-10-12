package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.SecurityControlState;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.security.FileSecurityControlStore;
import com.smile.aceeconomy.operations.AuditQuery;
import com.smile.aceeconomy.operations.HistoryService;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.ports.inmemory.MutableClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict control-file validation: every required field must be present,
 * non-null and exactly typed, otherwise the load fails closed without
 * touching the file. First start without a file still opens writable;
 * unknown extra fields stay compatible.
 */
class StrictControlFileValidationTest {

    private FileSecurityControlStore store(Path file) {
        return new FileSecurityControlStore(file);
    }

    private void write(Path file, String document) throws Exception {
        Files.writeString(file, document, StandardCharsets.UTF_8);
    }

    private String validDocument(UUID frozenId) {
        String frozen = frozenId == null ? "[]" : "[\"" + frozenId + "\"]";
        return "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                + "\"frozen\":" + frozen + ",\"audits\":[]}";
    }

    @Test
    @DisplayName("empty object fails closed instead of silently opening")
    void emptyObjectFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("security-control.json");
        write(file, "{}");
        byte[] before = Files.readAllBytes(file);
        long sizeBefore = before.length;
        FileSecurityControlStore s = store(file);
        SecurityControlService control = new SecurityControlService(s);

        assertTrue(control.isLoadFailed(), "{} must fail closed");
        assertTrue(control.isGlobalReadonly(), "{} must be read-only");
        assertTrue(control.checkInitiating(UUID.randomUUID()).isPresent());
        assertFalse(control.loadDiagnosis().isBlank());
        assertEquals(sizeBefore, Files.size(file), "bad file must never be overwritten");
        assertArrayEquals(before, Files.readAllBytes(file),
                "bad file bytes must stay identical, not just the same length");
    }

    @Test
    @DisplayName("null-valued control fields fail closed")
    void nullFieldsFailClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("security-control.json");
        write(file, "{\"format\":1,\"version\":4,\"globalReadonly\":null,\"frozen\":null,"
                + "\"audits\":null}");
        FileSecurityControlStore s = store(file);
        SecurityControlService control = new SecurityControlService(s);

        assertTrue(control.isLoadFailed(), "null fields must fail closed");
        assertTrue(control.isGlobalReadonly());
    }

    @Test
    @DisplayName("each missing required field fails closed")
    void eachMissingFieldFailsClosed(@TempDir Path dir) throws Exception {
        String[] documents = {
            "{\"version\":4,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,\"frozen\":[]}"
        };
        for (String document : documents) {
            Path file = dir.resolve("security-control-" + document.hashCode() + ".json");
            write(file, document);
            SecurityControlService control = new SecurityControlService(store(file));
            assertTrue(control.isLoadFailed(), "missing field must fail closed: " + document);
            assertTrue(control.isGlobalReadonly());
        }
    }

    @Test
    @DisplayName("wrong types, negative and fractional version, unknown format fail closed")
    void badTypesAndRangesFailClosed(@TempDir Path dir) throws Exception {
        String[] documents = {
            "{\"format\":\"1\",\"version\":4,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":\"4\",\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":\"false\",\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":-1,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":4.5,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":2,\"version\":4,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1.0,\"version\":4,\"globalReadonly\":false,\"frozen\":[],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,\"frozen\":{},\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,\"frozen\":[],\"audits\":{}}"
        };
        for (String document : documents) {
            Path file = dir.resolve("security-control-" + document.hashCode() + ".json");
            write(file, document);
            SecurityControlService control = new SecurityControlService(store(file));
            assertTrue(control.isLoadFailed(), "bad type/range must fail closed: " + document);
        }
    }

    @Test
    @DisplayName("bad, duplicate and non-canonical frozen entries fail closed")
    void badFrozenEntriesFailClosed(@TempDir Path dir) throws Exception {
        UUID good = UUID.randomUUID();
        String canonical = good.toString();
        String upper = canonical.toUpperCase(java.util.Locale.ROOT);
        String[] documents = {
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                    + "\"frozen\":[\"not-a-uuid\"],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                    + "\"frozen\":[123],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                    + "\"frozen\":[null],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                    + "\"frozen\":[\"" + canonical + "\",\"" + canonical + "\"],\"audits\":[]}",
            "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                    + "\"frozen\":[\"" + upper + "\"],\"audits\":[]}"
        };
        for (String document : documents) {
            Path file = dir.resolve("security-control-" + document.hashCode() + ".json");
            write(file, document);
            SecurityControlService control = new SecurityControlService(store(file));
            assertTrue(control.isLoadFailed(), "bad frozen entry must fail closed: " + document);
        }
    }

    @Test
    @DisplayName("illegal audit entries fail closed")
    void illegalAuditsFailClosed(@TempDir Path dir) throws Exception {
        String goodAudit = "{\"id\":\"" + UUID.randomUUID() + "\",\"operation\":\"FREEZE\","
                + "\"operatorId\":null,\"operatorName\":\"CONSOLE\",\"reason\":\"incident\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":\"" + UUID.randomUUID() + "\"}";
        // Each variant corrupts one audit rule: bad id, unknown op, bad time,
        // blank actor, secret reason, freeze without target.
        String[] badAudits = {
            "{\"id\":\"bad\",\"operation\":\"FREEZE\",\"operatorId\":null,"
                    + "\"operatorName\":\"CONSOLE\",\"reason\":\"incident\","
                    + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":\"" + UUID.randomUUID() + "\"}",
            "{\"id\":\"" + UUID.randomUUID() + "\",\"operation\":\"NOPE\",\"operatorId\":null,"
                    + "\"operatorName\":\"CONSOLE\",\"reason\":\"incident\","
                    + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":null}",
            "{\"id\":\"" + UUID.randomUUID() + "\",\"operation\":\"FREEZE\",\"operatorId\":null,"
                    + "\"operatorName\":\"CONSOLE\",\"reason\":\"incident\","
                    + "\"timestamp\":\"not-a-time\",\"target\":\"" + UUID.randomUUID() + "\"}",
            "{\"id\":\"" + UUID.randomUUID() + "\",\"operation\":\"FREEZE\",\"operatorId\":null,"
                    + "\"operatorName\":\"\",\"reason\":\"incident\","
                    + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":\"" + UUID.randomUUID() + "\"}",
            "{\"id\":\"" + UUID.randomUUID() + "\",\"operation\":\"FREEZE\",\"operatorId\":null,"
                    + "\"operatorName\":\"CONSOLE\",\"reason\":\"has space\","
                    + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":\"" + UUID.randomUUID() + "\"}",
            "{\"id\":\"" + UUID.randomUUID() + "\",\"operation\":\"FREEZE\",\"operatorId\":null,"
                    + "\"operatorName\":\"CONSOLE\",\"reason\":\"incident\","
                    + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":null}"
        };
        for (String bad : badAudits) {
            Path file = dir.resolve("security-control-" + bad.hashCode() + ".json");
            write(file, "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                    + "\"frozen\":[],\"audits\":[" + bad + "]}");
            SecurityControlService control = new SecurityControlService(store(file));
            assertTrue(control.isLoadFailed(), "illegal audit must fail closed: " + bad);
        }
        // The good audit stays loadable.
        Path goodFile = dir.resolve("security-control-good.json");
        write(goodFile, "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                + "\"frozen\":[],\"audits\":[" + goodAudit + "]}");
        SecurityControlService good = new SecurityControlService(store(goodFile));
        assertFalse(good.isLoadFailed(), "a well-formed audit must load");
    }

    @Test
    @DisplayName("unknown extra fields stay compatible and failed loads keep bytes and queries")
    void extraCompatibleAndFailedLoadKeepsBytes(@TempDir Path dir) throws Exception {
        Path extra = dir.resolve("security-control-extra.json");
        write(extra, "{\"format\":1,\"version\":4,\"globalReadonly\":false,\"frozen\":[],"
                + "\"audits\":[],\"extra\":\"future\"}");
        SecurityControlService compatible = new SecurityControlService(store(extra));
        assertFalse(compatible.isLoadFailed(), "unknown extra fields must be ignored");

        Path bad = dir.resolve("security-control-bad.json");
        byte[] before = "{\"format\":1,\"version\":4}".getBytes(StandardCharsets.UTF_8);
        Files.write(bad, before);
        long sizeBefore = Files.size(bad);
        FileSecurityControlStore s = store(bad);
        SecurityControlService failed = new SecurityControlService(s);
        assertTrue(failed.isLoadFailed());
        // Mutations cannot override the bad file.
        try {
            failed.freeze(UUID.randomUUID(), AdminAudit.console("incident"));
        } catch (IllegalStateException expected) {
            // Expected fail-closed refusal.
        }
        try {
            failed.unfreeze(UUID.randomUUID(), AdminAudit.console("incident"));
        } catch (IllegalStateException expected) {
            // Expected fail-closed refusal.
        }
        failed.setGlobalReadonly(false, AdminAudit.console("recovered"));
        assertTrue(failed.isLoadFailed(), "read-only off must not clear a load failure");
        assertTrue(failed.isGlobalReadonly());
        assertEquals(sizeBefore, Files.size(bad), "bad file bytes must be preserved");
        assertArrayEquals(before, Files.readAllBytes(bad),
                "bad file bytes must stay identical, not just the same length");
        assertTrue(failed.checkInitiating(UUID.randomUUID()).isPresent(),
                "mutations stay refused while queries stay available");
        assertFalse(failed.loadDiagnosis().isBlank());
        assertFalse(failed.loadDiagnosis().contains(new String(before, StandardCharsets.UTF_8)),
                "diagnosis must not echo raw file content");
        SecurityControlState snapshot = failed.snapshot();
        assertTrue(snapshot.isLoadFailed());

        // Queries bypass the gate even while the control is fail-closed: a real
        // economy bound to the failed control still answers balance, history
        // and leaderboard reads, while money movement stays refused.
        JsonPersistenceBackend economyBackend =
                new JsonPersistenceBackend(dir.resolve("economy-data.json"));
        economyBackend.initialize();
        CurrencyRegistry currencies = CurrencyRegistry.of(java.util.List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
        EconomyService economy = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), economyBackend, tx -> {
                    try {
                        economyBackend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, java.time.Instant::now,
                new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        UUID alice = UUID.randomUUID();
        assertTrue(economy.createAccount(alice, "Alice").isSuccess());
        assertTrue(economy.deposit(alice, "dollar", Amount.of(1000, 2)).isSuccess());
        economy.bindSecurityControl(failed);
        EconomyResult<Amount> balance = economy.getBalance(alice, "dollar");
        assertTrue(balance.isSuccess(), "balance queries must bypass the fail-closed gate");
        assertEquals(0, Amount.of(1000, 2).compareTo(balance.value()));
        EconomyResult<Amount> refused = economy.withdraw(alice, "dollar", Amount.of(100, 2));
        assertTrue(refused.isFailure(), "money movement must stay refused while load-failed");
        assertEquals(EconomyError.CONTROL_UNAVAILABLE, refused.error());
        HistoryService history = new HistoryService(economyBackend);
        assertTrue(history.query(AuditQuery.builder().build()).total() >= 0,
                "history queries must stay available while load-failed");
        LeaderboardService leaderboard = new LeaderboardService(
                economyBackend::leaderboardRows, new MutableClock(java.time.Instant.EPOCH),
                new LeaderboardCache(), java.time.Duration.ofMinutes(5));
        assertTrue(leaderboard.query("dollar", 0, 10).totalEntries() >= 1,
                "leaderboard queries must stay available while load-failed");
    }

    @Test
    @DisplayName("first start without a file still opens writable")
    void missingFileOpensWritable(@TempDir Path dir) {
        Path missing = dir.resolve("absent-control.json");
        SecurityControlService open = new SecurityControlService(store(missing));
        assertFalse(open.isLoadFailed());
        assertFalse(open.isGlobalReadonly());
    }
}
