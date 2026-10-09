package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.TransactionListener;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Public amount-cap compatibility: every money-moving use case refuses an amount
 * above the cap <em>before</em> anything is accepted — naming the currency and the
 * cap — and leaves the stored data untouched. Mirrors the stored-scale gate
 * ({@code CURRENCY_SCALE_MISMATCH}): request, stored and resulting balances are
 * all checked, while healthy in-range traffic (including exactly at the cap) is
 * unaffected.
 */
class AmountCapCompatibilityTest {

    private static final BigDecimal CAP = new BigDecimal("1000000000000000");

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private EconomyTestHarness newHarness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    private static Amount dollar(String plain) {
        BigDecimal bd = new BigDecimal(plain);
        return Amount.of(bd, 2);
    }

    private static Amount storedDollar(EconomyTestHarness h, UUID id) {
        Account loaded = h.repo.load(id).orElseThrow(() -> new AssertionError("account missing"));
        return loaded.balanceOf("dollar");
    }

    private static final class RefusalProbe {
        final AtomicInteger preCommitEvents = new AtomicInteger();

        void attach(EconomyTestHarness h) {
            h.publisher.register((TransactionListener) event -> preCommitEvents.incrementAndGet());
        }

        void assertNothingAccepted(EconomyTestHarness h, UUID id, Amount before) {
            assertEquals(0, preCommitEvents.get(), "refused transaction must not fire pre-commit");
            assertTrue(h.audit.recorded().isEmpty(), "refused transaction must not audit");
            assertEquals(before, storedDollar(h, id), "stored balance must be untouched");
        }
    }

