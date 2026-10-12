package com.smile.aceeconomy.domain;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of the server-local safety control.
 *
 * <p>A snapshot names every frozen account, whether the whole server is read-only,
 * whether the control file failed to load, and a monotonic version. The state itself
 * carries no audit data; control audits travel with it in the same file publish.
 */
public final class SecurityControlState {

    private final Set<UUID> frozenAccounts;
    private final boolean globalReadonly;
    private final boolean loadFailed;
    private final long version;

    public SecurityControlState(Set<UUID> frozenAccounts, boolean globalReadonly,
                                boolean loadFailed, long version) {
        this.frozenAccounts = Collections.unmodifiableSet(
                new HashSet<>(Objects.requireNonNull(frozenAccounts, "frozenAccounts")));
        this.globalReadonly = globalReadonly;
        this.loadFailed = loadFailed;
        this.version = version;
    }

    /** Open state used on first start when no control file exists yet. */
    public static SecurityControlState open() {
        return new SecurityControlState(Set.of(), false, false, 0L);
    }

    /** Fail-closed state used when the control file cannot be read. */
    public static SecurityControlState loadFailed(String diagnosis) {
        Objects.requireNonNull(diagnosis, "diagnosis");
        return new SecurityControlState(Set.of(), true, true, -1L);
    }

    public boolean isFrozen(UUID accountId) {
        return accountId != null && frozenAccounts.contains(accountId);
    }

    public boolean isGlobalReadonly() {
        return globalReadonly;
    }

    public boolean isLoadFailed() {
        return loadFailed;
    }

    public Set<UUID> frozenAccounts() {
        return frozenAccounts;
    }

    public long version() {
        return version;
    }
}
