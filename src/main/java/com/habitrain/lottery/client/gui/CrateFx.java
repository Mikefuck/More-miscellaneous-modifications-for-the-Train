package com.habitrain.lottery.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * 开箱界面的光效基元：顶点渐变的柔光、光环、光束、射线、镜头拉丝与星芒。
 *
 * <p>{@link GuiFx#glow} 用叠矩形近似柔光，放大到 1080p 画布会出现明显台阶；这里改为
 * 逐顶点插值的三角扇，边缘平滑衰减到 0。发光件走 {@link #ADD} 加色混合（{@code SRC_ALPHA, ONE}），
 * 与底图叠加后只会变亮，因此光斑之间互相叠加也不会发灰。阴影与暗角等压暗件走
 * {@link #BLEND} 的常规混合。两者都不写深度，绘制顺序即前后顺序。</p>
 *
 * <p>每个基元结束时 {@code flush}，保证与原版 {@code fill}/文字/物品的绘制顺序一致。</p>
 */
public final class CrateFx {

    /** 加色混合：不写深度、只做 LEQUAL 测试，光效永远不会遮挡随后画出的内容。 */
    public static final RenderType ADD = new RenderType("habitrain_crate_glow", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 16, false, true,
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
            },
            () -> {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
            }) { };

    /** 常规半透明混合、不剔除背面：扇形与环带的绕序无需再逐个校正。 */
    public static final RenderType BLEND = new RenderType("habitrain_crate_shade", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 16, false, true,
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
            },
            () -> {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
            }) { };

    private static final int SEGMENTS = 48;

    private CrateFx() {
    }

    // =====================================================================
    // 顶点工具
    // =====================================================================

    private static VertexConsumer begin(GuiGraphics g, boolean add) {
        return g.bufferSource().getBuffer(add ? ADD : BLEND);
    }

    private static void vertex(VertexConsumer v, Matrix4f pose, float x, float y, int color) {
        v.addVertex(pose, x, y, 0).setColor(color);
    }

    /** 三角形写成退化四边形（第三个顶点重复一次），与 QUADS 模式兼容。 */
    private static void tri(VertexConsumer v, Matrix4f pose, float x0, float y0, int c0,
                            float x1, float y1, int c1, float x2, float y2, int c2) {
        vertex(v, pose, x0, y0, c0);
        vertex(v, pose, x1, y1, c1);
        vertex(v, pose, x2, y2, c2);
        vertex(v, pose, x2, y2, c2);
    }

    private static void quad(VertexConsumer v, Matrix4f pose, float x0, float y0, int c0, float x1, float y1, int c1,
                             float x2, float y2, int c2, float x3, float y3, int c3) {
        vertex(v, pose, x0, y0, c0);
        vertex(v, pose, x1, y1, c1);
        vertex(v, pose, x2, y2, c2);
        vertex(v, pose, x3, y3, c3);
    }

    private static int a(int color, float strength) {
        return GuiFx.fade(color, strength);
    }

    private static int clear(int color) {
        return color & 0x00FFFFFF;
    }

    // =====================================================================
    // 柔光 / 光环
    // =====================================================================

    /**
     * 椭圆柔光：中心 {@code color·strength}，按近似二次曲线衰减到边缘 0。
     * {@code add=false} 时用常规混合，适合接触阴影。
     */
    public static void glow(GuiGraphics g, float cx, float cy, float rx, float ry, int color, float strength, boolean add) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || rx <= 0.5F || ry <= 0.5F) return;
        VertexConsumer v = begin(g, add);
        Matrix4f pose = g.pose().last().pose();
        int core = a(color, s), mid = a(color, s * 0.32F), edge = clear(color);
        float k = 0.42F;
        for (int i = 0; i < SEGMENTS; i++) {
            double t0 = i * Math.PI * 2 / SEGMENTS, t1 = (i + 1) * Math.PI * 2 / SEGMENTS;
            float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
            tri(v, pose, cx, cy, core, cx + c0 * rx * k, cy + s0 * ry * k, mid, cx + c1 * rx * k, cy + s1 * ry * k, mid);
            quad(v, pose, cx + c0 * rx * k, cy + s0 * ry * k, mid, cx + c0 * rx, cy + s0 * ry, edge,
                    cx + c1 * rx, cy + s1 * ry, edge, cx + c1 * rx * k, cy + s1 * ry * k, mid);
        }
        g.flush();
    }

    public static void glow(GuiGraphics g, float cx, float cy, float r, int color, float strength) {
        glow(g, cx, cy, r, r, color, strength, true);
    }

    /** 光环：半径 {@code r} 处最亮，向内外各 {@code thickness} 衰减到 0。 */
    public static void ring(GuiGraphics g, float cx, float cy, float r, float thickness, float yScale,
                            int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || r <= 0.5F) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        float r0 = Math.max(0, r - thickness), r1 = r + thickness;
        int peak = a(color, s), edge = clear(color);
        int segments = r > 300 ? 96 : 64;
        for (int i = 0; i < segments; i++) {
            double t0 = i * Math.PI * 2 / segments, t1 = (i + 1) * Math.PI * 2 / segments;
            float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0) * yScale;
            float c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1) * yScale;
            quad(v, pose, cx + c0 * r0, cy + s0 * r0, edge, cx + c0 * r, cy + s0 * r, peak,
                    cx + c1 * r, cy + s1 * r, peak, cx + c1 * r0, cy + s1 * r0, edge);
            quad(v, pose, cx + c0 * r, cy + s0 * r, peak, cx + c0 * r1, cy + s0 * r1, edge,
                    cx + c1 * r1, cy + s1 * r1, edge, cx + c1 * r, cy + s1 * r, peak);
        }
        g.flush();
    }

    /**
     * 实心圆弧环带：内外半径 {@code r0 / r1}，从 {@code start} 弧度起顺时针扫过 {@code sweep} 弧度
     * （屏幕坐标 y 向下，−π/2 即正上方）。用来画长按进度环。
     */
    public static void arc(GuiGraphics g, float cx, float cy, float r0, float r1, float start, float sweep,
                           int color, boolean add) {
        if ((color >>> 24) == 0 || sweep <= 0.0001F || r1 <= r0) return;
        VertexConsumer v = begin(g, add);
        Matrix4f pose = g.pose().last().pose();
        int segments = Math.max(2, (int) Math.ceil(64 * sweep / (Math.PI * 2)));
        for (int i = 0; i < segments; i++) {
            double t0 = start + sweep * i / segments, t1 = start + sweep * (i + 1) / segments;
            float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
            quad(v, pose, cx + c0 * r0, cy + s0 * r0, color, cx + c0 * r1, cy + s0 * r1, color,
                    cx + c1 * r1, cy + s1 * r1, color, cx + c1 * r0, cy + s1 * r0, color);
        }
        g.flush();
    }

    /** 沿椭圆均布的短划线，旋转后就是展台上的符文环。 */
    public static void dashRing(GuiGraphics g, float cx, float cy, float rx, float ry, int count, float fill,
                                float rotation, float width, int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || count <= 0) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        float step = (float) (Math.PI * 2 / count);
        for (int i = 0; i < count; i++) {
            float t0 = rotation + i * step, t1 = t0 + step * fill;
            // 远端（上半圈）更淡，读出透视
            float depth = 0.45F + 0.55F * (0.5F + 0.5F * (float) Math.sin((t0 + t1) * 0.5F));
            int cc = a(color, s * depth);
            float ox0 = (float) Math.cos(t0), oy0 = (float) Math.sin(t0), ox1 = (float) Math.cos(t1), oy1 = (float) Math.sin(t1);
            quad(v, pose, cx + ox0 * (rx - width), cy + oy0 * (ry - width * ry / rx), cc,
                    cx + ox0 * rx, cy + oy0 * ry, cc, cx + ox1 * rx, cy + oy1 * ry, cc,
                    cx + ox1 * (rx - width), cy + oy1 * (ry - width * ry / rx), cc);
        }
        g.flush();
    }

    // =====================================================================
    // 射线 / 光束 / 拉丝
    // =====================================================================

    /** 自中心放射的楔形光线；{@code widthDeg} 为每条光线的张角。 */
    public static void rays(GuiGraphics g, float cx, float cy, float r0, float r1, int count, float rotation,
                            float widthDeg, float yScale, int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || count <= 0) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        float half = (float) Math.toRadians(widthDeg) * 0.5F;
        int inner = a(color, s), edge = clear(color);
        for (int i = 0; i < count; i++) {
            float t = rotation + (float) (i * Math.PI * 2 / count);
            // 长短交错：偶数条更长更亮
            float len = i % 2 == 0 ? r1 : r1 * 0.72F;
            int core = i % 2 == 0 ? inner : a(color, s * 0.6F);
            float ca = (float) Math.cos(t - half), sa = (float) Math.sin(t - half) * yScale;
            float cb = (float) Math.cos(t + half), sb = (float) Math.sin(t + half) * yScale;
            float cm = (float) Math.cos(t), sm = (float) Math.sin(t) * yScale;
            quad(v, pose, cx + cm * r0, cy + sm * r0, core, cx + ca * len, cy + sa * len, edge,
                    cx + cm * len * 1.02F, cy + sm * len * 1.02F, edge, cx + cb * len, cy + sb * len, edge);
        }
        g.flush();
    }

    /** 竖直光柱：底部 {@code yBottom} 最亮、向上淡出；横向中线最亮、两侧淡出。 */
    public static void beam(GuiGraphics g, float cx, float yTop, float yBottom, float halfTop, float halfBottom,
                            int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int bottom = a(color, s), top = a(color, s * 0.05F), edge = clear(color);
        quad(v, pose, cx - halfTop, yTop, edge, cx, yTop, top, cx, yBottom, bottom, cx - halfBottom, yBottom, edge);
        quad(v, pose, cx, yTop, top, cx + halfTop, yTop, edge, cx + halfBottom, yBottom, edge, cx, yBottom, bottom);
        g.flush();
    }

    /** 自上而下的聚光锥：顶部最亮、落到地面淡出。 */
    public static void spotlight(GuiGraphics g, float cx, float yTop, float yBottom, float halfTop, float halfBottom,
                                 int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int top = a(color, s), bottom = a(color, s * 0.18F), edge = clear(color);
        quad(v, pose, cx - halfTop, yTop, edge, cx, yTop, top, cx, yBottom, bottom, cx - halfBottom, yBottom, edge);
        quad(v, pose, cx, yTop, top, cx + halfTop, yTop, edge, cx + halfBottom, yBottom, edge, cx, yBottom, bottom);
        g.flush();
    }

    /** 水平镜头拉丝：中心最亮、两端与上下沿都衰减到 0 的菱形。 */
    public static void streak(GuiGraphics g, float cx, float cy, float halfLength, float halfHeight,
                              int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || halfLength <= 0.5F) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int core = a(color, s), edge = clear(color);
        tri(v, pose, cx, cy, core, cx - halfLength, cy, edge, cx, cy - halfHeight, edge);
        tri(v, pose, cx, cy, core, cx, cy - halfHeight, edge, cx + halfLength, cy, edge);
        tri(v, pose, cx, cy, core, cx + halfLength, cy, edge, cx, cy + halfHeight, edge);
        tri(v, pose, cx, cy, core, cx, cy + halfHeight, edge, cx - halfLength, cy, edge);
        g.flush();
    }

    /** 四角星芒：两条交叉拉丝 + 中心柔光。 */
    public static void sparkle(GuiGraphics g, float cx, float cy, float size, float rotation, int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || size <= 0.5F) return;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (rotation != 0) g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(rotation));
        streak(g, 0, 0, size, size * 0.09F, color, s);
        streak(g, 0, 0, size * 0.09F, size, color, s);
        streak(g, 0, 0, size * 0.45F, size * 0.45F, 0xFFFFFFFF, s * 0.35F);
        g.pose().popPose();
        glow(g, cx, cy, size * 0.5F, color, s * 0.6F);
    }

    /** 线段光（粒子拖尾）：头部亮、尾部透明，宽度 {@code width}。 */
    public static void trail(GuiGraphics g, float x0, float y0, float x1, float y1, float width, int color, float strength) {
        float s = GuiFx.clamp01(strength);
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (s <= 0.004F || len < 0.2F) return;
        float nx = -dy / len * width * 0.5F, ny = dx / len * width * 0.5F;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int head = a(color, s), tail = clear(color);
        quad(v, pose, x0, y0, tail, x1 + nx, y1 + ny, head, x1 + dx / len * width, y1 + dy / len * width, head, x1 - nx, y1 - ny, head);
        g.flush();
    }

    // =====================================================================
    // 矩形件
    // =====================================================================

    /** 发光线段：{@code core} 宽的实芯 + 两侧 {@code spread} 衰减，端点补圆光。 */
    public static void lineGlow(GuiGraphics g, float x0, float y0, float x1, float y1, float core, float spread,
                                int color, float strength) {
        float s = GuiFx.clamp01(strength);
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (s <= 0.004F || len < 0.1F) return;
        float nx = -dy / len, ny = dx / len;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int c = a(color, s), hot = a(0xFFFFFFFF, s * 0.8F), edge = clear(color);
        float h = core * 0.5F;
        quad(v, pose, x0 + nx * h, y0 + ny * h, hot, x1 + nx * h, y1 + ny * h, hot,
                x1 - nx * h, y1 - ny * h, hot, x0 - nx * h, y0 - ny * h, hot);
        quad(v, pose, x0 + nx * h, y0 + ny * h, c, x0 + nx * (h + spread), y0 + ny * (h + spread), edge,
                x1 + nx * (h + spread), y1 + ny * (h + spread), edge, x1 + nx * h, y1 + ny * h, c);
        quad(v, pose, x0 - nx * h, y0 - ny * h, c, x0 - nx * (h + spread), y0 - ny * (h + spread), edge,
                x1 - nx * (h + spread), y1 - ny * (h + spread), edge, x1 - nx * h, y1 - ny * h, c);
        g.flush();
        glow(g, x0, y0, spread, color, s * 0.6F);
        glow(g, x1, y1, spread, color, s * 0.6F);
    }

    /** 斜向光带：中线 {@code color}、两侧淡出，底边相对顶边左移 {@code skew}。 */
    public static void skewBand(GuiGraphics g, float cx, float y0, float y1, float halfWidth, float skew, int color) {
        if ((color >>> 24) == 0) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int edge = clear(color);
        float bx = cx - skew;
        quad(v, pose, cx - halfWidth, y0, edge, bx - halfWidth, y1, edge, bx, y1, color, cx, y0, color);
        quad(v, pose, cx, y0, color, bx, y1, color, bx + halfWidth, y1, edge, cx + halfWidth, y0, edge);
        g.flush();
    }

    /** 任意四角颜色的矩形（左上、右上、右下、左下）。 */
    public static void rect(GuiGraphics g, float x0, float y0, float x1, float y1,
                            int tl, int tr, int br, int bl, boolean add) {
        if (x1 <= x0 || y1 <= y0) return;
        VertexConsumer v = begin(g, add);
        Matrix4f pose = g.pose().last().pose();
        quad(v, pose, x0, y0, tl, x0, y1, bl, x1, y1, br, x1, y0, tr);
        g.flush();
    }

    /** 横向渐变矩形。 */
    public static void hGradient(GuiGraphics g, float x0, float y0, float x1, float y1, int left, int right, boolean add) {
        rect(g, x0, y0, x1, y1, left, right, right, left, add);
    }

    /** 纵向渐变矩形。 */
    public static void vGradient(GuiGraphics g, float x0, float y0, float x1, float y1, int top, int bottom, boolean add) {
        rect(g, x0, y0, x1, y1, top, top, bottom, bottom, add);
    }

    /** 水平两端淡出的细线：中段 {@code color}，两端在 {@code fade} 像素内衰减到 0。 */
    public static void hairline(GuiGraphics g, float x0, float x1, float y, float height, float fade, int color, boolean add) {
        if (x1 <= x0) return;
        float f = Math.min(fade, (x1 - x0) * 0.5F);
        hGradient(g, x0, y, x0 + f, y + height, clear(color), color, add);
        rect(g, x0 + f, y, x1 - f, y + height, color, color, color, color, add);
        hGradient(g, x1 - f, y, x1, y + height, color, clear(color), add);
    }

    /** 矩形外发光：四边向外 {@code spread} 衰减，角上用扇形补齐。 */
    public static void rectGlow(GuiGraphics g, float x0, float y0, float x1, float y1, float spread,
                                int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F || spread <= 0.5F) return;
        VertexConsumer v = begin(g, true);
        Matrix4f pose = g.pose().last().pose();
        int in = a(color, s), out = clear(color);
        quad(v, pose, x0, y0 - spread, out, x0, y0, in, x1, y0, in, x1, y0 - spread, out);
        quad(v, pose, x0, y1, in, x0, y1 + spread, out, x1, y1 + spread, out, x1, y1, in);
        quad(v, pose, x0 - spread, y0, out, x0 - spread, y1, out, x0, y1, in, x0, y0, in);
        quad(v, pose, x1, y0, in, x1, y1, in, x1 + spread, y1, out, x1 + spread, y0, out);
        float[][] corners = {{x0, y0, (float) Math.PI}, {x1, y0, (float) (Math.PI * 1.5)},
                {x1, y1, 0}, {x0, y1, (float) (Math.PI * 0.5)}};
        for (float[] c : corners) {
            for (int i = 0; i < 6; i++) {
                double t0 = c[2] + i * Math.PI / 12, t1 = c[2] + (i + 1) * Math.PI / 12;
                tri(v, pose, c[0], c[1], in, c[0] + (float) Math.cos(t0) * spread, c[1] + (float) Math.sin(t0) * spread, out,
                        c[0] + (float) Math.cos(t1) * spread, c[1] + (float) Math.sin(t1) * spread, out);
            }
        }
        g.flush();
    }

    /**
     * 椭圆暗角：内椭圆以内通透，到外椭圆压到 {@code strength}，之外保持不变直到画面边缘。
     */
    public static void vignette(GuiGraphics g, float width, float height, float cx, float cy,
                                float rx0, float ry0, float rx1, float ry1, int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F) return;
        VertexConsumer v = begin(g, false);
        Matrix4f pose = g.pose().last().pose();
        int edge = clear(color), dark = a(color, s);
        float far = (width + height) * 1.2F;
        int segments = 72;
        for (int i = 0; i < segments; i++) {
            double t0 = i * Math.PI * 2 / segments, t1 = (i + 1) * Math.PI * 2 / segments;
            float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
            quad(v, pose, cx + c0 * rx0, cy + s0 * ry0, edge, cx + c0 * rx1, cy + s0 * ry1, dark,
                    cx + c1 * rx1, cy + s1 * ry1, dark, cx + c1 * rx0, cy + s1 * ry0, edge);
            quad(v, pose, cx + c0 * rx1, cy + s0 * ry1, dark, cx + c0 * far, cy + s0 * far, dark,
                    cx + c1 * far, cy + s1 * far, dark, cx + c1 * rx1, cy + s1 * ry1, dark);
        }
        g.flush();
    }

    /** 实心三角形。 */
    public static void triangle(GuiGraphics g, float x0, float y0, float x1, float y1, float x2, float y2,
                                int color, boolean add) {
        if ((color >>> 24) == 0) return;
        VertexConsumer v = begin(g, add);
        Matrix4f pose = g.pose().last().pose();
        tri(v, pose, x0, y0, color, x1, y1, color, x2, y2, color);
        g.flush();
    }

    /** 实心菱形（装饰用）。 */
    public static void diamond(GuiGraphics g, float cx, float cy, float rx, float ry, int color, boolean add) {
        if ((color >>> 24) == 0) return;
        VertexConsumer v = begin(g, add);
        Matrix4f pose = g.pose().last().pose();
        quad(v, pose, cx, cy - ry, color, cx - rx, cy, color, cx, cy + ry, color, cx + rx, cy, color);
        g.flush();
    }

    /** 斜切角面板：四角各切掉 {@code cut} 像素，上下两色纵向渐变。 */
    public static void bevelPanel(GuiGraphics g, float x0, float y0, float x1, float y1, float cut, int top, int bottom) {
        if (x1 <= x0 || y1 <= y0) return;
        VertexConsumer v = begin(g, false);
        Matrix4f pose = g.pose().last().pose();
        float h = y1 - y0;
        int topIn = GuiFx.mix(top, bottom, cut / h), bottomIn = GuiFx.mix(top, bottom, 1 - cut / h);
        int ta = (top >>> 24), ba = (bottom >>> 24);
        topIn = (topIn & 0xFFFFFF) | (((int) (ta + (ba - ta) * cut / h)) << 24);
        bottomIn = (bottomIn & 0xFFFFFF) | (((int) (ta + (ba - ta) * (1 - cut / h))) << 24);
        quad(v, pose, x0 + cut, y0, top, x0, y0 + cut, topIn, x1, y0 + cut, topIn, x1 - cut, y0, top);
        quad(v, pose, x0, y0 + cut, topIn, x0, y1 - cut, bottomIn, x1, y1 - cut, bottomIn, x1, y0 + cut, topIn);
        quad(v, pose, x0, y1 - cut, bottomIn, x0 + cut, y1, bottom, x1 - cut, y1, bottom, x1, y1 - cut, bottomIn);
        g.flush();
    }

    /** 斜切角面板的 1px 描边。 */
    public static void bevelOutline(GuiGraphics g, float x0, float y0, float x1, float y1, float cut, float w, int color) {
        if ((color >>> 24) == 0) return;
        VertexConsumer v = begin(g, false);
        Matrix4f pose = g.pose().last().pose();
        float[] xs = {x0 + cut, x1 - cut, x1, x1, x1 - cut, x0 + cut, x0, x0};
        float[] ys = {y0, y0, y0 + cut, y1 - cut, y1, y1, y1 - cut, y0 + cut};
        for (int i = 0; i < 8; i++) {
            int j = (i + 1) % 8;
            float dx = xs[j] - xs[i], dy = ys[j] - ys[i], len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.01F) continue;
            float nx = -dy / len * w, ny = dx / len * w;
            quad(v, pose, xs[i], ys[i], color, xs[i] + nx, ys[i] + ny, color, xs[j] + nx, ys[j] + ny, color, xs[j], ys[j], color);
        }
        g.flush();
    }

    /** 全屏加色闪白。 */
    public static void flash(GuiGraphics g, float width, float height, int color, float strength) {
        float s = GuiFx.clamp01(strength);
        if (s <= 0.004F) return;
        int c = a(color, s);
        rect(g, 0, 0, width, height, c, c, c, c, true);
    }
}
