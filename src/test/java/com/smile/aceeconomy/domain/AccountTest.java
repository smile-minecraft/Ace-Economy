package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private Account newAccount() {
        return Account.create(UUID.randomUUID(), "Alice",
                Map.of("dollar", Amount.of(1000, 2), "token", Amount.of(5, 0)));
    }

    @Test
    @DisplayName("deposit/withdraw/set are pure transitions")
    void transitions() {
        Account a = newAccount();
        Account afterDeposit = a.deposit("dollar", Amount.of(250, 2));
        assertEquals(Amount.of(1250, 2), afterDeposit.balanceOf("dollar"));
        // original unchanged
        assertEquals(Amount.of(1000, 2), a.balanceOf("dollar"));

        Account afterWithdraw = a.withdraw("dollar", Amount.of(400, 2));
        assertEquals(Amount.of(600, 2), afterWithdraw.balanceOf("dollar"));

        Account afterSet = a.setBalance("dollar", Amount.of(50, 2));
        assertEquals(Amount.of(50, 2), afterSet.balanceOf("dollar"));
    }

    @Test
    @DisplayName("missing currency defaults to zero on transition")
    void missingCurrency() {
        Account a = Account.create(UUID.randomUUID(), "Bob", Map.of("dollar", Amount.of(1000, 2)));
        assertNull(a.balanceOf("token"));
        Account after = a.deposit("token", Amount.of(3, 0));
        assertEquals(Amount.of(3, 0), after.balanceOf("token"));
    }

    @Test
    @DisplayName("currency id lookup is normalized")
    void normalizedLookup() {
        Account a = newAccount();
        assertEquals(Amount.of(1000, 2), a.balanceOf("  DOLLAR "));
    }
}
