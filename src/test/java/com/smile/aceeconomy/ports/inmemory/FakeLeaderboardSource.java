package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import com.smile.aceeconomy.ports.operations.LeaderboardSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** [TEST:P3] 測試替身 {@link LeaderboardSource}：附呼叫計數器，用於觀察快取行為。 */
public final class FakeLeaderboardSource implements LeaderboardSource {

    private final Map<String, List<LeaderboardRow>> rows = new HashMap<>();
    private int callCount = 0;

    public void put(String currencyId, List<LeaderboardRow> rowsForCurrency) {
        rows.put(Currency.normalizeId(currencyId), new ArrayList<>(rowsForCurrency));
    }

    public int callCount() {
        return callCount;
    }

    @Override
    public List<LeaderboardRow> rows(String currencyId) {
        callCount++;
        List<LeaderboardRow> r = rows.get(Currency.normalizeId(currencyId));
        return r == null ? List.of() : new ArrayList<>(r);
    }
}
