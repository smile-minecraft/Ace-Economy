package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Account;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only view of a player session handed back to callers (listeners, Task 12 wiring). The lifecycle
 * manager keeps the mutable {@code PlayerSession} internal; this handle is the stable, public contract.
 */
public interface PlayerSessionHandle {

    /** Lifecycle state of a single player session. */
    enum State {
        /** Load in flight; account not yet available. */
        PRELOGIN,
        /** Account loaded and cached; operations may proceed. */
        ACTIVE,
        /** Quit/flush in progress. */
        QUITTING,
        /** Session closed/removed. */
        CLOSED,
        /** Load failed; typed failure reported through {@link #ready()}. */
        FAILED
    }

    @NotNull
    UUID uuid();

    long generation();

    @NotNull
    State state();

    boolean isDirty();

    /** Completes with the loaded account, or completes exceptionally with a typed {@link SessionException}. */
    @NotNull
    CompletableFuture<Account> ready();

    @NotNull
    Optional<Account> account();
}
