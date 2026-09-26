package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 「Steam 库存」视觉系统。全部取值来自参考视频（反恐精英武器箱界面）的逐帧实测：
 * 见 {@code .video-analysis/SPEC.md} §1（仓库格栅）与 §4（颜色与字体系统）。
 *
 * <p>近黑 {@code #101410} 背景 + 顶部等高线纹理 {@code #2E332F} + 网格暗场 {@code #1A1D1A}；
 * 主导航活跃 teal {@code #4FD1C5} + 1px 下划线；次级/三级活跃为浅 teal 药丸 {@code #7FD4CB}；
 * 下拉框 field {@code #1A1F1E} + 1px {@code #3A4442} 描边；瓦片解剖为 {@code #454944} 图标井 +
 * {@code #5A6058} 顶部高光 + {@code #2B302C} 底边 + {@code #2E332F} 说明条。</p>
 *
 * <p>录制噪声一律不绘制：左上角水印、bilibili 头像栏、底部黄色字幕、编码幽灵残影、鼠标指针。</p>
 */
public final class WarehouseTheme {
    /* ---- §4 参考配色 ---- */
    /** 背景近黑。 */
    public static final int BACKDROP = 0xFF101410;
    /** 顶部等高线纹理。 */
    public static final int CONTOUR = 0xFF2E332F;
    /** 网格区暗场。 */
    public static final int FIELD = 0xFF1A1D1A;
    /** 下拉框 / 面板 field。 */
    public static final int SURFACE = 0xFF1A1F1E;
    /** 1px 描边。 */
    public static final int BORDER = 0xFF3A4442;
    /** 主导航活跃色 + 下划线。 */
    public static final int TEAL = 0xFF4FD1C5;
    /** 次级 / 三级活跃药丸。 */
    public static final int TEAL_PILL = 0xFF7FD4CB;
    /** 非活跃主导航文字。 */
    public static final int NAV_OFF = 0xFFB9C4C2;
    /** 非活跃次级标签文字。 */
    public static final int TAB_OFF = 0xFF8A9490;
    /** 大标题。 */
    public static final int TEXT = 0xFFEDEDED;
    /** 面板标题（26 semibold）。 */
    public static final int TITLE = 0xFFF2F2F2;
    /** 正文（17 / 19）。 */
    public static final int BODY = 0xFFD2D2D2;
    /** 瓦片物品名（18）。 */
    public static final int NAME = 0xFF959492;
    /** 说明条文字（17）。 */
    public static final int CAPTION_TEXT = 0xFF9B9B9B;
    /** 说明条底。 */
    public static final int CAPTION_BAR = 0xFF2E332F;
    /** 图标井卡片。 */
    public static final int WELL = 0xFF454944;
    /** 图标井 1px 顶部高光。 */
    public static final int WELL_TOP = 0xFF5A6058;
    /** 图标井 2px 底边。 */
    public static final int WELL_EDGE = 0xFF2B302C;
    /** 次级信息 / 状态条文字。 */
    public static final int MUTED = 0xFF8A9490;
    /** 主按钮（参考视频确认弹窗的 #4CAF50）。 */
    public static final int GREEN = 0xFF4CAF50;
    /** 警示 / 橙色正文行。 */
    public static final int WARN = 0xFFD98A4A;
    /** 特殊物品金。 */
    public static final int GOLD = 0xFFE7D06A;
    /** 开箱选中金线。 */
    public static final int SELECT_LINE = 0xFFE8D44D;
    /** 历史调用点保留（旧蓝色高亮）。 */
    public static final int BLUE = 0xFF77B6CE;
    /** 次级面板。 */
    public static final int SURFACE_SOFT = 0xFF23282A;

    public static final int ACCENT_CIVILIAN = 0xFF44BB66, ACCENT_KILLER = 0xFFE2555F,
            ACCENT_NEUTRAL = 0xFFD9B23C, ACCENT_NEUTRAL_FOR_KILLER = 0xFFB07CE8,
            ACCENT_SELF_SELECT = 0xFFFFD76A, ACCENT_LIMIT_BREAK = 0xFFFFB56B;

    private WarehouseTheme() {
    }

    public static int alpha(int color, float opacity) {
        return (color & 0xFFFFFF) | (Math.round((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24);
    }

    /**
     * 参考视频的背景：整屏近黑 + 顶部等高线纹理，{@code fieldTop} 以下是网格暗场。
     *
     * @param fieldTop 网格暗场的起始 y（等于顶部 chrome 的下沿）
     */
    public static void background(GuiGraphics g, int width, int height, int fieldTop, float opacity) {
        g.fill(0, 0, width, height, alpha(BACKDROP, opacity));
        int band = Math.max(6, Math.min(fieldTop, height));
        contours(g, width, height, band, opacity);
        g.fill(0, band, width, height, alpha(FIELD, opacity));
        // 暗场上沿留一点层次，避免 chrome 与网格之间出现硬边
        g.fillGradient(0, band, width, Math.min(height, band + Math.max(4, height / 24)),
                alpha(BACKDROP, opacity), alpha(BACKDROP, 0));
    }

    /** 顶部区域的同心等高线（浅灰 #2E332F 1px 线），只画左右两处穿越点。 */
    private static void contours(GuiGraphics g, int width, int height, int band, float opacity) {
        int color = alpha(CONTOUR, opacity * .75F);
        float cx = width * .5F, cy = -height * .06F;
        for (int i = 0; i < 9; i++) {
            float a = width * (.24F + i * .095F);
            float b = height * (.20F + i * .075F);
            for (int y = 0; y <= band; y += 3) {
                float t = (y - cy) / b;
                if (t <= -1F || t >= 1F) continue;
                float half = a * (float) Math.sqrt(Math.max(0F, 1F - t * t));
                int wobble = Math.round((float) Math.sin(y * .09F + i * 1.7F) * 3F);
                int lx = Math.round(cx - half) + wobble;
                int rx = Math.round(cx + half) + wobble;
                g.hLine(lx, lx + 10, y, color);
                g.hLine(rx - 10, rx, y, color);
            }
        }
    }

    /** 参考视频的下拉框 / 输入框：field + 1px 描边 + 圆角。 */
    public static void field(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, float opacity) {
        GuiFx.roundRect(g, x0, y0, x1, y1, radius, alpha(SURFACE, opacity));
        GuiFx.roundOutline(g, x0, y0, x1, y1, radius, alpha(BORDER, opacity));
    }

    /** 参考视频的活跃药丸：浅 teal、圆角、深色文字。 */
    public static void pill(GuiGraphics g, int x0, int y0, int x1, int y1, float opacity) {
        int radius = Math.max(2, Math.min(4, (y1 - y0) / 3));
        GuiFx.roundRect(g, x0, y0, x1, y1, radius, alpha(TEAL_PILL, opacity));
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

    // =====================================================================
    // 字形（全部用矩形/扫描线手绘，不依赖字体里是否存在这些符号）
    // =====================================================================

    /** 参考视频左上角图标行的尺寸基准：26px @1080p。 */
    public static int iconSize(int rowHeight) {
        return Math.max(6, Math.min(rowHeight - 4, Math.max(8, Math.round(18 * rowHeight / 30F))));
    }

    /** 开锁 / 闭锁：{@code open} 时只画一侧锁环，形状与颜色同时区分状态。 */
    public static void glyphLock(GuiGraphics g, int x, int y, int s, int color, boolean open) {
        if (s < 5) {
            g.fill(x, y, x + s, y + s, color);
            return;
        }
        int bodyTop = y + Math.max(2, s / 2);
        int shW = Math.max(3, s - Math.max(2, s / 3));
        int sx = x + (s - shW) / 2;
        g.hLine(sx, sx + shW, y, color);
        g.fill(sx, y, sx + 1, bodyTop, color);
        if (!open) g.fill(sx + shW - 1, y, sx + shW, bodyTop, color);
        g.fill(x, bodyTop, x + s, y + s, color);
    }

    /** ⇅ 排序方向字形。 */
    public static void glyphSort(GuiGraphics g, int x, int y, int s, int color) {
        int col = Math.max(1, s / 6);
        int head = Math.max(3, s / 2);
        g.fill(x, y + head - 1, x + col, y + s, color);
        triangle(g, x + col / 2, y, head, false, color);
        g.fill(x + s - col, y, x + s, y + s - head + 1, color);
        triangle(g, x + s - col / 2, y + s - head, head, true, color);
    }

    /** ∨ 下拉箭头。 */
    public static void glyphCaretDown(GuiGraphics g, int cx, int y, int s, int color) {
        triangle(g, cx, y, Math.max(3, s / 2), true, color);
    }

    /** ← 返回。 */
    public static void glyphBack(GuiGraphics g, int x, int y, int s, int color) {
        int mid = y + s / 2;
        g.fill(x, mid, x + s, mid + 1, color);
        for (int i = 0; i <= s / 2; i++) {
            g.fill(x + i, mid - i, x + i + 1, mid - i + 1, color);
            g.fill(x + i, mid + i, x + i + 1, mid + i + 1, color);
        }
    }

    /** ↻ 刷新（缺口的圆环 + 箭头）。 */
    public static void glyphRefresh(GuiGraphics g, int x, int y, int s, int color) {
        int r = Math.max(2, (s - 1) / 2);
        int cx = x + s / 2, cy = y + s / 2;
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, color);
            if (dy > -r / 2) g.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
        int head = Math.max(2, s / 4);
        for (int i = 0; i < head; i++) g.fill(cx + r - i, cy - r + i, cx + r - i + 2, cy - r + i + 1, color);
    }

    /** ▶ / ❚❚ 动效开关。 */
    public static void glyphMotion(GuiGraphics g, int x, int y, int s, int color, boolean playing) {
        int pad = Math.max(1, s / 5);
        int w = Math.max(2, s - pad * 2);
        if (playing) {
            int bar = Math.max(1, w / 3);
            g.fill(x + pad, y + pad, x + pad + bar, y + s - pad, color);
            g.fill(x + s - pad - bar, y + pad, x + s - pad, y + s - pad, color);
        } else {
            for (int i = 0; i < w; i++) {
                int half = Math.max(1, Math.round((s - pad * 2) / 2F * (i + 1) / w));
                g.fill(x + pad + i, y + s / 2 - half, x + pad + i + 1, y + s / 2 + half, color);
            }
        }
    }

    /** ✕ 关闭。 */
    public static void glyphClose(GuiGraphics g, int x, int y, int s, int color) {
        for (int i = 0; i < s; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + s - 1 - i, y + i, x + s - i, y + i + 1, color);
        }
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

    private static void triangle(GuiGraphics g, int cx, int top, int size, boolean down, int color) {
        for (int i = 0; i < size; i++) {
            int half = (down ? size - i : i + 1) / 2;
            g.fill(cx - half, top + i, cx + half + 1, top + i + 1, color);
        }
    }
}
