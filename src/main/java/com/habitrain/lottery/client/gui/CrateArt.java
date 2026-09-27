package com.habitrain.lottery.client.gui;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import net.minecraft.world.item.ItemStack;

/** Reference-derived case materials and courtyard, with live lid geometry and GUI effects. */
public final class CrateArt {

    // =====================================================================
    // 调色板（逐项取自参考视频 §4「Crate materials」实测表）
    // =====================================================================
    /** 箱体正面主色：近黑的暖炭色 {@code #2A2017}，不是冷灰。 */
    public static final int BODY_FRONT = 0xFF2A2017, BODY_FRONT_LOW = 0xFF1C130D,
            BODY_SIDE = 0xFF231A12, BODY_SIDE_LOW = 0xFF170F09,
            BODY_BACK = 0xFF140D08, BODY_INTERIOR = 0xFF0B0B0B, BODY_INTERIOR_DEEP = 0xFF050506,
            /** 加固角钢的 1px 高光边（参考 {@code #514437}）。 */
            EDGE = 0xFF514437, EDGE_DARK = 0xFF0D0906,
            /** 危险黄：视频雾化后实测 {@code #A27812}，规格书要求用回亮值 {@code #E8C21A}。 */
            HAZARD = 0xFFE8C21A, HAZARD_DEEP = 0xFFD9A31A, HAZARD_INK = 0xFF1A1A1A,
            /** 箱盖顶面读起来比箱体亮约两档。 */
            STEEL = 0xFF3B2C25, STEEL_LIGHT = 0xFF695545, RIB = 0xFF23201C,
            /** 正面那块浅色凸起面板 {@code #BB9F85}。 */
            PLATE = 0xFFBB9F85;
    /** 箱盖厚度（本地单位）。 */
    public static final float LID_THICKNESS = 0.13F;

    private CrateArt() {
    }

    // =====================================================================
    // 等距投影
    // =====================================================================

    /**
     * 正交等距投影。{@code crateWidth} 是箱子（含左右两个可见侧面）在屏幕上的总宽度，
     * {@code baseY} 是箱子底面中心落在屏幕上的位置。
     */
    public static final class Iso {
        private final float ox, oy, w;
        public Iso(float centerX, float baseY, float crateWidth) {
            ox = centerX; oy = baseY; w = Math.max(4, crateWidth);
        }
        public float px(float x, float y, float z) { return ox + ((x - .5F) * .74F + (z - .5F) * .26F) * w; }
        public float py(float x, float y, float z) { return oy + ((x - .5F) * .06F - (z - .5F) * .15F - y * .46F) * w; }
        public float scale() { return w * .5F; }
        public float heightScale() { return w * .46F; }
    }

    private static ResourceLocation art(String name) {
        return ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/crate/" + name + ".png");
    }
    private static final ResourceLocation FRONT = art("case_front"), SIDE = art("case_side"), LID = art("case_lid");
    private static final ResourceLocation[] COURTYARD = {art("courtyard_4"), art("courtyard_12"), art("courtyard_24")};

    private static void texturedFace(GuiGraphics g, Iso iso, ResourceLocation texture, float[][] points, float alpha) {
        g.flush();
        VertexConsumer v = g.bufferSource().getBuffer(RenderType.text(texture));
        Matrix4f pose = g.pose().last().pose();
        for (int i : new int[]{0, 3, 2, 1}) {
            float[] p = points[i];
            v.addVertex(pose, iso.px(p[0],p[1],p[2]), iso.py(p[0],p[1],p[2]), 0)
                    .setColor(255,255,255,(int)(255 * alpha))
                    .setUv(i == 1 || i == 2 ? 1 : 0, i >= 2 ? 1 : 0).setLight(15728880);
        }
        g.flush();
    }

