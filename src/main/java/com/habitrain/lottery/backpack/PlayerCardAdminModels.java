package com.habitrain.lottery.backpack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure DTOs shared by card-admin storage, networking and UI code. */
public final class PlayerCardAdminModels {
    private PlayerCardAdminModels() {
    }

    public enum CardStoreStatus {
        ONLINE_LIVE,
        STORED,
        MISSING,
        CORRUPT
    }

    public enum CardOperation {
        ADD,
        SET;

        public static CardOperation parse(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    public record CardSnapshot(CardStoreStatus status, Map<String, Integer> cards) {
        public CardSnapshot {
            status = status == null ? CardStoreStatus.MISSING : status;
            // Map.copyOf 不保证迭代顺序，会把 orderedCards() 排好的「乘客 → 独立 → 中立偏杀手
            // → 杀手 → 自选 → 突破上限」打乱。用不可变 LinkedHashMap 保住稳定顺序。
            cards = cards == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cards));
        }
    }

    public record CardMutationResult(
            boolean ok,
            String message,
            CardStoreStatus status,
            int newCount
    ) {
        public CardMutationResult {
            message = message == null ? "" : message;
            status = status == null ? CardStoreStatus.MISSING : status;
            newCount = Math.max(0, newCount);
        }

        public static CardMutationResult success(CardStoreStatus status, int newCount) {
            return new CardMutationResult(true, "", status, newCount);
        }

        public static CardMutationResult failure(String message, CardStoreStatus status) {
            return new CardMutationResult(false, message, status, 0);
        }
    }

    public static Map<String, Integer> orderedCards(Map<String, Integer> source) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        ordered.put("civilian", count(source, "civilian"));
        ordered.put("neutral", count(source, "neutral"));
        ordered.put("neutral_for_killer", count(source, "neutral_for_killer"));
        ordered.put("killer", count(source, "killer"));
        ordered.put("self_select", count(source, "self_select"));
        ordered.put("limit_break", count(source, "limit_break"));
        return ordered;
    }

    private static int count(Map<String, Integer> source, String key) {
        if (source == null) {
            return 0;
        }
        return Math.max(0, source.getOrDefault(key, 0));
    }
}
