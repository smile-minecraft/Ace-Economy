package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoryServiceTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);

    private Transaction tx(UUID id, UUID account, String currency, TransactionType type,
                           Instant ts, String reason) {
        return new Transaction(id, account, null, currency, Amount.of(1, 2), type,
                Amount.of(0, 2), Amount.of(1, 2), ts, reason);
    }

    @Test
    void filtersByAccountAndCurrency() {
        UUID a1 = new UUID(0, 1);
        UUID a2 = new UUID(0, 2);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(new UUID(0, 10), a1, "coin", TransactionType.DEPOSIT, T, "p1"));
        repo.append(tx(new UUID(0, 11), a1, "gem", TransactionType.DEPOSIT, T, "p2"));
        repo.append(tx(new UUID(0, 12), a2, "coin", TransactionType.DEPOSIT, T, "p3"));

        HistoryService svc = new HistoryService(repo);
        AuditPage page = svc.query(AuditQuery.builder()
                .accountId(a1).currencyId("COIN").page(0).limit(50).build());

        assertEquals(1, page.total());
        assertEquals(1, page.entries().size());
        assertEquals(a1, page.entries().get(0).accountId());
        assertEquals("coin", page.entries().get(0).currencyId());
    }

    @Test
    void ordersByTimestampThenIdDescending() {
        UUID account = new UUID(0, 1);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        UUID u1 = new UUID(0, 1), u2 = new UUID(0, 2), u3 = new UUID(0, 3);
        // Same timestamp, different ids -> stable tie-break by id.
        repo.append(tx(u1, account, "coin", TransactionType.DEPOSIT, T, "a"));
        repo.append(tx(u2, account, "coin", TransactionType.DEPOSIT, T, "b"));
        repo.append(tx(u3, account, "coin", TransactionType.DEPOSIT, T, "c"));

        HistoryService svc = new HistoryService(repo);
        AuditPage page = svc.query(AuditQuery.builder().page(0).limit(50).build());

        assertEquals(3, page.total());
        // Default ordering is descending: largest id first.
        assertEquals(u3, page.entries().get(0).id());
        assertEquals(u2, page.entries().get(1).id());
        assertEquals(u1, page.entries().get(2).id());
    }

    @Test
    void paginatesWithStableTotal() {
        UUID account = new UUID(0, 1);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        for (int i = 0; i < 5; i++) {
            repo.append(tx(new UUID(0, i + 1), account, "coin", TransactionType.DEPOSIT, T, "r" + i));
        }
        HistoryService svc = new HistoryService(repo);
        AuditPage p0 = svc.query(AuditQuery.builder().page(0).limit(2).build());
        assertEquals(5, p0.total());
        assertEquals(2, p0.entries().size());

        AuditPage p2 = svc.query(AuditQuery.builder().page(2).limit(2).build());
        assertEquals(1, p2.entries().size());

        AuditPage p3 = svc.query(AuditQuery.builder().page(3).limit(2).build());
        assertEquals(0, p3.entries().size());
    }

    @Test
    void reasonContainsIsCaseInsensitive() {
        UUID account = new UUID(0, 1);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(new UUID(0, 1), account, "coin", TransactionType.DEPOSIT, T, "Bonus Payout"));
        repo.append(tx(new UUID(0, 2), account, "coin", TransactionType.DEPOSIT, T, "monthly fee"));

        HistoryService svc = new HistoryService(repo);
        AuditPage page = svc.query(AuditQuery.builder().reasonContains("PAYOUT").page(0).limit(50).build());
        assertEquals(1, page.total());
        assertEquals("Bonus Payout", page.entries().get(0).reason());
    }

    @Test
    void rejectsInvalidPagination() {
        HistoryService svc = new HistoryService(new InMemoryTransactionRepository());
        assertThrows(IllegalArgumentException.class,
                () -> svc.query(AuditQuery.builder().page(-1).limit(10).build()));
        assertThrows(IllegalArgumentException.class,
                () -> svc.query(AuditQuery.builder().page(0).limit(0).build()));
    }
}
