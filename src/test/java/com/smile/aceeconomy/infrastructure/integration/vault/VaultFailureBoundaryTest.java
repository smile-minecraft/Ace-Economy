package com.smile.aceeconomy.infrastructure.integration.vault;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.ports.persistence.CommittedTransferException;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Failure-boundary contract for the Vault adapter: a storage failure must become an
 * explicit Vault outcome, never an escaped exception — and a committed outcome must
 * never be reported as an ordinary retryable failure, nor invent a receipt.
 */
class VaultFailureBoundaryTest {

    private final EconomyTestHarness harness =
            new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));

    private VaultEconomyProvider providerWith(EconomyApi api) {
        return new VaultEconomyProvider(api, harness.currencies());
    }

    private OfflinePlayer player(UUID uuid, String name) {
        OfflinePlayer p = mock(OfflinePlayer.class);
        when(p.getUniqueId()).thenReturn(uuid);
        when(p.getName()).thenReturn(name);
        return p;
    }

    private static PersistenceException uncommitted(String message) {
        return new PersistenceException(message, new RuntimeException("injected io"));
    }

    private static PersistenceException committed(String message) {
        return new PersistenceException(message, new RuntimeException("injected cleanup"), true);
    }

    @Test
    void lookupFailureIsFailClosedInsteadOfEscaping() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.loadAccount(any(UUID.class))).thenThrow(uncommitted("Failed to load account"));
        VaultEconomyProvider provider = providerWith(api);

        assertFalse(provider.hasAccount(player(UUID.randomUUID(), "Alice")));
    }

    @Test
    void createFailureIsFailClosedInsteadOfEscaping() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.createAccount(any(UUID.class), anyString()))
                .thenThrow(uncommitted("Failed to create account"));
        VaultEconomyProvider provider = providerWith(api);

        assertFalse(provider.createPlayerAccount(player(UUID.randomUUID(), "Newbie")));
    }

    @Test
    void depositStorageFailureIsExplicitFailureInsteadOfEscaping() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(uncommitted("Failed to save account"));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r;
        try {
            r = provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0);
        } catch (RuntimeException e) {
            fail("a storage failure must not escape Vault, got " + e);
            return;
        }
        assertFalse(r.transactionSuccess());
        assertEquals(0.0, r.amount, 0.0001, "failure must not claim an amount");
        assertNotNull(r.errorMessage);
    }

    @Test
    void withdrawStorageFailureIsExplicitFailureInsteadOfEscaping() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.withdraw(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(uncommitted("Failed to save account"));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r;
        try {
            r = provider.withdrawPlayer(player(UUID.randomUUID(), "Bob"), 10.0);
        } catch (RuntimeException e) {
            fail("a storage failure must not escape Vault, got " + e);
            return;
        }
        assertFalse(r.transactionSuccess());
        assertEquals(0.0, r.amount, 0.0001, "failure must not claim an amount");
        assertNotNull(r.errorMessage);
    }

    @Test
    void committedDepositIsNotReportedAsAnOrdinaryRetryableFailure() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(committed("Save committed successfully, but restoring auto-commit failed"));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r;
        try {
            r = provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0);
        } catch (RuntimeException e) {
            fail("even a committed outcome must not escape Vault, got " + e);
            return;
        }
        assertFalse(r.transactionSuccess(), "Vault cannot claim success without a receipt");
        assertEquals(0.0, r.amount, 0.0001, "a committed outcome must not invent a receipt amount");
        assertNotNull(r.errorMessage);
        String wording = r.errorMessage.toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("committed"),
                () -> "a committed outcome must say so, got: " + r.errorMessage);
        assertTrue(wording.contains("retry"),
                () -> "a committed outcome must warn against retrying, got: " + r.errorMessage);
    }

    @Test
    void committedWithdrawIsNotReportedAsAnOrdinaryRetryableFailure() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.withdraw(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(committed("Withdraw committed successfully, but restoring auto-commit failed"));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r = provider.withdrawPlayer(player(UUID.randomUUID(), "Bob"), 10.0);
        assertFalse(r.transactionSuccess());
        String wording = r.errorMessage.toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("committed"),
                () -> "a committed outcome must say so, got: " + r.errorMessage);
        assertTrue(wording.contains("retry"),
                () -> "a committed outcome must warn against retrying, got: " + r.errorMessage);
    }

    @Test
    void committedTransferReceiptIsNeverMistakenForARetryableFailure() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferResult receipt = new TransferResult(from, to,
                Amount.of(90L, 2), Amount.of(110L, 2), UUID.randomUUID(), UUID.randomUUID());
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new CommittedTransferException(
                        "Transfer committed successfully, but post-commit cleanup failed",
                        new RuntimeException("injected cleanup"), receipt));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r = provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0);
        assertFalse(r.transactionSuccess());
        String wording = r.errorMessage.toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("committed"),
                () -> "a committed outcome must say so, got: " + r.errorMessage);
        assertTrue(wording.contains("retry"),
                () -> "a committed outcome must warn against retrying, got: " + r.errorMessage);
    }

    @Test
    void unexpectedRuntimeFailureNeverEscapesMutations() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new IllegalStateException("bug"));
        when(api.withdraw(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new IllegalStateException("bug"));
        VaultEconomyProvider provider = providerWith(api);

        assertFalse(provider.depositPlayer(player(UUID.randomUUID(), "A"), 1.0).transactionSuccess());
        assertFalse(provider.withdrawPlayer(player(UUID.randomUUID(), "A"), 1.0).transactionSuccess());
    }

    @Test
    void unexpectedRuntimeFailureKeepsLookupsFailClosed() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.loadAccount(any(UUID.class))).thenThrow(new IllegalStateException("bug"));
        when(api.createAccount(any(UUID.class), anyString())).thenThrow(new IllegalStateException("bug"));
        VaultEconomyProvider provider = providerWith(api);

        assertFalse(provider.hasAccount(player(UUID.randomUUID(), "Alice")));
        assertFalse(provider.createPlayerAccount(player(UUID.randomUUID(), "Newbie")));
    }

    @Test
    void successfulResultsStillPassThroughUnchanged() {
        UUID uuid = UUID.randomUUID();
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenReturn(EconomyResult.success(Amount.of(50, 2)));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r = provider.depositPlayer(player(uuid, "Alice"), 50.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);
        assertEquals(50.0, r.balance, 0.0001);
        assertEquals(Optional.empty(), api.cachedBalance(uuid, "dollar"));
    }

    @Test
    void indeterminateDepositWarnsAgainstRetryingInsteadOfOrdinaryFailure() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new IndeterminatePersistenceException(
                        "Save may have committed although its confirmation was lost",
                        new RuntimeException("injected commit response lost")));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r = provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0);
        assertFalse(r.transactionSuccess(), "Vault cannot claim success without a receipt");
        assertEquals(0.0, r.amount, 0.0001, "an uncertain outcome must not invent a receipt amount");
        String wording = r.errorMessage.toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("uncertain"),
                () -> "an uncertain outcome must say so, got: " + r.errorMessage);
        assertTrue(wording.contains("retry"),
                () -> "an uncertain outcome must warn against retrying, got: " + r.errorMessage);
    }

    @Test
    void indeterminateWithdrawWarnsAgainstRetryingInsteadOfOrdinaryFailure() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.withdraw(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new IndeterminatePersistenceException(
                        "Save may have committed although its confirmation was lost",
                        new RuntimeException("injected commit response lost")));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r = provider.withdrawPlayer(player(UUID.randomUUID(), "Bob"), 10.0);
        assertFalse(r.transactionSuccess());
        String wording = r.errorMessage.toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("uncertain"),
                () -> "an uncertain outcome must say so, got: " + r.errorMessage);
        assertTrue(wording.contains("retry"),
                () -> "an uncertain outcome must warn against retrying, got: " + r.errorMessage);
    }

    @Test
    void playerReplyNeverCarriesInternalPaths() {
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new PersistenceException(
                        "Failed to persist banknote redemption; the new data was already published"
                                + " (file=/secret/place/data.json)",
                        new RuntimeException("injected io")));
        VaultEconomyProvider provider = providerWith(api);

        EconomyResponse r = provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0);
        assertFalse(r.transactionSuccess());
        assertNotNull(r.errorMessage);
        assertFalse(r.errorMessage.contains("/secret/place"),
                () -> "internal paths must not reach the player reply, got: " + r.errorMessage);
        assertFalse(r.errorMessage.contains("data.json"),
                () -> "internal file names must not reach the player reply, got: " + r.errorMessage);
    }

    @Test
    void diagnosticsKeepTheOriginalThrowableWithItsSuppressed() {
        PersistenceException storage = new PersistenceException(
                "Failed to save account (file=/secret/place/data.json)",
                new RuntimeException("injected io"));
        storage.addSuppressed(new java.io.IOException("rollback blew up"));
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class))).thenThrow(storage);
        VaultEconomyProvider provider = providerWith(api);

        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(VaultEconomyProvider.class.getName());
        java.util.List<java.util.logging.LogRecord> records =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
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
            provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0);
        } finally {
            logger.removeHandler(handler);
        }
        assertTrue(records.stream().anyMatch(record -> record.getThrown() == storage),
                "the original throwable must be attached to the diagnostic record");
        assertTrue(records.stream()
                        .filter(record -> record.getThrown() == storage)
                        .flatMap(record -> java.util.Arrays.stream(record.getThrown().getSuppressed()))
                        .anyMatch(suppressed -> suppressed.getMessage().contains("rollback blew up")),
                "suppressed cleanup failures must stay on the diagnostic record");
    }

    @Test
    void storageExceptionCarryingErrorCauseEscapesInsteadOfFailure() {
        AssertionError carried = new AssertionError("injected Error");
        EconomyApi api = mock(EconomyApi.class);
        when(api.deposit(any(UUID.class), anyString(), any(Amount.class)))
                .thenThrow(new IndeterminatePersistenceException(
                        "Save may have committed although its confirmation was lost", carried));
        VaultEconomyProvider provider = providerWith(api);

        AssertionError escaped = assertThrows(AssertionError.class,
                () -> provider.depositPlayer(player(UUID.randomUUID(), "Alice"), 50.0),
                "an Error carried by a storage failure must escape Vault,"
                        + " never become a business result");
        assertSame(carried, escaped, "the original Error must escape untouched");
    }

    @Test
    void lookupStorageExceptionCarryingErrorCauseEscapesInsteadOfFailClosed() {
        AssertionError carried = new AssertionError("injected Error");
        EconomyApi api = mock(EconomyApi.class);
        when(api.loadAccount(any(UUID.class)))
                .thenThrow(new PersistenceException("Failed to load account", carried));
        VaultEconomyProvider provider = providerWith(api);

        AssertionError escaped = assertThrows(AssertionError.class,
                () -> provider.hasAccount(player(UUID.randomUUID(), "Alice")),
                "an Error carried by a storage failure must escape Vault,"
                        + " never become a fail-closed default");
        assertSame(carried, escaped, "the original Error must escape untouched");
    }
}