    /**
     * 画一个开箱中的箱子。
     *
     * @param cx       箱子底面中心的屏幕 X
     * @param baseY    箱子底面中心的屏幕 Y
     * @param width    箱子总宽（像素）
     * @param lidAngle 箱盖翻转角度，0 为关闭，约 112° 为完全掀开
     * @param accent 保留调用方的品质色；箱体材质不随品质变色
     * @param intensity 保留动画调用参数；参考视频无发光
     */
    public static void crate(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                             int accent, float intensity) {
        Iso iso = new Iso(cx, baseY, width);
        float theta = (float) Math.toRadians(Mth.clamp(lidAngle, 0, 120));
        float sin = (float) Math.sin(theta), cos = (float) Math.cos(theta);
        // The lid is shallow; its projected rise must not cross the title block.
        float lift = sin * .34F;
        // The same case stays in the scene. Its lid pivots at the rear hinge.
        polygon(g, new float[]{iso.px(0,1,0),iso.px(1,1,0),iso.px(1,1,1),iso.px(0,1,1)},
                new float[]{iso.py(0,1,0),iso.py(1,1,0),iso.py(1,1,1),iso.py(0,1,1)},4,BODY_INTERIOR);
        if (lidAngle > 4) {
            polygon(g, new float[]{iso.px(0,1+lift,1-cos),iso.px(1,1+lift,1-cos),iso.px(1,1,1),iso.px(0,1,1)},
                    new float[]{iso.py(0,1+lift,1-cos),iso.py(1,1+lift,1-cos),iso.py(1,1,1),iso.py(0,1,1)},4,0xFF0B0B0B);
        }
        texturedFace(g, iso, FRONT, new float[][]{{0,1,0},{1,1,0},{1,0,0},{0,0,0}}, 1);
        texturedFace(g, iso, SIDE, new float[][]{{1,1,0},{1,1,1},{1,0,1},{1,0,0}}, 1);
        if (lidAngle <= 90) {
            texturedFace(g, iso, LID, new float[][]{{0,1.012F,1},{1,1.012F,1},
                    {1,1.012F+lift,1-cos},{0,1.012F+lift,1-cos}}, 1);
        }
        // Raised lid rim gives the closed case a visible seam.
        line(g, iso.px(0,1,0),iso.py(0,1,0),iso.px(1,1,0),iso.py(1,1,0),EDGE);
    }

    /** Palette and face mark for configurable cases, while retaining the original geometry and timing. */
    public static void crateStyled(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                                   int accent, float intensity, String preset, String badge) {
        crate(g, cx, baseY, width, lidAngle, accent, intensity);
        Iso iso = new Iso(cx, baseY, width);
        int dark = 0xFF000000 | ((accent & 0xFEFEFE) >> 1);
        float low = "industrial".equals(preset) ? .23F : .30F;
        float high = "hazard".equals(preset) ? .65F : .55F;
        polygon(g, new float[]{iso.px(.12F, low, 0),iso.px(.88F, low, 0),iso.px(.88F, high, 0),iso.px(.12F, high, 0)},
                new float[]{iso.py(.12F, low, 0),iso.py(.88F, low, 0),iso.py(.88F, high, 0),iso.py(.12F, high, 0)}, 4, dark);
        line(g, iso.px(.12F, high, 0), iso.py(.12F, high, 0), iso.px(.88F, high, 0), iso.py(.88F, high, 0), accent);
        if ("diamond".equals(badge) || "star".equals(badge) || "bolt".equals(badge)) {
            float[] xs = {iso.px(.50F,.59F,0),iso.px(.63F,.43F,0),iso.px(.50F,.27F,0),iso.px(.37F,.43F,0)};
            float[] ys = {iso.py(.50F,.59F,0),iso.py(.63F,.43F,0),iso.py(.50F,.27F,0),iso.py(.37F,.43F,0)};
            polygon(g, xs, ys, 4, accent);
        }
    }

    // =====================================================================
    // 多边形工具
    // =====================================================================

