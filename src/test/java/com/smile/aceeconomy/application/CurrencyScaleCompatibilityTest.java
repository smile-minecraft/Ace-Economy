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
 * Stored-scale compatibility: when a persisted balance carries a different scale than the
 * configured currency, every money-moving use case must refuse <em>before</em> anything is
 * accepted — naming the currency — and leave the stored data untouched.
 *
 * <p>Persistence backends load amounts with the scale found in storage
 * ({@code Amount.of(stored, stored.scale())}), so a config scale change (or a hand-edited
 * row) reaches the service as a scale mismatch. Without this guard the mismatch escapes as
 * an unchecked {@code IllegalArgumentException} from {@code Amount} arithmetic halfway
 * through the operation (or, for {@code setBalance}, silently stores a new wrong-scale
 * row on top of it).
 */
class CurrencyScaleCompatibilityTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private EconomyTestHarness newHarness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    /**
     * Seed an account whose stored dollar balance uses scale 3 while the configured
     * dollar scale is 2 — the shape a stale row has after a scale change without migration.
     */
    private void seedMismatchedDollar(EconomyTestHarness h, UUID id, String name) {
        h.repo.create(id, name, Map.of(
                "dollar", Amount.of(new BigDecimal("10.125"), 3),
                "token", Amount.of(0, 0)));
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

    private static void assertScaleRefusal(EconomyResult<?> r, String currencyId) {
        assertTrue(r.isFailure(), "scale mismatch must be refused, got success");
        assertEquals(EconomyError.CURRENCY_SCALE_MISMATCH, r.error());
        assertNotNull(r.message());
        assertTrue(r.message().contains(currencyId),
                "refusal must name the currency, got: " + r.message());
    }

    @Test
    @DisplayName("deposit is refused before accept when the stored scale differs; data untouched")
    void depositRefusedWhenStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        seedMismatchedDollar(h, alice, "Alice");
        Amount before = storedDollar(h, alice);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(100, 2));

        assertScaleRefusal(r, "dollar");
        probe.assertNothingAccepted(h, alice, before);
    }

    @Test
    @DisplayName("withdraw is refused before accept when the stored scale differs; data untouched")
    void withdrawRefusedWhenStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        seedMismatchedDollar(h, alice, "Alice");
        Amount before = storedDollar(h, alice);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<Amount> r = h.service.withdraw(alice, "dollar", Amount.of(100, 2));

        assertScaleRefusal(r, "dollar");
        probe.assertNothingAccepted(h, alice, before);
    }

    @Test
    @DisplayName("setBalance is refused when the stored scale differs instead of storing over it")
    void setBalanceRefusedWhenStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        seedMismatchedDollar(h, alice, "Alice");
        Amount before = storedDollar(h, alice);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<Amount> r = h.service.setBalance(alice, "dollar", Amount.of(100, 2));

        assertScaleRefusal(r, "dollar");
        probe.assertNothingAccepted(h, alice, before);
    }

    @Test
    @DisplayName("transfer is refused when the sender stored scale differs; neither leg moves")
    void transferRefusedWhenSenderStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        seedMismatchedDollar(h, alice, "Alice");
        h.service.createAccount(bob, "Bob");
        Amount beforeSender = storedDollar(h, alice);
        Amount beforeReceiver = storedDollar(h, bob);
        RefusalProbe probe = new RefusalProbe();
        probe.attach(h);

        EconomyResult<TransferResult> r =
                h.service.transfer(alice, bob, "dollar", Amount.of(100, 2));

        assertScaleRefusal(r, "dollar");
        assertEquals(0, probe.preCommitEvents.get(), "refused transfer must not fire pre-commit");
        assertTrue(h.audit.recorded().isEmpty(), "refused transfer must not audit");
        assertEquals(beforeSender, storedDollar(h, alice), "sender balance must be untouched");
        assertEquals(beforeReceiver, storedDollar(h, bob), "receiver balance must be untouched");
    }

    @Test
    @DisplayName("transfer is refused when the receiver stored scale differs; neither leg moves")
    void transferRefusedWhenReceiverStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        seedMismatchedDollar(h, bob, "Bob");
        Amount beforeSender = storedDollar(h, alice);
        Amount beforeReceiver = storedDollar(h, bob);

        EconomyResult<TransferResult> r =
                h.service.transfer(alice, bob, "dollar", Amount.of(100, 2));

        assertScaleRefusal(r, "dollar");
        assertEquals(beforeSender, storedDollar(h, alice), "sender balance must be untouched");
        assertEquals(beforeReceiver, storedDollar(h, bob), "receiver balance must be untouched");
    }

    @Test
    @DisplayName("deposit is refused when the request scale differs from config; stored row kept")
    void depositRefusedWhenRequestScaleDiffers() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r =
                h.service.deposit(alice, "dollar", Amount.of(new BigDecimal("1.5"), 1));

        assertScaleRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("healthy deposit still succeeds: the guard must not reject legal transactions")
    void healthyDepositStillSucceeds() {
        EconomyTestHarness h = newHarness();
        h.service.createAccount(alice, "Alice");

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(250, 2));

        assertTrue(r.isSuccess(), "legal deposit must succeed: " + r.message());
        assertEquals(Amount.of(1250, 2), r.value());
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
    @DisplayName("redeemBanknote is refused before accept when the stored scale differs")
    void redeemRefusedWhenStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        seedMismatchedDollar(h, alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.redeemBanknote(
                UUID.randomUUID(), alice, "dollar", Amount.of(50, 2), untouchableStore());

        assertScaleRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }

    @Test
    @DisplayName("compensateUndeliveredNote is refused when the stored scale differs")
    void compensateRefusedWhenStoredScaleDiffers() {
        EconomyTestHarness h = newHarness();
        seedMismatchedDollar(h, alice, "Alice");
        Amount before = storedDollar(h, alice);

        EconomyResult<Amount> r = h.service.compensateUndeliveredNote(
                UUID.randomUUID(), alice, "dollar", Amount.of(50, 2),
                untouchableStore(), "test-refund");

        assertScaleRefusal(r, "dollar");
        assertEquals(before, storedDollar(h, alice), "stored balance must be untouched");
    }
}
