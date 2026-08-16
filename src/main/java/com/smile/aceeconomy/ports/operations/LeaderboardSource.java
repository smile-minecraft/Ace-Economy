package com.smile.aceeconomy.ports.operations;

import java.util.List;

/**
 * Source of leaderboard rows for a given currency. Implementations return every account's balance
 * for that currency; the {@code operations.LeaderboardService} ranks and paginates them.
 *
 * <p>This is the shared port contract exercised by the leaderboard tests; a SQL/JSON-backed
 * implementation can satisfy the same contract in a later task.</p>
 */
public interface LeaderboardSource {

    /** All balance rows for the currency, or an empty list when none exist / the currency is unknown. */
    List<LeaderboardRow> rows(String currencyId);
}
