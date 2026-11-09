package com.habitrain.lottery.daily;

import java.util.List;
import java.util.Map;

/**
 * Data sent by the server; the client does not calculate balances, task completion or shop limits.
 * {@code shopReceipt} is the item id of the purchase this board answers, {@code null} for a plain refresh.
 */
public record DailyTaskSnapshot(long epochDayUtc, int greenApples,
                                Map<String, Integer> cards, int cardTotal,
                                List<TaskRow> tasks, boolean shopOpen, List<ShopRow> shop,
                                String shopReceipt,
                                long nextResetEpochSecond, String resetZoneId) {
    public DailyTaskSnapshot(long epochDayUtc, int greenApples,
                             Map<String, Integer> cards, int cardTotal,
                             List<TaskRow> tasks, boolean shopOpen, List<ShopRow> shop,
                             String shopReceipt) {
        this(epochDayUtc, greenApples, cards, cardTotal, tasks, shopOpen, shop, shopReceipt,
                (epochDayUtc + 1) * 86_400L, "UTC");
    }

    public long effectiveNextResetEpochSecond() {
        return nextResetEpochSecond > 0L ? nextResetEpochSecond : (epochDayUtc + 1) * 86_400L;
    }

    public String effectiveResetZoneId() {
        return (resetZoneId == null || resetZoneId.isBlank()) ? "UTC" : resetZoneId;
    }
    public record TaskRow(String id, String title, String description, String reward,
                          int progress, int target, boolean claimed, boolean random) { }

    /**
     * One shop listing. {@code kind} is the first reward kind (for the accent colour);
     * {@code used}/{@code resetDay} only mean something while {@code limited}; {@code resetDay}
     * is -1 for a lifetime limit. {@code matchLocked}: the item gives cards and a match is running.
     */
    public record ShopRow(String id, String title, String description, String reward, String kind,
                          int price, boolean limited, int limitCount, int limitDays, int used,
                          long resetDay, boolean owned, boolean matchLocked) { }
}
