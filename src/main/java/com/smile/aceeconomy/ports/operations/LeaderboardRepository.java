package com.smile.aceeconomy.ports.operations;

import java.util.List;

/**
 * Native leaderboard query port. Implementations must return rows for the
 * currency already sorted by balance descending and accountId ascending,
 * using a single ordered SQL query on the SQL backend (constant query count,
 * not N+1). The {@code operations.LeaderboardService} ranking becomes a
 * straight rank assignment when this port is used.
 */
public interface LeaderboardRepository {

    /** All rows for the currency, sorted by balance desc then accountId asc. Constant-query. */
    List<LeaderboardRow> leaderboardRows(String currencyId);

    /** Top N rows for the currency, same ordering, with SQL LIMIT. */
    List<LeaderboardRow> leaderboardTop(String currencyId, int limit);
}
