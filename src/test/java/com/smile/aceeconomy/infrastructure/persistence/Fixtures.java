package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Shared domain builders for persistence tests. */
public final class Fixtures {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Fixtures() {
    }

    public static Amount amt(String v) {
        BigDecimal bd = new BigDecimal(v);
        return Amount.of(bd, bd.scale());
    }

    public static Transaction tx(UUID id, UUID account, UUID counterparty, String currency,
                                 Amount amount, TransactionType type, Amount before, Amount after) {
        return new Transaction(id, account, counterparty, currency, amount, type, before, after, T0, "test");
    }
}
