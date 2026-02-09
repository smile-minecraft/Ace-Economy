package com.smile.aceeconomy;

import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.manager.CurrencyManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CurrencyManagerTest {

    @Test
    public void testConcurrency() throws InterruptedException {
        // Setup
        TestStorageHandler storage = new TestStorageHandler();
        Logger logger = Logger.getLogger("CurrencyManagerTest");
        double initialBalance = 10000.0;
        // Constructor: StorageHandler, Logger, defaultBalance
        CurrencyManager manager = new CurrencyManager(storage, logger, 0.0);

        UUID user = UUID.randomUUID();
        // Create account directly using manager (which puts it in cache)
        manager.createAccount(user, "TestUser");
        manager.setBalance(user, initialBalance);

        int threadCount = 20;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Run concurrent operations
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    if (threadId % 2 == 0) {
                        // Even threads deposit 1.0
                        manager.deposit(user, 1.0);
                    } else {
                        // Odd threads withdraw 1.0
                        manager.withdraw(user, 1.0);
                    }
                }
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertTrue(finished, "Test timed out!");

        // Assertions
        // Half threads deposit, half withdraw.
        // Total deposits: (threadCount / 2) * operationsPerThread * 1.0
        // Total withdrawals: (threadCount / 2) * operationsPerThread * 1.0
        // Net change: 0.

        // Note: initialBalance is 10000.Withdrawals total is 10 * 1000 = 10000.
        // So balance could dip to 0, but since we start with enough, it shouldn't fail due to insufficient funds unless race condition causes double withdrawal logic error (which lock prevents).
        // Since threads run concurrently, if one withdraws and balance is low, it might fail.
        // But with 10000 start and max possible withdrawal 10000 (even if all withdraws happen before deposits), it should be fine.
        // Wait, if all 10 withdraw threads run first, they take 10000. Balance 0.
        // Then deposit threads run, adding 10000. Balance 10000.
        // So order doesn't matter for final sum as long as no withdraw fails.
        // Does withdraw fail if balance < amount? Yes.
        // But initial balance = 10000. Max total withdrawal = 10 threads * 1000 ops * 1.0 = 10000.
        // So balance will never drop below 0 provided calculations are correct.
        // So no withdraw should fail.

        double expectedBalance = initialBalance;
        double actualBalance = manager.getBalance(user);

        assertEquals(expectedBalance, actualBalance, 0.0001, "Balance mismatch due to race condition!");
    }
}
