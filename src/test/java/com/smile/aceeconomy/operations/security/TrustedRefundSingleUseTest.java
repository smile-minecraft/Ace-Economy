package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.operations.internal.FailedMintRefund;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.internal.TrustedRefundLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trusted failed-mint refunds go through a single-use ledger entry that only a
 * real committed deduction can authorize.
 *
 * <p>A self-asserted {@link FailedMintRefund} without a ledger entry, an unknown
 * nonce, a tampered amount, a replay, or an already-redeemed note are all refused
 * with no money movement. The one authorized refund succeeds and normal queries
 * keep working. All success assertions fail on the skeleton ledger and refund path.
 */
class TrustedRefundSingleUseTest {

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    private EconomyService wired(EconomyTestHarness h, SecurityControlService control,
                                 TrustedRefundLedger ledger) {
        h.service().bindSecurityControl(control);
        h.service().bindRefundLedger(ledger);
        return h.service();
    }

    @Test
    @DisplayName("authorized failed mint is refunded exactly once through the wired core")
    void authorizedRefundSucceedsOnce() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        h.service().withdraw(alice, "dollar", Amount.of(200, 2));

        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        EconomyService wired = wired(h, control, ledger);

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        FailedMintRefund proof = FailedMintRefund.proven(alice, "dollar", Amount.of(200, 2), nonce);

        EconomyResult<Amount> first = wired.refundFailedMint(proof);
        assertTrue(first.isSuccess(), "authorized failed mint must be refunded");
        assertEquals(Amount.of(1000, 2), first.value());
        assertEquals(Amount.of(1000, 2), wired.getBalance(alice, "dollar").value());

        EconomyResult<Amount> second = wired.refundFailedMint(proof);
        assertTrue(second.isFailure(), "the same proof must not refund twice");
        assertEquals(Amount.of(1000, 2), wired.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("self-asserted proof without a ledger entry is refused with no money change")
    void forgedProofRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");

        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        EconomyService wired = wired(h, control, ledger);

        FailedMintRefund forged = FailedMintRefund.proven(
                alice, "dollar", Amount.of(200, 2), UUID.randomUUID());
        EconomyResult<Amount> r = wired.refundFailedMint(forged);

        assertTrue(r.isFailure(), "proof without a ledger entry must be refused");
        assertEquals(Amount.of(1000, 2), wired.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("tampered amount against the ledger entry is refused")
    void tamperedAmountRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        h.service().withdraw(alice, "dollar", Amount.of(200, 2));

        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        EconomyService wired = wired(h, control, ledger);

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        FailedMintRefund tampered = FailedMintRefund.proven(
                alice, "dollar", Amount.of(999, 2), nonce);

        EconomyResult<Amount> r = wired.refundFailedMint(tampered);
        assertTrue(r.isFailure(), "tampered amount must not match the ledger entry");
        assertEquals(Amount.of(800, 2), wired.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("refund for a successfully redeemed note is refused")
    void redeemedNoteCannotRefund(@TempDir Path dir) {
        Path file = dir.resolve("redeemed-refund.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "owner", Map.of("dollar", Amount.of(100L, 2)));
        EconomyService economy = new EconomyService(harness().currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> java.time.Instant.now(),
                new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(ledger);

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> redeemed =
                economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), backend);
        assertTrue(redeemed.isSuccess(), "setup redeem must succeed");

        ledger.authorize(nonce, owner, "dollar", Amount.of(10L, 2));
        FailedMintRefund proof = FailedMintRefund.proven(owner, "dollar", Amount.of(10L, 2), nonce);
        EconomyResult<Amount> r = economy.refundFailedMint(proof);

        assertTrue(r.isFailure(), "an already-redeemed nonce must never refund");
        assertTrue(backend.isConsumed(nonce), "redeemed nonce stays consumed");
    }

    @Test
    @DisplayName("wired core refuses a frozen withdraw before any debit")
    void frozenWithdrawRefusedBeforeDebit() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");

        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        EconomyService wired = wired(h, control, ledger);
        control.freeze(alice, AdminAudit.console("incident"));

        assertTrue(control.isFrozen(alice), "freeze must be effective before the withdraw");
        EconomyResult<Amount> w = wired.withdraw(alice, "dollar", Amount.of(100, 2));
        assertTrue(w.isFailure(), "frozen withdraw must be refused");
        assertEquals(EconomyError.ACCOUNT_FROZEN, w.error());
        assertEquals(Amount.of(1000, 2), wired.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("wired Vault deposit into a frozen account is refused")
    void vaultDepositIntoFrozenRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");

        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        EconomyService wired = wired(h, control, ledger);
        control.freeze(alice, AdminAudit.console("incident"));

        EconomyApi api = new EconomyApiImpl(wired, h.publisher());
        EconomyResult<Amount> r = api.deposit(alice, "dollar", Amount.of(50, 2));

        assertTrue(r.isFailure(), "Vault-shared core deposit into a frozen account must be refused");
        assertEquals(EconomyError.ACCOUNT_FROZEN, r.error());
        assertEquals(Amount.of(1000, 2), wired.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("frozen redeem through the wired core keeps its nonce")
    void frozenRedeemKeepsNonce(@TempDir Path dir) {
        Path file = dir.resolve("wired-frozen-redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "owner", Map.of("dollar", Amount.of(100L, 2)));
        EconomyService economy = new EconomyService(harness().currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, tx -> {
                    try {
                        backend.append(tx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> java.time.Instant.now(),
                new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        SecurityControlService control = new SecurityControlService();
        economy.bindSecurityControl(control);
        economy.bindRefundLedger(new InMemoryTrustedRefundLedger());
        control.freeze(owner, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r =
                economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), backend);

        assertTrue(r.isFailure(), "frozen redeem must be refused");
        assertEquals(EconomyError.ACCOUNT_FROZEN, r.error());
        assertFalse(backend.isConsumed(nonce), "refused redeem must not burn the nonce");
    }
}
