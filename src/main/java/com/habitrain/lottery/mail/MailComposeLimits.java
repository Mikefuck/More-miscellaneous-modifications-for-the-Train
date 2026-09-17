package com.habitrain.lottery.mail;

import io.netty.handler.codec.DecoderException;

/** Decode caps for mail compose C2S (targets / rewards / amounts). */
public final class MailComposeLimits {
    public static final int MAX_REWARDS = 32;
    public static final int MAX_EXPIRES_DAYS = 365;
    public static final int MIN_REWARD_AMOUNT = -100000;
    public static final int MAX_REWARD_AMOUNT = 100000;

    private MailComposeLimits() {
    }

    public static int requireCount(int n, int max, String name) {
        if (n < 0) {
            throw new DecoderException("MailCompose " + name + " count negative: " + n);
        }
        if (n > max) {
            throw new DecoderException("MailCompose " + name + " count " + n + " exceeds max " + max);
        }
        return n;
    }

    public static int clampExpiresDays(int days) {
        if (days < 0) {
            return 0;
        }
        if (days > MAX_EXPIRES_DAYS) {
            return MAX_EXPIRES_DAYS;
        }
        return days;
    }

    public static int clampRewardAmount(int amount) {
        return Math.max(MIN_REWARD_AMOUNT, Math.min(MAX_REWARD_AMOUNT, amount));
    }
}
