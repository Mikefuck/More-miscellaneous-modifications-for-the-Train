package com.habitrain.lottery.api.player;

import com.habitrain.lottery.backpack.PlayerCardMutationPolicy;

/**
 * Pure numeric rules shared by the player API and the in-game admin UI.
 *
 * <p>Card limits intentionally mirror {@link PlayerCardMutationPolicy} so an
 * external mod cannot bypass the bounds the admin screen enforces.</p>
 */
public final class HabiAssetPolicy {
    /** Largest coin / draw balance the API will persist. */
    public static final int MAX_CURRENCY = Integer.MAX_VALUE;
    /** Largest allowed absolute card balance. */
    public static final int MAX_CARD_COUNT = PlayerCardMutationPolicy.MAX_COUNT;
    /** Largest allowed additive card delta (absolute value). */
    public static final int MAX_CARD_DELTA = PlayerCardMutationPolicy.MAX_DELTA;

    private HabiAssetPolicy() {
    }

    /** Currencies accept any additive delta and non-negative absolute values. */
    public static boolean isValidCurrencyOperation(HabiAssetOperation operation, int value) {
        if (operation == null) {
            return false;
        }
        if (operation == HabiAssetOperation.SET) {
            return value >= 0;
        }
        return value != 0;
    }

    /** Applies an ADD/SET to a currency balance, clamped into {@code [0, MAX_CURRENCY]}. */
    public static int applyCurrency(int current, HabiAssetOperation operation, int value) {
        long target = operation == HabiAssetOperation.SET ? value : (long) current + value;
        if (target < 0L) {
            target = 0L;
        }
        if (target > MAX_CURRENCY) {
            target = MAX_CURRENCY;
        }
        return (int) target;
    }

    /**
     * Card balances accept {@code ADD} deltas in {@code [-1000, 1000]} (never 0)
     * and absolute values in {@code [0, 100000]}.
     */
    public static boolean isValidCardOperation(HabiAssetOperation operation, int value) {
        if (operation == null) {
            return false;
        }
        if (operation == HabiAssetOperation.ADD) {
            return value != 0 && value >= -MAX_CARD_DELTA && value <= MAX_CARD_DELTA;
        }
        return value >= 0 && value <= MAX_CARD_COUNT;
    }

    /** Applies an ADD/SET to a card balance, clamped into {@code [0, MAX_CARD_COUNT]}. */
    public static int applyCardCount(int current, HabiAssetOperation operation, int value) {
        long target = operation == HabiAssetOperation.SET
                ? value
                : (long) Math.max(0, current) + value;
        if (target < 0L) {
            target = 0L;
        }
        if (target > MAX_CARD_COUNT) {
            target = MAX_CARD_COUNT;
        }
        return (int) target;
    }

    /**
     * Remaining uses for one UTC day: {@code limit + bonus - used}, floored at 0.
     */
    public static int dailyRemaining(int usedToday, int bonusToday, int dailyLimit) {
        long limit = Math.max(0, dailyLimit);
        long bonus = Math.max(0L, Math.min(Integer.MAX_VALUE - limit, (long) bonusToday));
        long used = Math.max(0, usedToday);
        return (int) Math.max(0L, limit + bonus - used);
    }
}
