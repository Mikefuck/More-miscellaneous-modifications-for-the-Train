package com.habitrain.lottery.client.gui;

/** Settlement boundary is one second before the actual reel stop, or an explicit skip. */
public final class CrateAnimationSettlement {
    public static final long SUCCESS_BEFORE_STOP_MS = 1000L;
    private CrateAnimationSettlement() {}

    public static boolean succeeds(CrateStage stage, long now, boolean explicitlySkipped) {
        if (stage == null || !stage.active()) return false;
        if (explicitlySkipped) return true;
        return stage.hasResult() && now >= stage.stopAt() - SUCCESS_BEFORE_STOP_MS;
    }
}
