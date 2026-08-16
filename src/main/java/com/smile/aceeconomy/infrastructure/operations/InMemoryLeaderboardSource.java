package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import com.smile.aceeconomy.ports.operations.LeaderboardSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [TEST:P3] 記憶體版 {@link LeaderboardSource}：依幣別保存餘額列，可作為測試替身，
 * 亦可用於記憶體經濟模式的正式來源；後續任務可用 SQL/JSON 實作滿足同一 port 契約。
 */
public final class InMemoryLeaderboardSource implements LeaderboardSource {

    // currencyId (normalized) -> accountId -> row
    private final Map<String, Map<UUID, LeaderboardRow>> store = new ConcurrentHashMap<>();

    public void setRow(String currencyId, UUID accountId, String ownerName, Amount balance) {
        String cid = com.smile.aceeconomy.domain.Currency.normalizeId(currencyId);
        store.computeIfAbsent(cid, k -> new ConcurrentHashMap<>())
                .put(accountId, new LeaderboardRow(accountId, ownerName, balance));
    }

    public void clear() {
        store.clear();
    }

    @Override
    public List<LeaderboardRow> rows(String currencyId) {
        String cid = com.smile.aceeconomy.domain.Currency.normalizeId(currencyId);
        Map<UUID, LeaderboardRow> byAccount = store.get(cid);
        if (byAccount == null) {
            return List.of();
        }
        return List.copyOf(byAccount.values());
    }
}
