package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.internal.UncertainDeliveryCompensation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An uncertain banknote handover (the region callback threw, so the note may
 * or may not have landed) carries a committed deduction. Its atomic
 * void+refund travels a single-use trusted authorization exempt from the
 * freeze and read-only gate, so the money is never stranded. Anything without
 * a matching authorization mints nothing, and the legacy direct call stays
 * gate-checked for ordinary initiators.
 */
class TrustedVoidRefundExemptionTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    private static EconomyService jsonEconomy(JsonPersistenceBackend backend) {
        return new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2), backend,
                tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, Instant::now, new InMemoryTransactionEventPublisher());
    }

    private static EconomyService sqliteEconomy(SqlBackend backend) {
        return new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2), backend,
                tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, Instant::now, new InMemoryTransactionEventPublisher());
    }

    private static Amount storedDollar(JsonPersistenceBackend backend, UUID owner) {
        return backend.load(owner).orElseThrow().balanceOf("dollar");
    }

    private static Amount storedDollar(SqlBackend backend, UUID owner) {
        return backend.load(owner).orElseThrow().balanceOf("dollar");
    }

    @Test
    @DisplayName("trusted void+refund succeeds while the account is frozen (real JSON)")
    void trustedVoidRefundSucceedsUnderFreeze(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("trusted-void-freeze.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));
        Amount charge = Amount.of(200, 2);
        assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());
        assertEquals(0, Amount.of(800, 2).compareTo(storedDollar(backend, alice)));

        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", charge, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
        EconomyResult<Amount> refund = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation");

        assertTrue(refund.isSuccess(),
                "a ledger-authorized uncertain handover must void and refund despite the freeze: "
                        + refund.message());
        assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)),
                "the frozen account must be whole again, nothing stranded");
        assertTrue(backend.isConsumed(nonce),
                "the uncertain note must be dead: no redeemable note next to the refund");
    }

    @Test
    @DisplayName("trusted void+refund succeeds while the server is read-only (real SQLite)")
    void trustedVoidRefundSucceedsUnderReadonly(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("trusted-void-readonly.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        try {
            backend.initialize();
            EconomyService economy = sqliteEconomy(backend);
            UUID alice = UUID.randomUUID();
            economy.createAccount(alice, "Alice");
            economy.deposit(alice, "dollar", Amount.of(1000, 2));
            Amount charge = Amount.of(200, 2);
            assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());

            SecurityControlService control = new SecurityControlService();
            InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
            economy.bindSecurityControl(control);
            economy.bindRefundLedger(ledger);
            control.setGlobalReadonly(true, AdminAudit.console("emergency"));

            UUID nonce = UUID.randomUUID();
            ledger.authorize(nonce, alice, "dollar", charge, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
            EconomyResult<Amount> refund = economy.compensateUndeliveredNote(
                    UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                    backend, "withdraw-compensation");

            assertTrue(refund.isSuccess(),
                    "a ledger-authorized uncertain handover must void and refund while read-only: "
                            + refund.message());
            assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)),
                    "the account must be whole again, nothing stranded");
            assertTrue(backend.isConsumed(nonce),
                    "the uncertain note must be dead: no redeemable note next to the refund");
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("legacy direct compensate stays gate-checked while frozen")
    void legacyCompensateStaysRefusedUnderFreeze(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("legacy-void-freeze.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));
        Amount charge = Amount.of(200, 2);
        assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());

        SecurityControlService control = new SecurityControlService();
        economy.bindSecurityControl(control);
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> refused = economy.compensateUndeliveredNote(
                nonce, alice, "dollar", charge, backend, "withdraw-compensation");

        assertTrue(refused.isFailure(), "the legacy direct call must stay gate-checked");
        assertEquals(EconomyError.ACCOUNT_FROZEN, refused.error());
        assertEquals(0, Amount.of(800, 2).compareTo(storedDollar(backend, alice)),
                "nothing moved: the charge stands stranded until the trusted path runs");
        assertFalse(backend.isConsumed(nonce), "a refused void must not burn the nonce");
    }

    @Test
    @DisplayName("replay after a trusted void refuses, and the note can never be redeemed")
    void replayAfterVoidRefusesWithoutSecondMoney(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("trusted-void-replay.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));
        Amount charge = Amount.of(200, 2);
        assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());

        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", charge, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
        assertTrue(economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation").isSuccess());

        EconomyResult<Amount> replay = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation");
        assertTrue(replay.isFailure(), "a second compensation for the same nonce must be refused");
        assertEquals(EconomyError.REPLAY_DETECTED, replay.error());
        assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)),
                "no second refund may land");

        control.unfreeze(alice, AdminAudit.console("resolved"));
        EconomyResult<Amount> redeem = economy.redeemBanknote(nonce, alice, "dollar", charge,
                backend);
        assertTrue(redeem.isFailure(), "the voided note must never become redeemable");
        assertEquals(EconomyError.REPLAY_DETECTED, redeem.error());
        assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)),
                "no ticket-plus-money coexistence");
    }

    @Test
    @DisplayName("a hand-built proof without a ledger entry mints nothing")
    void forgedProofWithoutAuthorizationIsRefused(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("trusted-void-forged.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));

        SecurityControlService control = new SecurityControlService();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(new InMemoryTrustedRefundLedger());

        UUID nonce = UUID.randomUUID();
        Amount forged = Amount.of(200, 2);
        EconomyResult<Amount> refused = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", forged, nonce),
                backend, "withdraw-compensation");

        assertTrue(refused.isFailure(), "a hand-built proof with a fresh nonce must be refused");
        assertEquals(EconomyError.INVALID_AMOUNT, refused.error());
        assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)));
        assertFalse(backend.isConsumed(nonce), "a refused forgery must not burn the nonce");
    }

    @Test
    @DisplayName("a tampered amount against the authorized entry is refused")
    void tamperedAmountIsRefused(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("trusted-void-tampered.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));

        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2), com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
        EconomyResult<Amount> refused = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", Amount.of(150, 2), nonce),
                backend, "withdraw-compensation");

        assertTrue(refused.isFailure(), "a tampered amount must not match the authorization");
        assertEquals(EconomyError.INVALID_AMOUNT, refused.error());
        assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)));
        assertFalse(backend.isConsumed(nonce), "a refused tampering must not burn the nonce");
    }

    @Test
    @DisplayName("unknown account leaves the nonce unconsumed and retries after creation")
    void unknownAccountRetriesAfterCreation(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("trusted-void-unknown.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        try {
            backend.initialize();
            EconomyService economy = sqliteEconomy(backend);
            UUID ghost = UUID.randomUUID();
            Amount charge = Amount.of(200, 2);

            SecurityControlService control = new SecurityControlService();
            InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
            economy.bindSecurityControl(control);
            economy.bindRefundLedger(ledger);

            UUID nonce = UUID.randomUUID();
            ledger.authorize(nonce, ghost, "dollar", charge, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
            EconomyResult<Amount> missing = economy.compensateUndeliveredNote(
                    UncertainDeliveryCompensation.proven(ghost, "dollar", charge, nonce),
                    backend, "withdraw-compensation");

            assertTrue(missing.isFailure(), "compensation for a missing account must be refused");
            assertEquals(EconomyError.ACCOUNT_NOT_FOUND, missing.error());
            assertFalse(backend.isConsumed(nonce),
                    "the note stays redeemable once the account exists; nothing is fabricated");

            economy.createAccount(ghost, "Ghost");
            EconomyResult<Amount> retry = economy.compensateUndeliveredNote(
                    UncertainDeliveryCompensation.proven(ghost, "dollar", charge, nonce),
                    backend, "withdraw-compensation");
            assertTrue(retry.isSuccess(),
                    "the released claim retries with the exact same fields: " + retry.message());
            assertEquals(0, charge.compareTo(storedDollar(backend, ghost)));
            assertTrue(backend.isConsumed(nonce));
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("trusted void without a bound ledger fails closed")
    void unboundLedgerFailsClosed(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("trusted-void-unbound.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));
        Amount charge = Amount.of(200, 2);
        assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());

        SecurityControlService control = new SecurityControlService();
        economy.bindSecurityControl(control);
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> refused = economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation");

        assertTrue(refused.isFailure(), "no ledger means no trusted compensation");
        assertEquals(EconomyError.AUDIT_FAILURE, refused.error());
        assertTrue(refused.message() != null && refused.message().contains("ledger"),
                "the refusal must name the missing ledger, not the gate: " + refused.message());
        assertEquals(0, Amount.of(800, 2).compareTo(storedDollar(backend, alice)));
        assertFalse(backend.isConsumed(nonce));
    }

    @Test
    @DisplayName("ordinary deposit stays refused while the trusted void succeeds")
    void ordinaryDepositStaysRefusedWhileTrustedSucceeds(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("trusted-void-no-bypass.json"));
        backend.initialize();
        EconomyService economy = jsonEconomy(backend);
        UUID alice = UUID.randomUUID();
        economy.createAccount(alice, "Alice");
        economy.deposit(alice, "dollar", Amount.of(1000, 2));
        Amount charge = Amount.of(200, 2);
        assertTrue(economy.withdraw(alice, "dollar", charge).isSuccess());

        SecurityControlService control = new SecurityControlService();
        InMemoryTrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", charge, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
        assertTrue(economy.compensateUndeliveredNote(
                UncertainDeliveryCompensation.proven(alice, "dollar", charge, nonce),
                backend, "withdraw-compensation").isSuccess());

        EconomyResult<Amount> external =
                economy.deposit(alice, "dollar", Amount.of(50, 2));
        assertTrue(external.isFailure(), "ordinary deposit must not ride the exemption");
        assertEquals(EconomyError.ACCOUNT_FROZEN, external.error());
        assertEquals(0, Amount.of(1000, 2).compareTo(storedDollar(backend, alice)));
    }
}
