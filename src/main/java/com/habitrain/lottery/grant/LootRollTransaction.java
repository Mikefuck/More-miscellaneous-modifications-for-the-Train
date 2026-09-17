package com.habitrain.lottery.grant;

import com.habitrain.lottery.storage.PlayerLotteryData;

import java.util.Map;

/**
 * Pure helpers for the pre-debit lottery roll path (no Minecraft types).
 */
public final class LootRollTransaction {
    private LootRollTransaction() {
    }

    public static boolean isValidRoll(Integer first) {
        return first != null && first != -1;
    }

    /**
     * Whether {@code rollOnce} already flushed a skin unlock or coin award
     * relative to {@code before} (snapshot at attempt start, before or after debit).
     */
    public static boolean awardPersisted(PlayerLotteryData before, PlayerLotteryData after) {
        if (before == null || after == null) {
            return false;
        }
        if (after.coinNum > before.coinNum) {
            return true;
        }
        return unlockedExpanded(before.unlocked, after.unlocked);
    }

    /**
     * Refund the pre-debited chance only when the attempt did not succeed
     * and no skin/coin award was flushed. Valid rolls keep the debit.
     * Thrown failures that already flushed an award also keep the debit.
     */
    public static boolean shouldRefundChance(boolean validRoll, boolean awardPersisted) {
        if (validRoll) {
            return false;
        }
        return !awardPersisted;
    }

    static boolean unlockedExpanded(
            Map<String, Map<String, Boolean>> before,
            Map<String, Map<String, Boolean>> after) {
        if (after == null || after.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Map<String, Boolean>> typeEntry : after.entrySet()) {
            Map<String, Boolean> skins = typeEntry.getValue();
            if (skins == null) {
                continue;
            }
            Map<String, Boolean> prior = before == null ? null : before.get(typeEntry.getKey());
            for (Map.Entry<String, Boolean> skinEntry : skins.entrySet()) {
                if (!Boolean.TRUE.equals(skinEntry.getValue())) {
                    continue;
                }
                if (prior == null || !Boolean.TRUE.equals(prior.get(skinEntry.getKey()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
