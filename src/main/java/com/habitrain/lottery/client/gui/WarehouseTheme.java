package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 仓库的视觉系统，直接沿用开箱终端的语汇：失焦庭院 + 暗场与暗角、金色眉题线、
 * 玻璃展柜（{@link CrateArt#glassPanel} / {@link CrateArt#stripPanel}）、品质卡片
 * （{@link CrateArt#rarityCard}）、斜切角按钮与标签（{@link CrateArt#primaryButton} /
 * {@link CrateArt#ghostButton} / {@link CrateArt#chip}）。文字配色与开箱终端完全一致。
 */
public final class WarehouseTheme {
    /* ---- 文字（与 CrateOpenScreen 同值） ---- */
    public static final int TEXT_BRIGHT = 0xFFF2F2F2, TEXT_BODY = 0xFFD2D2D2, TEXT_DIM = 0xFFA9B3B1,
            TEXT_MUTED = 0xFF7D8785, TEXT_GOLD = 0xFFF0D36E, DANGER = 0xFFFF8A8A;
    /** 次强调色（开箱终端底栏中段、钥匙标签）。 */
    public static final int TEAL = 0xFF4FD1C5;
    /** 没有品质的普通奖励所用的中性卡色。 */
    public static final int NEUTRAL = 0xFF8E9A9C;
    /** 绿苹果货币。 */
    public static final int APPLE = 0xFF7CC45A;

    public static final int ACCENT_CIVILIAN = 0xFF44BB66, ACCENT_KILLER = 0xFFE2555F,
            ACCENT_NEUTRAL = 0xFFD9B23C, ACCENT_NEUTRAL_FOR_KILLER = 0xFFB07CE8,
            ACCENT_SELF_SELECT = 0xFFFFD76A, ACCENT_LIMIT_BREAK = 0xFFFFB56B;

    private WarehouseTheme() {
    }

    public static int alpha(int color, float opacity) {
        return (color & 0xFFFFFF) | (Math.round((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24);
    }

    public static int cardColor(String id) {
        return switch (id) {
            case "civilian" -> ACCENT_CIVILIAN;
            case "killer" -> ACCENT_KILLER;
            case "neutral" -> ACCENT_NEUTRAL;
            case "neutral_for_killer" -> ACCENT_NEUTRAL_FOR_KILLER;
            case "self_select" -> ACCENT_SELF_SELECT;
            default -> ACCENT_LIMIT_BREAK;
        };
    }

    /**
     * 舞台背景：开箱终端的庭院，按 {@code focus} 从失焦合到景深，再压暗成展柜的底；
     * 顶部一束暖色聚光、左右与上下压暗、椭圆暗角把视线收到展柜。
     */
    public static void stage(GuiGraphics g, int width, int height, float focus) {
        g.fill(0, 0, width, height, 0xFF0B0D0C);
        CrateArt.backdrop(g, width, height, focus, 0, 0, 0.9F);
        g.fill(0, 0, width, height, 0xB8060808);
        CrateFx.spotlight(g, width * .5F, -height * .08F, height * .8F, width * .07F, width * .34F, 0xFFFFE2B0, 0.10F);
        CrateFx.vGradient(g, 0, 0, width, height * .28F, 0xA0050606, 0x00050606, false);
        CrateFx.vGradient(g, 0, height * .62F, width, height, 0x00050606, 0xC0050606, false);
        CrateFx.hGradient(g, 0, 0, width * .22F, height, 0x80050606, 0x00050606, false);
        CrateFx.hGradient(g, width * .78F, 0, width, height, 0x00050606, 0x80050606, false);
        CrateFx.vignette(g, width, height, width * .5F, height * .5F, width * .36F, height * .34F,
                width * .78F, height * .82F, 0xFF010202, 0.55F);
    }

    /** 放大镜。 */
    public static void glyphSearch(GuiGraphics g, int x, int y, int s, int color) {
        int r = Math.max(2, s / 2 - 1);
        int cx = x + r, cy = y + r;
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, color);
            g.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
        int handle = Math.max(2, s - r * 2);
        for (int i = 0; i < handle; i++) {
            int px = Math.min(x + s - 1, cx + r + i - 1), py = Math.min(y + s - 1, cy + r + i - 1);
            g.fill(px, py, px + 2, py + 1, color);
        }
    }
}
