package com.smile.aceeconomy;

import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.StorageHandler;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stub implementation of StorageHandler for testing purposes.
 */
public class TestStorageHandler implements StorageHandler {

    @Override
    public CompletableFuture<Account> loadAccount(UUID uuid) {
        // For testing, we can simulate no account found, or return a mock if needed.
        // Returning null simulates "account not found in database".
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveAccount(Account account) {
        // No-op for testing
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void initialize() {
        // No-op
    }

    @Override
    public void shutdown() {
        // No-op
    }
}
