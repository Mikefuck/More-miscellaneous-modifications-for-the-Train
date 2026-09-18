package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 自绘 GUI 的动画与绘制工具箱。
 *
 * <p>原版 {@link GuiGraphics} 只提供直角矩形与线性渐变；本类在其之上补齐了「现代扁平
 * 卡片」界面需要的基础件：圆角、双层渐变、柔光、斜向高光、菱形与裁剪。所有方法都是
 * 纯粹的即时绘制，不持有状态，因此可以在任意 Screen / Widget 里复用。</p>
 *
 * <p>约定：本类不修改 {@code RenderSystem} 全局状态，也不依赖实例缓存；颜色一律使用
 * {@code 0xAARRGGBB}，透明度直接参与合成，避免依赖着色器颜色带来的跨版本差异。</p>
 */
public final class GuiFx {

    private GuiFx() {
    }

    // =====================================================================
    // 时间与缓动
    // =====================================================================

    /** 把毫秒时间戳映射为 0→1 的进度并夹取；{@code duration <= 0} 直接返回 1。 */
    public static float progress(long nowMillis, long startMillis, float durationMillis) {
        if (durationMillis <= 0.0F) {
            return 1.0F;
        }
        return clamp01((nowMillis - startMillis) / durationMillis);
    }

    /** 延迟 {@code delayMillis} 后再用 {@code durationMillis} 走完的进度。 */
    public static float progress(long nowMillis, long startMillis, float delayMillis, float durationMillis) {
        return progress(nowMillis, startMillis + (long) delayMillis, durationMillis);
    }

    public static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    /** 三次缓出：起步快、收尾稳，适合面板与卡片的入场。 */
    public static float easeOutCubic(float t) {
        float x = clamp01(t) - 1.0F;
        return x * x * x + 1.0F;
    }

    /** 五次缓出：比 cubic 更「跟手」，用于位移。 */
    public static float easeOutQuint(float t) {
        float x = clamp01(t) - 1.0F;
        return x * x * x * x * x + 1.0F;
    }

    /** 回弹缓出：允许轻微过冲，用于点击反馈。 */
    public static float easeOutBack(float t) {
        float x = clamp01(t) - 1.0F;
        float c = 1.70158F;
        return 1.0F + (c + 1.0F) * x * x * x + c * x * x;
    }

    /** 缓入：用于退场。 */
    public static float easeInCubic(float t) {
        float x = clamp01(t);
        return x * x * x;
    }

    /**
     * 与帧率无关的指数趋近，替代固定系数的 {@code Mth.lerp}。
     *
     * @param halfLifeMillis 数值衰减到一半所需的毫秒数
     */
    public static float approach(float current, float target, float deltaMillis, float halfLifeMillis) {
        if (halfLifeMillis <= 0.0F) {
            return target;
        }
        float factor = 1.0F - (float) Math.pow(0.5D, deltaMillis / halfLifeMillis);
        return current + (target - current) * clamp01(factor);
    }

    /** 0→1→0 的脉冲；{@code t} 超出 0..1 时为 0。 */
    public static float pulse(float t) {
        return (float) Math.sin(clamp01(t) * Math.PI);
    }

    /** 三角波，用于「呼吸」类循环动画。 */
    public static float triWave(long nowMillis, float periodMillis, float phase) {
        if (periodMillis <= 0.0F) {
            return 0.0F;
        }
        float t = ((nowMillis / periodMillis) + phase) % 1.0F;
        return t < 0.5F ? t * 2.0F : (1.0F - t) * 2.0F;
    }

    // =====================================================================
    // 颜色
    // =====================================================================

