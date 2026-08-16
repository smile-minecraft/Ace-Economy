package com.smile.aceeconomy.ports.operations;

import com.smile.aceeconomy.domain.Amount;

import java.util.UUID;

/**
 * A single normalized balance row for a currency, sourced from the account store. The leaderboard
 * query/cache operates on these rows; the source implementation (in-memory, SQL or JSON) is
 * hidden behind {@link LeaderboardSource}.
 */
public record LeaderboardRow(UUID accountId, String ownerName, Amount balance) {

    public LeaderboardRow {
        if (accountId == null) {
            throw new IllegalArgumentException("LeaderboardRow.accountId must not be null");
        }
        if (balance == null) {
            throw new IllegalArgumentException("LeaderboardRow.balance must not be null");
        }
        if (ownerName == null) {
            ownerName = accountId.toString();
        }
    }
}
