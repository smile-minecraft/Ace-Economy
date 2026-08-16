package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;

import java.util.UUID;

/**
 * A single ranked leaderboard entry. Immutable; the service never exposes mutable internal state.
 */
public record LeaderboardEntry(int rank, UUID accountId, String ownerName, Amount balance) {

    public LeaderboardEntry {
        if (accountId == null) {
            throw new IllegalArgumentException("LeaderboardEntry.accountId must not be null");
        }
        if (balance == null) {
            throw new IllegalArgumentException("LeaderboardEntry.balance must not be null");
        }
        if (ownerName == null) {
            ownerName = accountId.toString();
        }
    }
}
