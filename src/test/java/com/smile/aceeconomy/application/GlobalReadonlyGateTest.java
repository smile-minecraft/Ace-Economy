package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.operations.security.SecurityControlService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Global read-only mode refuses every balance change but keeps read-only queries working,
 * and the operator can switch it back off.
 */
class GlobalReadonlyGateTest {

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    @Test
    @DisplayName("read-only refuses deposit, withdraw, and transfer on any account")
    void readonlyRefusesAllMutations() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.setGlobalReadonly(true, AdminAudit.console("emergency"));

        assertTrue(control.isGlobalReadonly(), "read-only must be effective");
        EconomyResult<Amount> d = h.service.deposit(alice, "dollar", Amount.of(10, 2));
        assertTrue(d.isFailure(), "deposit under read-only must be refused");
        assertEquals(EconomyError.GLOBAL_READ_ONLY, d.error());

        EconomyResult<Amount> w = h.service.withdraw(alice, "dollar", Amount.of(10, 2));
        assertTrue(w.isFailure(), "withdraw under read-only must be refused");
        assertEquals(EconomyError.GLOBAL_READ_ONLY, w.error());

        EconomyResult<TransferResult> t =
                h.service.transfer(alice, bob, "dollar", Amount.of(10, 2));
        assertTrue(t.isFailure(), "transfer under read-only must be refused");
        assertEquals(EconomyError.GLOBAL_READ_ONLY, t.error());

        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(bob, "dollar").value());
    }

    @Test
    @DisplayName("read-only keeps balance and account queries working")
    void readonlyKeepsQueriesWorking() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.setGlobalReadonly(true, AdminAudit.console("emergency"));

        assertTrue(h.service.getBalance(alice, "dollar").isSuccess(),
                "balance query must stay available under read-only");
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertTrue(h.service.load(alice).isSuccess(),
                "account query must stay available under read-only");
        assertFalse(h.service.hasAccount(UUID.randomUUID()) ,
                "unknown account query must still report absence under read-only");
    }

    @Test
    @DisplayName("switching read-only off restores mutations")
    void readonlyOffRestoresMutations() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.setGlobalReadonly(true, AdminAudit.console("emergency"));
        control.setGlobalReadonly(false, AdminAudit.console("recovered"));

        assertFalse(control.isGlobalReadonly(), "read-only off must be effective");
        EconomyResult<Amount> d = h.service.deposit(alice, "dollar", Amount.of(10, 2));
        assertTrue(d.isSuccess(), "deposit must work again after read-only is switched off");
        assertEquals(Amount.of(1010, 2), d.value());
    }
}
