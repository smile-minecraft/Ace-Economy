package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.commands.v2.CommandModels.LeaderboardEntry;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Async leaderboard query boundary for {@code /baltop}. */
public interface LeaderboardQueryService {

    CompletableFuture<List<LeaderboardEntry>> top(String currencyId, int page, int pageSize);

    /** Page size used when formatting the leaderboard. */
    int pageSize();
}
