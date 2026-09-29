package com.habitrain.lottery.daily;

import java.util.List;
import java.util.Map;

/** Data sent by the server; the client does not calculate balances, task completion or shop limits. */
public record DailyTaskSnapshot(long epochDayUtc, int greenApples,
                                Map<String, Integer> cards, int cardTotal,
                                List<TaskRow> tasks, boolean shopOpen, List<ShopRow> shop) {
    public record TaskRow(String id, String title, String description, String reward,
                          int progress, int target, boolean claimed, boolean random) { }

    /**
     * One shop listing. {@code kind} is the first reward kind (for the accent colour);
     * {@code used}/{@code resetDay} only mean something while {@code limited}; {@code resetDay}
     * is -1 for a lifetime limit.
     */
    public record ShopRow(String id, String title, String description, String reward, String kind,
                          int price, boolean limited, int limitCount, int limitDays, int used,
                          long resetDay, boolean owned) { }
}
