package com.habitrain.lottery.client.gui;

/** Monotonic-time animation curves. Input stays locked while the visible grid is displaced. */
public final class WarehouseMotion {
    public static final long OPEN_MS = 520, SWITCH_MS = 320;
    private WarehouseMotion() {}
    public static float progress(long now, long start, long duration) {
        return Math.max(0, Math.min(1, (now - start) / (float) duration));
    }
    public static float ease(float t) { return 1 - (float) Math.pow(1 - Math.max(0, Math.min(1, t)), 3); }
    public static float reveal(long now, long start, int order) {
        return ease(progress(now, start + Math.min(order, 12) * 17L, 300));
    }
}
