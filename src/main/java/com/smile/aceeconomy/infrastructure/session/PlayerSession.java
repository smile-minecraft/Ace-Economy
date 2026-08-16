package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.ports.PlayerSessionHandle;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mutable internal session state for one UUID. Package-private: callers receive the public
 * {@link PlayerSessionHandle} view. Each session carries a monotonic {@code generation} so a late or
 * stale load result can be discarded instead of overwriting a newer session.
 */
final class PlayerSession implements PlayerSessionHandle {

    final UUID uuid;
    final long generation;
    final Player player;
    volatile PlayerSessionHandle.State state;
    volatile Account account;
    volatile boolean dirty;
    final CompletableFuture<Account> ready = new CompletableFuture<>();

    PlayerSession(UUID uuid, long generation, Player player) {
        this.uuid = uuid;
        this.generation = generation;
        this.player = player;
        this.state = PlayerSessionHandle.State.PRELOGIN;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public PlayerSessionHandle.State state() {
        return state;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public CompletableFuture<Account> ready() {
        return ready;
    }

    @Override
    public java.util.Optional<Account> account() {
        return java.util.Optional.ofNullable(account);
    }
}
