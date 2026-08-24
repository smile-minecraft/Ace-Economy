package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;
import com.smile.aceeconomy.operations.HistoryService;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring contract for the production history adapter: it must delegate to
 * {@link HistoryService} over the configured {@code TransactionRepository}, run on the
 * supplied executor and never mutate the underlying store.
 */
class ProductionAdaptersHistoryTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);

    @Test
    void queriesThroughHistoryServiceOnTheProvidedExecutorWithoutMutatingTheStore() {
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        UUID account = UUID.randomUUID();
        repo.append(new Transaction(UUID.randomUUID(), account, null, "dollar",
                Amount.of(10, 2), TransactionType.DEPOSIT, Amount.of(990, 2), Amount.of(1000, 2),
                T, "bonus"));
        repo.append(new Transaction(UUID.randomUUID(), account, null, "token",
                Amount.of(5, 0), TransactionType.WITHDRAW, Amount.of(5, 0), Amount.of(0, 0),
                T, "purchase"));

        ProductionAdapters.History history = new ProductionAdapters.History(
                new HistoryService(repo), Runnable::run);

        AuditPage page = history.query(AuditQuery.builder()
                .accountId(account)
                .currencyId("DOLLAR")
                .page(0)
                .limit(10)
                .build()).join();

        assertEquals(1, page.total(), "currency filter must reach the repository query");
        assertEquals("dollar", page.entries().get(0).currencyId());
        assertEquals(2, repo.all().size(), "history queries must be read-only");
        assertTrue(page.entries().get(0).id() != null);
    }
}