    /** 按 alpha 缩放颜色透明度（{@code alpha} 取 0..255）。 */
    public static int alpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 按比例缩放颜色透明度（{@code 0..1}）。 */
    public static int fade(int color, float factor) {
        int a = color >>> 24;
        return (Mth.clamp((int) (a * clamp01(factor)), 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 线性混色，保留 {@code from} 的 alpha。 */
    public static int mix(int from, int to, float amount) {
        float t = clamp01(amount);
        int r1 = from >> 16 & 0xFF, g1 = from >> 8 & 0xFF, b1 = from & 0xFF;
        int r2 = to >> 16 & 0xFF, g2 = to >> 8 & 0xFF, b2 = to & 0xFF;
        return ((from >>> 24) << 24)
                | (Mth.clamp((int) (r1 + (r2 - r1) * t), 0, 255) << 16)
                | (Mth.clamp((int) (g1 + (g2 - g1) * t), 0, 255) << 8)
                | Mth.clamp((int) (b1 + (b2 - b1) * t), 0, 255);
    }

    /** 只替换 RGB、保留 {@code base} 的 alpha。 */
    public static int rgbOnly(int base, int rgb) {
        return (base & 0xFF000000) | (rgb & 0x00FFFFFF);
    }

    /** 明暗调整：{@code amount > 0} 提亮，{@code < 0} 压暗，保留 alpha。 */
    public static int shade(int color, float amount) {
        int target = amount >= 0.0F ? 0xFFFFFF : 0x000000;
        return rgbOnly(color, mix(color | 0xFF000000, target | 0xFF000000, Math.abs(amount)) & 0xFFFFFF);
    }

    // =====================================================================
    // 基础形状
    // =====================================================================

    /** 直角渐变填充。 */
    public static void gradient(GuiGraphics g, int x0, int y0, int x1, int y1, int top, int bottom) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        if (top == bottom) {
            g.fill(x0, y0, x1, y1, top);
            return;
        }
        g.fillGradient(x0, y0, x1, y1, top, bottom);
    }

    /**
     * 纵向渐变 + 横向淡出：用于「底边发光」「斜向光带」这类需要在一侧收边的装饰。
     * 内部按列切分，列数建议 16–32。
     */
    public static void gradientFadeX(GuiGraphics g, int x0, int y0, int x1, int y1,
                                     int color, float fromFactor, float toFactor, int steps) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int n = Math.max(1, Math.min(steps, x1 - x0));
        int span = x1 - x0;
        for (int i = 0; i < n; i++) {
            int sx = x0 + span * i / n;
            int ex = x0 + span * (i + 1) / n;
            if (ex <= sx) {
                continue;
            }
            float t = (i + 0.5F) / n;
            float factor = fromFactor + (toFactor - fromFactor) * t;
            int c = fade(color, factor);
            g.fill(sx, y0, ex, y1, c);
        }
    }

    /**
     * 平行四边形（顶部相对底部右移 {@code shear} 像素），由水平切片拼成。
     * {@code steps} 控制平滑度，建议 10–20。
     */
    public static void parallelogram(GuiGraphics g, int x0, int y0, int x1, int y1,
                                     float shear, int top, int bottom, int steps) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int n = Math.max(1, Math.min(steps, y1 - y0));
        for (int i = 0; i < n; i++) {
            int sy = y0 + (y1 - y0) * i / n;
            int ey = y0 + (y1 - y0) * (i + 1) / n;
            if (ey <= sy) {
                continue;
            }
            float t = (i + 0.5F) / n;
            int offset = (int) (shear * t);
            int color = top == bottom ? top : mix(top, bottom, t);
            g.fill(x0 + offset, sy, x1 + offset, ey, color);
        }
    }

