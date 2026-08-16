package com.smile.aceeconomy.operations;

import java.util.List;

/**
 * A page of ranked leaderboard entries plus pagination metadata. The {@code entries} list is an
 * immutable copy so callers cannot mutate the cache's internal state.
 */
public final class LeaderboardPage {

    private final List<LeaderboardEntry> entries;
    private final int page;
    private final int limit;
    private final int totalEntries;
    private final int totalPages;

    public LeaderboardPage(List<LeaderboardEntry> entries, int page, int limit, int totalEntries, int totalPages) {
        this.entries = List.copyOf(entries);
        this.page = page;
        this.limit = limit;
        this.totalEntries = totalEntries;
        this.totalPages = totalPages;
    }

    public List<LeaderboardEntry> entries() {
        return entries;
    }

    public int page() {
        return page;
    }

    public int limit() {
        return limit;
    }

    public int totalEntries() {
        return totalEntries;
    }

    public int totalPages() {
        return totalPages;
    }
}