    private static void assertCapRefusal(EconomyResult<?> r, String currencyId) {
        assertTrue(r.isFailure(), "over-cap amount must be refused, got success");
        assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, r.error());
        assertNotNull(r.message());
        assertTrue(r.message().contains(currencyId),
                "refusal must name the currency, got: " + r.message());
        assertTrue(r.message().contains(CAP.toPlainString()),
                "refusal must name the cap, got: " + r.message());
    }

    @Test
    @DisplayName("deposit of cap+1 is refused before accept; balance untouched")
    void depositRequestOverCapRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", dollar("1000000000000001.00"));

        assertCapRefusal(r, "dollar");
        probe.assertNothingAccepted(h, alice, before);
    }

    @Test
    @DisplayName("deposit of a huge amount is refused")
    void depositHugeAmountRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", dollar("100000000000000000000.00"));

        assertCapRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("deposit that would push the balance over the cap is refused; nothing moves")
    void depositResultOverCapRefused() {
        EconomyTestHarness h = newHarness();
        // Seed near-cap directly (no audit), so the probe sees only the refused call.
        h.repo.create(alice, "Alice", Map.of(
                "dollar", dollar("999999999999900.00"),
                "token", Amount.of(0, 0)));
        Amount before = storedDollar(h, alice);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", dollar("200.00"));

        assertCapRefusal(r, "dollar");
        probe.assertNothingAccepted(h, alice, before);
    }

    @Test
    @DisplayName("exactly at the cap succeeds: the boundary is inclusive")
    void atCapSucceeds() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");

        EconomyResult<Amount> set = h.service.setBalance(alice, "dollar", dollar("1000000000000000.00"));
        assertTrue(set.isSuccess(), "at-cap setBalance must succeed: " + set.message());

        EconomyResult<Amount> back = h.service.withdraw(alice, "dollar", dollar("0.01"));
        assertTrue(back.isSuccess(), "withdraw from cap must succeed: " + back.message());
        assertEquals(dollar("999999999999999.99"), back.value());
    }

    @Test
    @DisplayName("withdraw of cap+1 is refused even though the balance would shrink")
    void withdrawRequestOverCapRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", dollar("1000000000000001.00"));

        assertCapRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("setBalance above the cap is refused instead of storing over it")
    void setBalanceOverCapRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.setBalance(alice, "dollar", dollar("1000000000000000.01"));

        assertCapRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("transfer of cap+1 is refused; neither leg moves")
    void transferRequestOverCapRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        Amount beforeSender = storedDollar(h, alice);
        Amount beforeReceiver = storedDollar(h, bob);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<TransferResult> r =
                h.service.transfer(alice, bob, "dollar", dollar("1000000000000001.00"));

        assertCapRefusal(r, "dollar");
        assertEquals(0, probe.preCommitEvents.get(), "refused transfer must not fire pre-commit");
        assertTrue(h.audit.recorded().isEmpty(), "refused transfer must not audit");
        assertEquals(beforeSender, storedDollar(h, alice), "sender balance must be untouched");
        assertEquals(beforeReceiver, storedDollar(h, bob), "receiver balance must be untouched");
    }

    @Test
    @DisplayName("transfer receiver overflow is refused with the cap error when funds cover it")
    void transferReceiverOverflowNamesCap() {
        EconomyTestHarness h = newHarness();
        // Seed directly (no audit): Alice covers the 200, Bob sits 100 below the cap.
        h.repo.create(alice, "Alice", Map.of(
                "dollar", dollar("500.00"),
                "token", Amount.of(0, 0)));
        h.repo.create(bob, "Bob", Map.of(
                "dollar", dollar("999999999999900.00"),
                "token", Amount.of(0, 0)));
        Amount beforeSender = storedDollar(h, alice);
        Amount beforeReceiver = storedDollar(h, bob);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<TransferResult> r = h.service.transfer(alice, bob, "dollar", dollar("200.00"));

        assertCapRefusal(r, "dollar");
        assertEquals(0, probe.preCommitEvents.get(), "refused transfer must not fire pre-commit");
        assertTrue(h.audit.recorded().isEmpty(), "refused transfer must not audit");
        assertEquals(beforeSender, storedDollar(h, alice), "sender balance must be untouched");
        assertEquals(beforeReceiver, storedDollar(h, bob), "receiver balance must be untouched");
    }

    @Test
    @DisplayName("stored over-cap balance refuses the next transaction with a diagnosis; data kept")
    void storedOverCapDiagnosed() {
        EconomyTestHarness h = newHarness();
        // Bypass the service (in-memory repo has no write guard) to model a legacy
        // row that already exceeds the cap: it must diagnose, never silently serve.
        h.repo.create(alice, "Alice", Map.of(
                "dollar", dollar("2000000000000000.00"),
                "token", Amount.of(0, 0)));
        Amount before = storedDollar(h, alice);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", dollar("1.00"));

        assertCapRefusal(r, "dollar");
        assertTrue(r.message().contains("stored"),
                "stored over-cap must say the stored data is at fault, got: " + r.message());
        probe.assertNothingAccepted(h, alice, before);
    }

    private static AtomicRedemptionStore untouchableStore() {
        return new AtomicRedemptionStore() {
            @Override
            public com.smile.aceeconomy.ports.persistence.RedemptionResult redeem(
                    UUID nonce, UUID accountId, String currencyId, Amount amount)
                    throws PersistenceException {
                throw new AssertionError("refused redeem must never reach the store");
            }

            @Override
            public com.smile.aceeconomy.ports.persistence.RedemptionResult redeemPrepared(
                    UUID nonce, Account account,
                    com.smile.aceeconomy.domain.Transaction transaction,
                    DebtPolicy debtPolicy) throws PersistenceException {
                throw new AssertionError("refused redeem must never reach the store");
            }
        };
    }

    @Test
    @DisplayName("redeemBanknote of cap+1 is refused before the store")
    void redeemOverCapRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.redeemBanknote(
                UUID.randomUUID(), alice, "dollar", dollar("1000000000000001.00"), untouchableStore());

        assertCapRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("compensateUndeliveredNote of cap+1 is refused before the store")
    void compensateOverCapRefused() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.compensateUndeliveredNote(
                UUID.randomUUID(), alice, "dollar", dollar("1000000000000001.00"),
                untouchableStore(), "test-refund");

        assertCapRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("cap applies per currency scale: token cap+1 refused, at-cap token allowed")
    void tokenScaleBoundary() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");

        EconomyResult<Amount> over =
                h.service.setBalance(alice, "token", Amount.of(1000000000000001L, 0));
        assertTrue(over.isFailure());
        assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, over.error());

        EconomyResult<Amount> atCap =
                h.service.setBalance(alice, "token", Amount.of(1000000000000000L, 0));
        assertTrue(atCap.isSuccess(), "at-cap token setBalance must succeed: " + atCap.message());
    }
}