    /** 凸多边形 GPU 三角填充；统一朝向，避免翻盖后被背面剔除。 */
    public static void polygon(GuiGraphics g, float[] xs, float[] ys, int count, int color) {
        if (count < 3 || (color >>> 24) == 0) return;
        VertexConsumer v = g.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = g.pose().last().pose();
        float area = 0;
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            area += xs[i] * ys[next] - xs[next] * ys[i];
        }
        for (int i = 1; i < count - 1; i++) {
            int a = area > 0 ? i + 1 : i, b = area > 0 ? i : i + 1;
            for (int p : new int[]{0, a, b, b}) {
                v.addVertex(pose, xs[p], ys[p], 0).setColor(color);
            }
        }
    }

    /** 1 像素粗的线段（按主方向步进填充）。 */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        if ((color >>> 24) == 0) return;
        float dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            g.fill((int) x0, (int) y0, (int) x0 + 1, (int) y0 + 1, color);
            return;
        }
        if (steps > 420) steps = 420;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int x = (int) (x0 + dx * t), y = (int) (y0 + dy * t);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** 椭圆填充（用于地面光池与地面阴影）。 */
    public static void ellipse(GuiGraphics g, float cx, float cy, float rx, float ry, int color) {
        if ((color >>> 24) == 0 || rx <= 0 || ry <= 0) return;
        int steps = 28;
        float[] xs = new float[steps], ys = new float[steps];
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2.0D / steps;
            xs[i] = cx + (float) Math.cos(a) * rx;
            ys[i] = cy + (float) Math.sin(a) * ry;
        }
        polygon(g, xs, ys, steps, color);
    }

    // =====================================================================
    // 场景
    // =====================================================================

    /**
     * 参考视频中庭院的重建背景，按合焦、推镜、右移三个进度绘制。
     *
     * 预模糊纹理只在入场合焦期间交叉淡入；没有同心光圈或闪光。
     */
    public static void backdrop(GuiGraphics g, int width, int height, float focus, float dolly, float pan) {
        // Reference-derived clean scene plate; cross-fade preblurred levels for the 533ms focus pull.
        float blur = (1 - CrateStage.easeInOutCubic(focus)) * 2;
        int low = Math.min(2, (int)blur), high = Math.min(2, low + 1);
        float mix = blur - low;
        g.pose().pushPose();
        g.pose().translate(width * .5F - pan * 120, height * .5F, 0);
        float zoom = 1.04F + .07F * dolly + .10F * pan;
        g.pose().scale(zoom, zoom, 1);
        g.pose().translate(-width * .5F, -height * .5F, 0);
        g.blit(COURTYARD[low], 0, 0, width, height, 0, 0, 1920, 1080, 1920, 1080);
        if (mix > .01F) {
            g.setColor(1,1,1,mix);
            g.blit(COURTYARD[high], 0, 0, width, height, 0, 0, 1920, 1080, 1920, 1080);
            g.setColor(1,1,1,1);
        }
        g.pose().popPose();
    }

    /**
     * 全屏暗场：转场与「切黑」桥接。
     *
     * <p>参考视频里所有明暗变化都是纯不透明度过渡，没有任何高光或泛光，因此这里只做
     * 一层纯色压暗，不往白色方向混、也不叠任何发光。</p>
     */
    public static void bridge(GuiGraphics g, int width, int height, int color, float strength) {
        float s = CrateStage.clamp01(strength);
        if (s <= 0.01F) return;
        g.fill(0, 0, width, height, GuiFx.fade(color, s));
    }

    /**
     * 箱子底部的接地阴影。
     *
     * <p>参考视频里开箱全程没有光柱、粒子或泛光，只有一圈很淡的接触阴影，
     * 因此这里刻意不画任何发光。</p>
     */
    public static void groundShadow(GuiGraphics g, float cx, float baseY, float width, float strength) {
        float s = CrateStage.clamp01(strength);
        if (s <= 0.01F) return;
        ellipse(g, cx, baseY + width * 0.02F, width * 0.55F, width * 0.095F,
                GuiFx.alpha(0xFF000000, (int) (110 * s)));
    }

    // =====================================================================
    // 物品
    // =====================================================================

    /**
     * 渲染一个物品图标。
     *
     * @param yawDeg 绕竖轴的旋转，用于展示台前的缓慢自转
     * @param rollDeg 面内旋转，用于环绕漂浮时的轻微倾斜
     * @param tint   亮度系数（1 为原色）；物品渲染不支持逐像素 alpha，因此退场时靠它压暗
     */
    public static void item(GuiGraphics g, ItemStack stack, float cx, float cy, float scale,
                            float yawDeg, float rollDeg, float tint) {
        item(g, stack, cx, cy, scale, yawDeg, rollDeg, tint, 1);
    }

    public static void item(GuiGraphics g, ItemStack stack, float cx, float cy, float scale,
                            float yawDeg, float rollDeg, float tint, float alpha) {
        if (stack == null || stack.isEmpty() || scale <= 0.02F || alpha <= .01F) return;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0F);
        if (yawDeg != 0.0F) g.pose().mulPose(Axis.YP.rotationDegrees(yawDeg));
        if (rollDeg != 0.0F) g.pose().mulPose(Axis.ZP.rotationDegrees(rollDeg));
        g.pose().scale(scale, scale, 1.0F);
        float t = Mth.clamp(tint, 0.0F, 1.0F);
        g.setColor(t, t, t, alpha);
        g.renderFakeItem(stack, -8, -8);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.pose().popPose();
    }

    // =====================================================================
    // 物品卡片与光标
    // =====================================================================

    /** 卡片底板：参考视频里每张卡片都是「灰棚拍 + 底部靛蓝地板」的实拍背景。 */
    public static final int CARD_TOP = 0xFF9A9A9A, CARD_MID = 0xFF6E6E6E,
            CARD_FLOOR = 0xFF5B4FC8, CARD_FLOOR_DEEP = 0xFF3A2E8C, CARD_EDGE = 0xFFC8C8C8;

    /**
     * 绘制一张物品卡片。
     *
     * @param haze 0..1 的失焦程度：GUI 没有模糊通道，用同色薄雾叠加近似景深虚化
     * @param tint 亮度系数，离光标越远越暗
     */
    public static void card(GuiGraphics g, float x, float y, float w, float h, float tint, float haze) {
        card(g, x, y, w, h, tint, haze, 0, 0.0F);
    }

    /**
     * 绘制一张物品卡片，并在底部压一条品质色条纹。
     *
     * <p>参考视频里每张卡片都是「灰棚拍 + 底部靛蓝地板」的实拍背景，卡片之间严丝合缝
     * （360px 卡片、360px 间距、0 间隙），井顶有一条 1px {@code #C8C8C8} 高光边，
     * 井底压一条 6px 的品质色带（军规蓝／受限紫／保密品红／隐秘红／特殊金）。
     * 屏幕上没有画任何描边或圆角，因此这里也只用直角填充。</p>
     *
     * @param stripe      品质色；{@code stripeAlpha <= 0} 时不画
     * @param stripeAlpha 品质条纹的不透明度 0..1
     */
    public static void card(GuiGraphics g, float x, float y, float w, float h, float tint, float haze,
                            int stripe, float stripeAlpha) {
        if (w < 4.0F || h < 4.0F) return;
        int left = (int) x, top = (int) y, right = (int) (x + w), bottom = (int) (y + h);
        float dim = Mth.clamp(tint, 0.0F, 1.0F);
        float alpha = CrateStage.clamp01(stripeAlpha);
        // 上灰棚拍 → 中部压暗 → 底部靛蓝地板
        float stripeHeight = Math.max(1.0F, h * 6.0F / 280.0F);
        int split = (int) (y + h * 0.62F);
        int floorTop = (int) (bottom - stripeHeight);
        g.fillGradient(left, top, right, Math.min(split, floorTop),
                GuiFx.fade(GuiFx.shade(CARD_TOP, dim - 1.0F), alpha), GuiFx.fade(GuiFx.shade(CARD_MID, dim - 1.0F), alpha));
        if (floorTop > split) {
            g.fillGradient(left, split, right, floorTop,
                    GuiFx.fade(GuiFx.shade(GuiFx.mix(0xFF30343B, stripe, .28F), dim - 1.0F), alpha), GuiFx.fade(GuiFx.shade(GuiFx.mix(0xFF20242B, stripe, .14F), dim - 1.0F), alpha));
        }
        g.fill(left, top, right, top + 1, GuiFx.alpha(CARD_EDGE, (int)(200 * alpha)));
        if (stripeAlpha > 0.02F) {
            g.fill(left, floorTop, right, bottom,
                    GuiFx.alpha(stripe, (int) (255.0F * Mth.clamp(stripeAlpha, 0.0F, 1.0F))));
        }
        if (haze > 0.02F) {
            // 失焦：一层低对比薄雾压在卡片上，越远越浓
            g.fill(left, top, right, bottom,
                    GuiFx.alpha(GuiFx.mix(CARD_MID, CARD_FLOOR, 0.5F), (int) (140 * haze)));
        }
    }

    /** 光标黄线：参考视频里固定 2px、颜色 {@code #E8D44D}、贯通卡片带。 */
    public static void cursor(GuiGraphics g, float cx, float y0, float y1, float strength) {
        float s = CrateStage.clamp01(strength);
        if (s <= 0.01F) return;
        int x = (int) cx;
        g.fill(x - 1, (int) y0, x + 1, (int) y1, GuiFx.alpha(0xFFE8D44D, (int) (240 * s)));
    }

    /**
     * 圆形暗角：中心通透、到 {@code rOuter} 完全不透明。
     *
     * <p>参考视频里转盘阶段会收拢一个圆心 (0.5, 0.47)、内径 400px、460px 处全黑的圆形暗角，
     * 把视线锁在光标附近。这里按扫描线逐带填充圆外区域。</p>
     */
    public static void circleVignette(GuiGraphics g, int width, int height, float cx, float cy,
                                     float rInner, float rOuter, int color, float strength) {
        float s = CrateStage.clamp01(strength);
        if (s <= .01F || rOuter <= rInner) return;
        // Tile-free radial mesh: the transparent centre meets the opaque outside monotonically.
        g.flush();
        VertexConsumer v = g.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = g.pose().last().pose();
        float far = width + height;
        int segments = 160;
        for (int i = 0; i < segments; i++) {
            double a = i * Math.PI * 2 / segments, b = (i + 1) * Math.PI * 2 / segments;
            for (int ring = 0; ring < 2; ring++) {
                float r0 = ring == 0 ? rInner : rOuter, r1 = ring == 0 ? rOuter : far;
                int c0 = GuiFx.alpha(color, ring == 0 ? 0 : (int)(255*s));
                int c1 = GuiFx.alpha(color, (int)(255*s));
                v.addVertex(pose,cx+(float)Math.cos(a)*r0,cy+(float)Math.sin(a)*r0,0).setColor(c0);
                v.addVertex(pose,cx+(float)Math.cos(b)*r0,cy+(float)Math.sin(b)*r0,0).setColor(c0);
                v.addVertex(pose,cx+(float)Math.cos(b)*r1,cy+(float)Math.sin(b)*r1,0).setColor(c1);
                v.addVertex(pose,cx+(float)Math.cos(a)*r1,cy+(float)Math.sin(a)*r1,0).setColor(c1);
            }
        }
        g.flush();
    }

    // =====================================================================
    // 参考视频的界面件：底部物品条 / 确认弹窗 / 品质条 / 合焦薄雾
    // =====================================================================

    /**
     * 底部十连物品条的底板（参考 f074：x 40–1590、y 795–965，占屏高 15.7%）。
     * 柔和暗场 {@code rgba(28,28,28,0.45)} + 顶部 1px {@code #8A8A8A} 细规。
     */
    public static void stripPanel(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0 || y1 <= y0) return;
        g.fill(x0, y0, x1, y1, GuiFx.fade(0x731C1C1C, a));
        g.fill(x0, y0, x1, y0 + 1, GuiFx.fade(0xFF8A8A8A, a));
    }

    /**
     * 确认弹窗底板（参考 f104：772×204px、直角、无描边、半透明鼠尾草绿
     * {@code rgba(74,90,60,0.72)}，下面还压着一层约 18% 的整屏黑）。
     */
    public static void modalPanel(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0 || y1 <= y0) return;
        g.fill(x0, y0, x1, y1, GuiFx.fade(0xEE252C24, a));
        g.fill(x0, y0, x1, y0 + 1, GuiFx.fade(0x59FFFFFF, a));
    }

    /** 弹窗主按钮（参考 {@code #4CAF50}、r=3px）；{@code lift} 用于悬停提亮。 */
    public static void primaryButton(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha, float lift) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0) return;
        int fill = GuiFx.mix(0xFF4CAF50, 0xFFFFFFFF, 0.12F * CrateStage.clamp01(lift));
        GuiFx.roundRect(g, x0, y0, x1, y1, 3, GuiFx.fade(fill, a));
    }

    /** 弹窗次级按钮：透明底 + 1px 白色 35% 描边。 */
    public static void ghostButton(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha, float lift) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0) return;
        GuiFx.roundOutline(g, x0, y0, x1, y1, 3,
                GuiFx.alpha(0xFFFFFFFF, (int) ((0.35F + 0.35F * CrateStage.clamp01(lift)) * 255.0F * a)));
    }

    /**
     * 品质横条：参考视频展示页在 y=175 拉一条 2px 横条，x 578→1345（占屏宽 40%），
     * 两端最后 30px 渐隐。
     */
    public static void rarityRule(GuiGraphics g, int x0, int y0, int x1, int color, float alpha) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0) return;
        int fade = Math.min(30, Math.max(1, (x1 - x0) / 8));
        GuiFx.gradientFadeX(g, x0, y0, x0 + fade, y0 + 2, GuiFx.fade(color, a), 0.0F, 1.0F, 8);
        GuiFx.gradientFadeX(g, x1 - fade, y0, x1, y0 + 2, GuiFx.fade(color, a), 1.0F, 0.0F, 8);
        g.fill(x0 + fade, y0, x1 - fade, y0 + 2, GuiFx.fade(color, a));
    }

    /**
     * 展示页左上角的箱子徽标（参考 86×86px：危险黄三角 + 白星 + {@code CS GO} 铭牌）。
     * 这里用等距箱体的正面配色画一个方块化版本，保持与场景里的箱子同一套颜色。
     */
    public static void crateBadge(GuiGraphics g, int x, int y, int size, int accent, float alpha) {
        g.setColor(1,1,1,alpha);
        g.blit(art("case_badge"), x, y, size, size, 0,0,96,96,96,96);
        g.setColor(1,1,1,1);
    }

    public static void specialMedallion(GuiGraphics g, float x, float y, float w, float h, float alpha) {
        g.fillGradient((int)x,(int)y,(int)(x+w),(int)(y+h),GuiFx.fade(0xFF746531,alpha),GuiFx.fade(0xFF292713,alpha));
        float cx=x+w*.5F, cy=y+h*.46F;
        ellipse(g,cx,cy,h*.28F,h*.28F,GuiFx.fade(0xFFDECE85,alpha));
        ellipse(g,cx,cy,h*.24F,h*.24F,GuiFx.fade(0xFF81732D,alpha));
        for(int side : new int[]{-1,1}) for(int i=0;i<7;i++) {
            double angle = Math.toRadians(28+i*17);
            float lx=cx+side*(float)Math.sin(angle)*h*.42F, ly=cy+(float)Math.cos(angle)*h*.38F;
            polygon(g,new float[]{lx, lx+side*9, lx+side*4}, new float[]{ly+4,ly-8,ly-12},3,GuiFx.fade(0xFFD8C986,alpha));
        }
        g.fill((int)x,(int)(y+h-5),(int)(x+w),(int)(y+h),GuiFx.fade(0xFFD9C93E,alpha));
    }

    // =====================================================================
    // 工具（本类只提供绘制件；粒子/光柱在参考视频里并不存在，因此没有对应实现）
    // =====================================================================
}
