package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.operations.internal.FailedMintRefund;
import com.smile.aceeconomy.operations.internal.InMemoryTrustedRefundLedger;
import com.smile.aceeconomy.operations.internal.TrustedRefundLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A proven failed mint is refunded even while its account is frozen or the server is
 * read-only; an ordinary deposit through the public path stays refused. The exemption
 * carries exactly one proven deduction and never becomes a general bypass.
 */
class TrustedRefundExemptionTest {

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    @Test
    @DisplayName("proven failed mint is refunded while the account is frozen")
    void trustedRefundSucceedsUnderFreeze() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        h.service().bindSecurityControl(control);
        h.service().bindRefundLedger(ledger);
        // The deduction has committed and the mint observably failed without exposing a note.
        h.service().withdraw(alice, "dollar", Amount.of(200, 2));
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        FailedMintRefund proof = FailedMintRefund.proven(
                alice, "dollar", Amount.of(200, 2), nonce);
        EconomyResult<Amount> refund = h.service().refundFailedMint(proof);
        assertTrue(refund.isSuccess(), "proven failed mint must be refunded despite the freeze");
        assertEquals(Amount.of(1000, 2), refund.value());
        assertEquals(Amount.of(1000, 2), h.service().getBalance(alice, "dollar").value());
    }

    @Test
    @DisplayName("proven failed mint is refunded while the server is read-only")
    void trustedRefundSucceedsUnderReadonly() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        h.service().bindSecurityControl(control);
        h.service().bindRefundLedger(ledger);
        h.service().withdraw(alice, "dollar", Amount.of(200, 2));
        control.setGlobalReadonly(true, AdminAudit.console("emergency"));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        FailedMintRefund proof = FailedMintRefund.proven(
                alice, "dollar", Amount.of(200, 2), nonce);
        EconomyResult<Amount> refund = h.service().refundFailedMint(proof);
        assertTrue(refund.isSuccess(), "proven failed mint must be refunded despite read-only");
        assertEquals(Amount.of(1000, 2), refund.value());
    }

    @Test
    @DisplayName("ordinary deposit stays refused while the trusted refund succeeds")
    void ordinaryDepositStaysRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        h.service().createAccount(alice, "Alice");
        SecurityControlService control = new SecurityControlService();
        TrustedRefundLedger ledger = new InMemoryTrustedRefundLedger();
        h.service().bindSecurityControl(control);
        h.service().bindRefundLedger(ledger);
        h.service().withdraw(alice, "dollar", Amount.of(200, 2));
        control.freeze(alice, AdminAudit.console("incident"));

        UUID nonce = UUID.randomUUID();
        ledger.authorize(nonce, alice, "dollar", Amount.of(200, 2));
        FailedMintRefund proof = FailedMintRefund.proven(
                alice, "dollar", Amount.of(200, 2), nonce);
        EconomyResult<Amount> refund = h.service().refundFailedMint(proof);
        assertTrue(refund.isSuccess(), "authorized refund must succeed despite the freeze");

        EconomyResult<Amount> external = h.service().deposit(alice, "dollar", Amount.of(50, 2));
        assertTrue(external.isFailure(), "ordinary deposit must not ride the refund exemption");
        assertEquals(EconomyError.ACCOUNT_FROZEN, external.error());
        assertEquals(Amount.of(1000, 2), h.service().getBalance(alice, "dollar").value());
    }
}
