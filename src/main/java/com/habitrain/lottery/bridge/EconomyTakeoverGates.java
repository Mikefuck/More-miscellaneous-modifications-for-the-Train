package com.habitrain.lottery.bridge;

/**
 * Pure predicates for world-JSON economy takeover write gating.
 */
public final class EconomyTakeoverGates {
    private EconomyTakeoverGates() {
    }

    /**
     * Block server-side SRE economy writes when JSON takeover is not active.
     * Client-thread / LocalPlayer calls must still fall through to SRE client cache.
     */
    public static boolean blockServerWrite(boolean takeoverActive, boolean clientSide, boolean serverPlayer) {
        return !takeoverActive && !clientSide && serverPlayer;
    }
}
