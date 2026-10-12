package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.operations.security.SecurityControlService;

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
 * A frozen account cannot start any balance change and cannot receive one.
 * Every refusal happens before any debit, so no money moves and a ticket keeps its nonce.
 */
class AccountFreezeGateTest {

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    private AdminAudit audit() {
        return AdminAudit.console("incident");
    }

    @Test
    @DisplayName("frozen sender transfer is refused before any debit")
    void frozenSenderTransferRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.freeze(alice, audit());

        assertTrue(control.isFrozen(alice), "freeze must be effective before the transfer");
        EconomyResult<TransferResult> r =
                h.service.transfer(alice, bob, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure(), "frozen sender must not transfer");
        assertEquals(EconomyError.ACCOUNT_FROZEN, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(bob, "dollar").value());
    }

    @Test
    @DisplayName("transfer into a frozen account fails and the sender keeps the money")
    void frozenReceiverTransferRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.freeze(bob, audit());

        assertTrue(control.isFrozen(bob), "receiver freeze must be effective");
        EconomyResult<TransferResult> r =
                h.service.transfer(alice, bob, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure(), "transfer into a frozen account must fail");
        assertEquals(EconomyError.ACCOUNT_FROZEN, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(bob, "dollar").value());
    }

    @Test
    @DisplayName("frozen account withdraw and external deposit are refused without moving money")
    void frozenWithdrawAndDepositRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.freeze(alice, audit());

        EconomyResult<Amount> w = h.service.withdraw(alice, "dollar", Amount.of(100, 2));
        assertTrue(w.isFailure(), "frozen withdraw must be refused");
        assertEquals(EconomyError.ACCOUNT_FROZEN, w.error());

        EconomyResult<Amount> d = h.service.deposit(alice, "dollar", Amount.of(100, 2));
        assertTrue(d.isFailure(), "external deposit into a frozen account must be refused");
        assertEquals(EconomyError.ACCOUNT_FROZEN, d.error());

        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("frozen redeem is refused before debit and keeps its nonce")
    void frozenRedeemKeepsNonce(@TempDir Path dir) {
        Path file = dir.resolve("frozen-redeem.json");
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
        control.freeze(owner, audit());

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r =
                economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), backend);
        assertTrue(r.isFailure(), "frozen redeem must be refused");
        assertEquals(EconomyError.ACCOUNT_FROZEN, r.error());
        assertFalse(backend.isConsumed(nonce), "refused redeem must not burn the nonce");
        assertEquals(0, Amount.of(100L, 2).compareTo(
                backend.load(owner).orElseThrow().balanceOf("dollar")));
    }

    @Test
    @DisplayName("frozen administrative mutations are refused with no override")
    void frozenAdminMutationsRefused(@TempDir Path dir) {
        Path file = dir.resolve("frozen-admin.json");
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
        control.freeze(owner, audit());

        EconomyResult<Amount> give =
                economy.deposit(owner, "dollar", Amount.of(10L, 2), AdminAudit.console("incident"));
        assertTrue(give.isFailure(), "frozen give must be refused with no override");
        assertEquals(EconomyError.ACCOUNT_FROZEN, give.error());

        EconomyResult<Amount> take =
                economy.withdraw(owner, "dollar", Amount.of(10L, 2), AdminAudit.console("incident"));
        assertTrue(take.isFailure(), "frozen take must be refused with no override");
        assertEquals(EconomyError.ACCOUNT_FROZEN, take.error());

        EconomyResult<Amount> set =
                economy.setBalance(owner, "dollar", Amount.of(5L, 2), AdminAudit.console("incident"));
        assertTrue(set.isFailure(), "frozen set must be refused with no override");
        assertEquals(EconomyError.ACCOUNT_FROZEN, set.error());

        assertEquals(0, Amount.of(100L, 2).compareTo(
                backend.load(owner).orElseThrow().balanceOf("dollar")));
    }
}
