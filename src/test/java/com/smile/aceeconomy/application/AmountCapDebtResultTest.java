package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.TransactionListener;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Result-side cap gate for balance-shrinking operations. When debt is enabled
 * with a limit above the cap, a withdraw (or the sender leg of a transfer)
 * can land below minus-the-cap; that outcome must be refused at the service
 * layer with {@link EconomyError#AMOUNT_EXCEEDS_CAP} <em>before</em> any
 * pre-commit event fires — never as a storage exception from the backend
 * write guard.
 */
class AmountCapDebtResultTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private static Amount dollar(String plain) {
        return Amount.of(new BigDecimal(plain), 2);
    }

    /** Debt limit (2e15) deliberately larger than the cap (1e15), so the cap is what refuses. */
    private static final BigDecimal DEBT_LIMIT = new BigDecimal("2000000000000000");

    private static DebtPolicy debtPolicy() {
        return DebtPolicy.enabled(Amount.of(DEBT_LIMIT, 0));
    }

    private EconomyTestHarness newDebtHarness() {
        return new EconomyTestHarness(debtPolicy(), Amount.of(1000, 2));
    }

    /**
     * Precondition for the refusal tests: the debt policy itself must permit the candidate.
     * Otherwise a debt refusal could pass for the cap refusal under test.
     */
    private static void assertDebtPolicyPermits(Amount candidate) {
        assertTrue(debtPolicy().allows(candidate),
                "precondition: debt policy must permit " + candidate.value().toPlainString());
    }

    private static Amount storedDollar(EconomyTestHarness h, UUID id) {
        Account loaded = h.repo.load(id).orElseThrow(() -> new AssertionError("account missing"));
        return loaded.balanceOf("dollar");
    }

    private void seed(EconomyTestHarness h, UUID id, String name, String balance) {
        h.repo.create(id, name, Map.of("dollar", dollar(balance), "token", Amount.of(0, 0)));
    }

    private static void assertCapRefusal(EconomyResult<?> r) {
        assertTrue(r.isFailure(), "over-cap result must be refused, got success");
        assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, r.error());
        assertNotNull(r.message());
        assertTrue(r.message().contains("1000000000000000"),
                "refusal must name the cap, got: " + r.message());
    }

    @Test
    @DisplayName("withdraw landing below minus-the-cap is refused at the service layer")
    void withdrawNegativeResultOverCapRefused() {
        EconomyTestHarness h = newDebtHarness();
        seed(h, alice, "Alice", "-1000000000000000.00");
        Amount before = storedDollar(h, alice);
        AtomicInteger events = new AtomicInteger();
        h.publisher.register((TransactionListener) event -> events.incrementAndGet());

        // Debt allows -1000000000000001.00 (limit is -2e15), but the cap does not.
        assertDebtPolicyPermits(dollar("-1000000000000001.00"));
        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", dollar("1.00"));

        assertCapRefusal(r);
        assertEquals(0, events.get(), "refused withdraw must not fire pre-commit");
        assertTrue(h.audit.recorded().isEmpty(), "refused withdraw must not audit");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("transfer whose sender leg lands below minus-the-cap is refused; neither moves")
    void transferSenderNegativeResultOverCapRefused() {
        EconomyTestHarness h = newDebtHarness();
        seed(h, alice, "Alice", "-1000000000000000.00");
        seed(h, bob, "Bob", "0.00");
        Amount beforeSender = storedDollar(h, alice);
        Amount beforeReceiver = storedDollar(h, bob);
        AtomicInteger events = new AtomicInteger();
        h.publisher.register((TransactionListener) event -> events.incrementAndGet());

        // Sender leg lands at -1000000000000001.00: the debt policy must permit it, so only the cap refuses.
        assertDebtPolicyPermits(dollar("-1000000000000001.00"));
        EconomyResult<TransferResult> r = h.service.transfer(alice, bob, "dollar", dollar("1.00"));

        assertCapRefusal(r);
        assertEquals(0, events.get(), "refused transfer must not fire pre-commit");
        assertTrue(h.audit.recorded().isEmpty(), "refused transfer must not audit");
        assertEquals(beforeSender, storedDollar(h, alice), "sender balance must be untouched");
        assertEquals(beforeReceiver, storedDollar(h, bob), "receiver balance must be untouched");
    }

    @Test
    @DisplayName("in-range negative results still succeed under debt")
    void negativeResultWithinCapSucceeds() {
        EconomyTestHarness h = newDebtHarness();
        seed(h, alice, "Alice", "-500.00");

        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", dollar("100.00"));

        assertTrue(r.isSuccess(), "in-range debt withdraw must succeed: " + r.message());
        assertEquals(dollar("-600.00"), r.value());
    }

    @Test
    @DisplayName("withdraw landing exactly on minus-the-cap is allowed (boundary inclusive)")
    void withdrawNegativeResultAtCapSucceeds() {
        EconomyTestHarness h = newDebtHarness();
        seed(h, alice, "Alice", "-999999999999999.00");

        // The result is exactly -1e15. The cap is inclusive, so this must succeed.
        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", dollar("1.00"));

        assertTrue(r.isSuccess(), "result exactly at minus-the-cap must succeed: " + r.message());
        assertEquals(dollar("-1000000000000000.00"), r.value());
    }

    @Test
    @DisplayName("debt limit below the cap is still enforced by the debt policy")
    void debtLimitStillEnforced() {
        EconomyTestHarness smallDebt = new EconomyTestHarness(
                DebtPolicy.enabled(Amount.of(200, 2)), Amount.of(1000, 2));
        seed(smallDebt, alice, "Alice", "-100.00");

        EconomyResult<Amount> r = smallDebt.service.withdraw(alice, "dollar", dollar("150.00"));

        assertTrue(r.isFailure());
        assertEquals(EconomyError.DEBT_LIMIT_EXCEEDED, r.error());
    }
}