    /** 从中心向外的柔光：由若干层递减透明度的矩形近似，成本低且不需要阴影贴图。 */
    public static void glow(GuiGraphics g, int centerX, int centerY, int radiusX, int radiusY,
                            int color, float strength) {
        if (radiusX <= 0 || radiusY <= 0) {
            return;
        }
        int peak = (int) (Mth.clamp(strength, 0.0F, 1.0F) * 70.0F);
        if (peak <= 2) {
            return;
        }
        int layers = 3;
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            int rx = Math.max(1, (int) (radiusX * t));
            int ry = Math.max(1, (int) (radiusY * t));
            int a = (int) (peak * (1.0F - t) * 1.5F) + 6;
            g.fill(centerX - rx, centerY - ry, centerX + rx, centerY + ry, alpha(color, a));
        }
    }

    /** 单像素描边（直角）。 */
    public static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** 菱形（旋转 45° 的方块）；用于职业纹章与装饰。 */
    public static void diamond(GuiGraphics g, int centerX, int centerY, int radiusX, int radiusY, int color) {
        if (radiusX <= 0 || radiusY <= 0) {
            return;
        }
        int step = Math.max(1, radiusY / 8);
        for (int dy = -radiusY; dy < radiusY; dy += step) {
            int y0 = centerY + dy;
            int y1 = Math.min(centerY + radiusY, y0 + step);
            float k = 1.0F - Math.abs((dy + step * 0.5F) / (float) radiusY);
            int half = (int) (radiusX * k);
            if (half <= 0 || y1 <= y0) {
                continue;
            }
            g.fill(centerX - half, y0, centerX + half + 1, y1, color);
        }
    }

    /** 菱形描边。 */
    public static void diamondOutline(GuiGraphics g, int centerX, int centerY, int radiusX, int radiusY, int color) {
        if (radiusX <= 0 || radiusY <= 0) {
            return;
        }
        for (int sign = -1; sign <= 1; sign += 2) {
            int prevHalf = -1;
            int prevY = 0;
            for (int dy = 0; dy <= radiusY; dy++) {
                float k = 1.0F - dy / (float) radiusY;
                int half = (int) (radiusX * k);
                int y = centerY + sign * dy;
                if (prevHalf >= 0) {
                    int from = Math.min(half, prevHalf);
                    int to = Math.max(half, prevHalf);
                    g.fill(centerX + sign * from, Math.min(y, prevY), centerX + sign * to + 1, Math.max(y, prevY) + 1, color);
                    g.fill(centerX - sign * to, Math.min(y, prevY), centerX - sign * from + 1, Math.max(y, prevY) + 1, color);
                }
                prevHalf = half;
                prevY = y;
            }
        }
        g.fill(centerX - radiusX, centerY, centerX + radiusX + 1, centerY + 1, color);
    }

    /** 竖直胶囊/圆头竖条：顶部与底部各收窄，用于进度条与分隔装饰。 */
    public static void roundedBar(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, int color) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int r = Mth.clamp(radius, 0, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
        if (r == 0) {
            g.fill(x0, y0, x1, y1, color);
            return;
        }
        g.fill(x0, y0 + r, x1, y1 - r, color);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(i, r);
            g.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, color);
            g.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, color);
        }
    }

    // =====================================================================
    // 卡片
    // =====================================================================

    /** 圆角矩形（纯色）。 */
    public static void roundRect(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, int color) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int r = clampRadius(x0, y0, x1, y1, radius);
        if (r == 0) {
            g.fill(x0, y0, x1, y1, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(i, r);
            g.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, color);
            g.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, color);
        }
        g.fill(x0, y0 + r, x1, y1 - r, color);
    }

    /** 圆角矩形（纵向渐变）。 */
    public static void roundGradient(GuiGraphics g, int x0, int y0, int x1, int y1, int radius,
                                     int top, int bottom) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        if (top == bottom) {
            roundRect(g, x0, y0, x1, y1, radius, top);
            return;
        }
        int r = clampRadius(x0, y0, x1, y1, radius);
        int h = Math.max(1, y1 - y0);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(i, r);
            g.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, mix(top, bottom, i / (float) h));
            g.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, mix(top, bottom, (h - i - 1) / (float) h));
        }
        g.fillGradient(x0, y0 + r, x1, y1 - r, mix(top, bottom, r / (float) h),
                mix(top, bottom, (h - r) / (float) h));
    }

    /** 圆角描边（1px）。 */
    public static void roundOutline(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, int color) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int r = clampRadius(x0, y0, x1, y1, radius);
        if (r == 0) {
            outline(g, x0, y0, x1, y1, color);
            return;
        }
        int prevInset = r;
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(i, r);
            int segX0 = Math.min(x0 + prevInset, x0 + inset);
            int segX1 = Math.max(x1 - prevInset, x1 - inset);
            g.fill(segX0, y0 + i, segX1, y0 + i + 1, color);
            g.fill(segX0, y1 - i - 1, segX1, y1 - i, color);
            prevInset = inset;
        }
        g.fill(x0, y0 + r, x0 + 1, y1 - r, color);
        g.fill(x1 - 1, y0 + r, x1, y1 - r, color);
    }

    /**
     * 卡片：圆角渐变背景 + 顶部内高光 + 1px 描边，可选外发光。
     *
     * @param lift 抬升强度（0..1），用于悬停外发光与描边提亮
     */
    public static void card(GuiGraphics g, int x0, int y0, int x1, int y1, int radius,
                            int top, int bottom, int border, int accent, float lift) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        float amount = clamp01(lift);
        if (amount > 0.02F) {
            int spread = 1 + (int) (amount * 3.0F);
            glow(g, (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2 + spread, (y1 - y0) / 2 + spread,
                    accent, amount * 0.85F);
        }
        roundGradient(g, x0, y0, x1, y1, radius, top, bottom);
        roundOutline(g, x0, y0, x1, y1, radius, border);
        // 顶部内高光：制造玻璃厚度感
        int inset = clampRadius(x0, y0, x1, y1, radius);
        gradient(g, x0 + inset, y0 + 1, x1 - inset, y0 + 2, alpha(0xFFFFFFFF, 24), alpha(0xFFFFFFFF, 2));
    }

    /** 斜向高光扫过。{@code sweep} 0..1 表示光带位置（可超出范围表示在画面外）。 */
    public static void sheen(GuiGraphics g, int x0, int y0, int x1, int y1, float sweep, float strength) {
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 2 || h <= 2 || strength <= 0.01F) {
            return;
        }
        // 光带沿 45° 从左上到右下；用平行四边形切片绘制，避免逐像素填充。
        int shear = Math.max(4, h);
        int bandWidth = Math.max(6, w / 3);
        int center = x0 - shear + (int) ((w + shear + bandWidth) * sweep);
        int peak = (int) (clamp01(strength) * 30.0F);
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            float t = (i + 0.5F) / steps;
            int bx0 = center - bandWidth / 2 + (int) (bandWidth * (i / (float) steps));
            int bx1 = center - bandWidth / 2 + (int) (bandWidth * ((i + 1) / (float) steps));
            if (bx1 <= bx0 || bx1 <= x0 || bx0 >= x1) {
                continue;
            }
            int a = (int) (peak * Math.sin(t * Math.PI));
            if (a <= 1) {
                continue;
            }
            int color = alpha(0xFFFFFFFF, a);
            parallelogram(g, Math.max(x0, bx0), y0, Math.min(x1, bx1), y1,
                    shear, color, alpha(0xFFFFFFFF, 0), 10);
        }
    }

    /** 把绘制限制在矩形内；务必与 {@link #endClip} 成对使用。 */
    public static void beginClip(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.enableScissor(x0, y0, x1, y1);
    }

    public static void endClip(GuiGraphics g) {
        g.disableScissor();
    }

    // =====================================================================
    // 文字
    // =====================================================================

    public static void centered(GuiGraphics g, Font font, Component text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** 居中且带阴影，用于压在图像之上的文字。 */
    public static void centeredShadow(GuiGraphics g, Font font, Component text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, true);
    }

    /** 从左到右的双色渐变文字：按字符逐段替换颜色。 */
    public static Component gradientText(String text, int from, int to) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        int length = text.length();
        var out = Component.empty();
        for (int i = 0; i < length; i++) {
            float t = length <= 1 ? 0.0F : i / (float) (length - 1);
            int rgb = mix(from | 0xFF000000, to | 0xFF000000, t) & 0xFFFFFF;
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(style -> style.withColor(rgb)));
        }
        return out;
    }

    /** 打字机：按进度截断字符串（按字符数）。 */
    public static String typewriter(String text, float progress) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int count = Mth.clamp((int) Math.ceil(text.length() * clamp01(progress)), 0, text.length());
        return text.substring(0, count);
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private static int clampRadius(int x0, int y0, int x1, int y1, int radius) {
        return Mth.clamp(radius, 0, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
    }

    /** 半径 r 的圆角在第 i 行（0 = 最外行）需要内缩的像素数。 */
    private static int cornerInset(int i, int r) {
        if (r <= 0) {
            return 0;
        }
        double k = Math.sqrt(Math.max(0.0D, 1.0D - Math.pow(1.0D - i / (double) r, 2.0D)));
        return (int) Math.round(r * (1.0D - k));
    }
}
