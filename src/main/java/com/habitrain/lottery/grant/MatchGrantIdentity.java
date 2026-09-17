package com.habitrain.lottery.grant;

import java.util.UUID;

/**
 * Durable match identity for grant dedupe. Survives process restart, unlike a
 * process-local sequence counter.
 */
public final class MatchGrantIdentity {
    private MatchGrantIdentity() {
    }

    /**
     * {@code dimensionLocation + ":" + startGameTime}. Falls back to a random
     * UUID when start identity was never recorded.
     */
    public static String of(String dimensionLocation, Long startGameTime) {
        if (dimensionLocation == null || dimensionLocation.isBlank() || startGameTime == null) {
            return UUID.randomUUID().toString();
        }
        return dimensionLocation + ":" + startGameTime;
    }

    public static boolean isRecordedStart(String dimensionLocation, Long startGameTime) {
        return dimensionLocation != null && !dimensionLocation.isBlank() && startGameTime != null;
    }
}
