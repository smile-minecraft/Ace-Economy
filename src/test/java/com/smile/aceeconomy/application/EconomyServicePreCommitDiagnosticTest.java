package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicTransferStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pre-commit storage failure must stay diagnosable: the service answers with an
 * explicit failure result, but the original throwable (with its cause chain and
 * suppressed cleanup failures) is recorded on the diagnostic log, and the
 * player-facing message is a fixed safe string without internal paths.
 */
class EconomyServicePreCommitDiagnosticTest {

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    private static PersistenceException storageOutage() {
        PersistenceException failure = new PersistenceException(
                "Failed to save account (file=/secret/place/data.json)",
                new RuntimeException("injected io"));
        failure.addSuppressed(new java.io.IOException("rollback blew up"));
        return failure;
    }

    private static List<LogRecord> captureServiceLog(Runnable action) {
        Logger logger = Logger.getLogger(EconomyService.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    @Test
    void transferPreCommitFailureKeepsDiagnosticAndUsesFixedMessage() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        repo.create(alice, "Alice", Map.of("dollar", Amount.of(1000L, 2)));
        repo.create(bob, "Bob", Map.of("dollar", Amount.of(1000L, 2)));
        PersistenceException injected = storageOutage();
        AtomicTransferStore failing = (from, to, currencyId, amount, debtPolicy) -> {
            throw injected;
        };
        EconomyService svc = new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2),
                repo, new RecordingAuditSink(), Instant::now,
                new InMemoryTransactionEventPublisher(), failing);

        List<LogRecord> records = captureServiceLog(() -> {
            EconomyResult<TransferResult> r = svc.transfer(alice, bob, "dollar", Amount.of(100L, 2));
            assertTrue(r.isFailure(), "an uncommitted transfer must answer with a failure result");
            assertEquals(EconomyError.AUDIT_FAILURE, r.error());
            assertFalse(String.valueOf(r.message()).contains("/secret/place"),
                    () -> "internal paths must not reach the player reply, got: " + r.message());
            assertFalse(String.valueOf(r.message()).contains("data.json"),
                    () -> "internal file names must not reach the player reply, got: " + r.message());
        });

        assertTrue(records.stream().anyMatch(record -> record.getThrown() == injected),
                "the original throwable must be recorded for diagnosis");
        assertTrue(records.stream()
                        .filter(record -> record.getThrown() == injected)
                        .flatMap(record -> java.util.Arrays.stream(record.getThrown().getSuppressed()))
                        .anyMatch(suppressed -> suppressed.getMessage().contains("rollback blew up")),
                "suppressed cleanup failures must stay on the diagnostic record");
    }

    @Test
    void redeemPreCommitFailureKeepsDiagnosticAndUsesFixedMessage() {
        UUID owner = UUID.randomUUID();
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        repo.create(owner, "alice", Map.of("dollar", Amount.of(100L, 2)));
        PersistenceException injected = storageOutage();
        AtomicRedemptionStore failing = new AtomicRedemptionStore() {
            @Override
            public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount) {
                throw injected;
            }

            @Override
            public RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction,
                                                   DebtPolicy debtPolicy) {
                throw injected;
            }
        };
        EconomyService svc = new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2),
                repo, new RecordingAuditSink(), Instant::now,
                new InMemoryTransactionEventPublisher());

        List<LogRecord> records = captureServiceLog(() -> {
            EconomyResult<Amount> r = svc.redeemBanknote(UUID.randomUUID(), owner, "dollar",
                    Amount.of(10L, 2), failing);
            assertTrue(r.isFailure(), "an uncommitted redeem must answer with a failure result");
            assertEquals(EconomyError.AUDIT_FAILURE, r.error());
            assertFalse(String.valueOf(r.message()).contains("/secret/place"),
                    () -> "internal paths must not reach the player reply, got: " + r.message());
            assertFalse(String.valueOf(r.message()).contains("data.json"),
                    () -> "internal file names must not reach the player reply, got: " + r.message());
        });

        assertTrue(records.stream().anyMatch(record -> record.getThrown() == injected),
                "the original throwable must be recorded for diagnosis");
        assertTrue(records.stream()
                        .filter(record -> record.getThrown() == injected)
                        .flatMap(record -> java.util.Arrays.stream(record.getThrown().getSuppressed()))
                        .anyMatch(suppressed -> suppressed.getMessage().contains("rollback blew up")),
                "suppressed cleanup failures must stay on the diagnostic record");
    }
}
