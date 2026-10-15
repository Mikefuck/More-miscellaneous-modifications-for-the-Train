package com.habitrain.lottery.backpack;

import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

import java.util.Locale;

import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation;

/** Server-authoritative parsing and numeric rules for one-player card edits. */
public final class PlayerCardMutationPolicy {
    public static final int MAX_DELTA = 1_000;
    /** Limit for a single SET request, not the stored balance. */
    public static final int MAX_COUNT = 100_000;
    public static final int MAX_BALANCE = Integer.MAX_VALUE;

    private PlayerCardMutationPolicy() {
    }

    public static FactionCardType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "civilian" -> FactionCardType.CIVILIAN;
            case "neutral" -> FactionCardType.NEUTRAL;
            case "neutral_for_killer" -> FactionCardType.NEUTRAL_FOR_KILLER;
            case "killer" -> FactionCardType.KILLER;
            default -> null;
        };
    }

    public static void validate(CardOperation operation, int value) {
        if (operation == null) {
            throw new IllegalArgumentException("未知角色卡操作");
        }
        if (operation == CardOperation.ADD) {
            if (value == 0 || value < -MAX_DELTA || value > MAX_DELTA) {
                throw new IllegalArgumentException("角色卡增减值必须在 -1000..1000 且不能为 0");
            }
            return;
        }
        if (value < 0 || value > MAX_COUNT) {
            throw new IllegalArgumentException("角色卡目标数量必须在 0..100000");
        }
    }

    public static int targetCount(int current, CardOperation operation, int value) {
        validate(operation, value);
        return operation == CardOperation.SET ? value : addToBalance(current, value);
    }

    /** Preserves reward-granted balances above the SET limit; overflow never mutates assets. */
    public static int addToBalance(int current, int delta) {
        long target = (long) Math.max(0, current) + delta;
        if (target > MAX_BALANCE) throw new IllegalArgumentException("角色卡余额溢出");
        return (int) Math.max(0L, target);
    }
}
