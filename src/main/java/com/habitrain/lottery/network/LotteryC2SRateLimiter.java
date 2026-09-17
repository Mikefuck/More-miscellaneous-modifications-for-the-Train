package com.habitrain.lottery.network;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-player C2S cooldown (same contract as habitrain_core {@code C2SRateLimiter}).
 * Local copy because {@code libs/habitrain_core.jar} may not yet contain that class.
 */
public final class LotteryC2SRateLimiter {
    private static final ConcurrentMap<String, Long> LAST_MS = new ConcurrentHashMap<>();

    private LotteryC2SRateLimiter() {
    }

    public static boolean tryAcquire(UUID playerId, String channel, long cooldownMs) {
        if (playerId == null || channel == null || cooldownMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        String slot = playerId + ":" + channel;
        boolean[] granted = {false};
        LAST_MS.compute(slot, (key, previous) -> {
            if (previous != null && now - previous < cooldownMs) {
                return previous;
            }
            granted[0] = true;
            return now;
        });
        return granted[0];
    }

    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId + ":";
        LAST_MS.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
