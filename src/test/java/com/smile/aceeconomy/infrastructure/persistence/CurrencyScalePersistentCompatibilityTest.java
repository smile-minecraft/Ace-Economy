package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真實持久化後端重開後的幣別精度相容性驗證。 */
class CurrencyScalePersistentCompatibilityTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path tempDir;

    private final Locale originalDefaultLocale = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale);
    }

    @FunctionalInterface
    private interface BackendFactory {
        BackendHandle open(Path path) throws Exception;
    }

    private record BackendHandle(
            AccountRepository accounts,
            TransactionRepository transactions,
            PersistenceLifecycle lifecycle) implements AutoCloseable {

        void initialize() {
            lifecycle.initialize();
        }

        @Override
        public void close() {
            lifecycle.close();
        }
    }

    private record AuditState(
            UUID id,
            UUID accountId,
            UUID counterparty,
            String currencyId,
            Amount amount,
            TransactionType type,
            Amount balanceBefore,
            Amount balanceAfter,
            Instant timestamp,
            String reason) {

        static AuditState from(Transaction transaction) {
            return new AuditState(transaction.id(), transaction.accountId(),
                    transaction.counterparty(), transaction.currencyId(), transaction.amount(),
                    transaction.type(), transaction.balanceBefore(), transaction.balanceAfter(),
                    transaction.timestamp(), transaction.reason());
        }
    }

    @Test
    @DisplayName("JSON 重開後拒絕存量精度不一致的交易並保留資料")
    void jsonRestartRejectsStoredScaleMismatchWithoutMutation() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        verifyPersistentScaleMismatch(
                path -> {
                    JsonPersistenceBackend backend = new JsonPersistenceBackend(path);
                    return new BackendHandle(backend, backend, backend);
                },
                tempDir.resolve("currency-scale.json"));
    }

    @Test
    @DisplayName("SQLite 重開後拒絕存量精度不一致的交易並保留資料")
    void sqliteRestartRejectsStoredScaleMismatchWithoutMutation() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        verifyPersistentScaleMismatch(path -> {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            SqlBackend backend = new SqlBackend(new SqlConnectionProvider(connection), new SqliteDialect());
            return new BackendHandle(backend, backend, backend);
        }, tempDir.resolve("currency-scale.sqlite"));
    }

    private void verifyPersistentScaleMismatch(BackendFactory factory, Path path) throws Exception {
        UUID alice = UUID.randomUUID();
        Account beforeRestart;
        List<AuditState> auditBeforeRestart;

        try (BackendHandle first = factory.open(path)) {
            first.initialize();
            InMemoryTransactionEventPublisher events = new InMemoryTransactionEventPublisher();
            AtomicInteger preCommitEvents = new AtomicInteger();
            events.register(event -> preCommitEvents.incrementAndGet());
            EconomyService service = newService(
                    registryWithScale(2, "Dollar", "$"), first, events, Amount.of(100, 2));

            assertTrue(service.createAccount(alice, "Alice").isSuccess());
            EconomyResult<Amount> deposit = service.deposit(
                    alice, "DOLLAR", Amount.of(new BigDecimal("25.00"), 2));
            assertTrue(deposit.isSuccess(), "scale-2 seed deposit must succeed: " + deposit.message());
            assertEquals(1, preCommitEvents.get(), "the seed deposit must be accepted once");

            beforeRestart = first.accounts().load(alice).orElseThrow();
            assertEquals(2, beforeRestart.balanceOf("dollar").scale());
            auditBeforeRestart = auditState(first.transactions().loadAll());
            assertEquals(1, auditBeforeRestart.size(), "the seed deposit must create one audit record");
        }

        try (BackendHandle reopened = factory.open(path)) {
            reopened.initialize();
            Account loadedBeforeAttempt = reopened.accounts().load(alice).orElseThrow();
            List<AuditState> auditBeforeAttempt = auditState(reopened.transactions().loadAll());
            assertEquals(beforeRestart.balances(), loadedBeforeAttempt.balances());
            assertEquals(auditBeforeRestart, auditBeforeAttempt);

            InMemoryTransactionEventPublisher events = new InMemoryTransactionEventPublisher();
            AtomicInteger preCommitEvents = new AtomicInteger();
            events.register(event -> preCommitEvents.incrementAndGet());
            EconomyService reloadedService = newService(
                    registryWithScale(4, "Renamed Dollar", "€"), reopened, events, Amount.of(100, 4));

            EconomyResult<Amount> refused = reloadedService.deposit(
                    alice, "DOLLAR", Amount.of(new BigDecimal("1.0000"), 4));

            assertTrue(refused.isFailure(), "the scale-mismatched transaction must be refused");
            assertEquals(EconomyError.CURRENCY_SCALE_MISMATCH, refused.error());
            assertNotNull(refused.message());
            assertTrue(refused.message().contains("dollar"),
                    "the refusal must name the affected currency: " + refused.message());
            assertTrue(refused.message().contains("stored scale 2"),
                    "the refusal must report the persisted scale: " + refused.message());
            assertTrue(refused.message().contains("configured scale 4"),
                    "the refusal must report the configured scale: " + refused.message());
            assertTrue(refused.message().contains("data migration required"),
                    "restart must not be presented as a scale migration: " + refused.message());
            assertTrue(refused.message().contains("nothing changed"),
                    "the refusal must state that no data changed: " + refused.message());

            Account afterAttempt = reopened.accounts().load(alice).orElseThrow();
            assertEquals(loadedBeforeAttempt.balances(), afterAttempt.balances(),
                    "the persisted balance must remain unchanged");
            assertEquals(auditBeforeAttempt, auditState(reopened.transactions().loadAll()),
                    "the persisted audit must remain unchanged");
            assertEquals(0, preCommitEvents.get(), "refusal must happen before pre-commit");
        }
    }

    private static EconomyService newService(
            CurrencyRegistry currencies,
            BackendHandle backend,
            InMemoryTransactionEventPublisher events,
            Amount startBalance) {
        return new EconomyService(currencies, DebtPolicy.disabled(), startBalance,
                backend.accounts(), new PersistentAuditSink(backend.transactions()),
                new FixedClock(), events);
    }

    private static CurrencyRegistry registryWithScale(int scale, String displayName, String symbol) {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", displayName, symbol, scale, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    private static List<AuditState> auditState(List<Transaction> transactions) {
        return transactions.stream().map(AuditState::from).toList();
    }
}
