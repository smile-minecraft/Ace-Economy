package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Account;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async account load/flush seam with single-flight per UUID. The lifecycle manager owns session
 * identity and generation; this store owns the I/O executor and the per-UUID in-flight dedupe so the
 * underlying repository is never called twice for the same UUID at once.
 */
public interface SessionStore {

    /** Single-flight async load. Concurrent callers for the same UUID share one underlying load. */
    @NotNull
    CompletableFuture<Account> load(@NotNull UUID uuid);

    /** Async flush (persist) of a dirty account. */
    @NotNull
    CompletableFuture<Void> flush(@NotNull Account account);

    /** Drop any cached/loaded state for the UUID. */
    void invalidate(@NotNull UUID uuid);

    /** Best-effort cancel of any in-flight load for the UUID. */
    void cancelLoad(@NotNull UUID uuid);
}
