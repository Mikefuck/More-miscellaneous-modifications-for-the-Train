package com.habitrain.lottery.daily;

import java.util.List;
import java.util.Map;

/** Data sent by the server; the client does not calculate balances or task completion. */
public record DailyTaskSnapshot(long epochDayUtc, int draws, int coins,
                                Map<String, Integer> cards, int cardTotal,
                                List<TaskRow> tasks, List<SourceRow> sources) {
    public record TaskRow(String id, String title, String description, String reward,
                          int progress, int target, boolean claimed) { }
    public record SourceRow(String category, String title, String detail) { }
}
