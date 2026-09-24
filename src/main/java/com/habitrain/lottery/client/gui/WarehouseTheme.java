package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/** Smoke glass, slate navigation, ash display trays and small green quantity labels. */
public final class WarehouseTheme {
    public static final int TEXT = 0xFFF0F0EB, MUTED = 0xFFBEC3C3, GREEN = 0xFF83D564,
            BLUE = 0xFF77B6CE, SURFACE = 0xF02D3030, BORDER = 0xFF626868;
    public static final int ACCENT_CIVILIAN = 0xFF44BB66, ACCENT_KILLER = 0xFFE2555F,
            ACCENT_NEUTRAL = 0xFFD9B23C, ACCENT_NEUTRAL_FOR_KILLER = 0xFFB07CE8,
            ACCENT_SELF_SELECT = 0xFFFFD76A, ACCENT_LIMIT_BREAK = 0xFFFFB56B;
    private WarehouseTheme() {}
    public static int alpha(int color, float opacity) {
        return (color & 0xFFFFFF) | (Math.round((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24);
    }
    public static void background(GuiGraphics g, int width, int height, float opacity) {
        g.fillGradient(0, 0, width, height, alpha(0xEF535653, opacity), alpha(0xF422282B, opacity));
        g.fillGradient(0, 0, width, height / 2, alpha(0x383F4541, opacity), 0);
        // Quiet contour lines echo the reference's topographical navigation texture.
        for (int i = 0; i < 13; i++) {
            int x = width * 3 / 4 + i * 12;
            g.hLine(x - 70, x, 6 + i * 2, alpha(0x126F7D77, opacity));
        }
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
}
