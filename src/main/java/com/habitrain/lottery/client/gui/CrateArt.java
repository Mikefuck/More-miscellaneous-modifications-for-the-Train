package com.habitrain.lottery.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * 开箱界面的美术件：参考视频重建的箱体与庭院、实时翻盖几何，以及卡片 / 面板 / 按钮。
 *
 * <p>箱体仍是等距投影的三张贴图面；箱盖绕后铰链翻转，翻过 90° 之后露出的是
 * 被箱内光照亮的盖底（贴图压暗 + 自下而上的加色辉光），而不是一块纯黑多边形。
 * {@code glow} 统一描述「箱内的光」：关盖时从缝隙、锁扣与徽标漏出，开盖后照亮内腔与盖底。</p>
 */
public final class CrateArt {

    // =====================================================================
    // 调色板
    // =====================================================================
    public static final int BODY_FRONT = 0xFF2A2017, BODY_FRONT_LOW = 0xFF1C130D,
            BODY_SIDE = 0xFF231A12, BODY_SIDE_LOW = 0xFF170F09,
            BODY_BACK = 0xFF140D08, BODY_INTERIOR = 0xFF0B0B0B, BODY_INTERIOR_DEEP = 0xFF050506,
            EDGE = 0xFF514437, EDGE_DARK = 0xFF0D0906,
            HAZARD = 0xFFE8C21A, HAZARD_DEEP = 0xFFD9A31A, HAZARD_INK = 0xFF1A1A1A,
            STEEL = 0xFF3B2C25, STEEL_LIGHT = 0xFF695545, RIB = 0xFF23201C,
            PLATE = 0xFFBB9F85;
    /** 界面金线（光标、分隔线）。 */
    public static final int GOLD_LINE = 0xFFF2D25A;
    /** 暖白：箱内光的基色，与箱子强调色混合，不泄露结果品质。 */
    public static final int WARM_LIGHT = 0xFFFFE6B3;

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

    /** 与 {@link RenderType#text} 相同，但关闭背面剔除：翻过去的盖底也要画出来。 */
    private static final Map<ResourceLocation, RenderType> FACE_TYPES = new HashMap<>();

    private static RenderType faceType(ResourceLocation texture) {
        return FACE_TYPES.computeIfAbsent(texture, id -> {
            RenderType base = RenderType.text(id);
            return new RenderType("habitrain_crate_face_" + id.getPath().replace('/', '_'),
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS, 1024, false, true,
                    () -> { base.setupRenderState(); RenderSystem.disableCull(); },
                    () -> { RenderSystem.enableCull(); base.clearRenderState(); }) { };
        });
    }

    /** 四个本地坐标点（左上、右上、右下、左下）贴一张贴图，按 {@code tint} 着色。 */
    private static void face(GuiGraphics g, Iso iso, ResourceLocation texture, float[][] points, int tint, float alpha) {
        g.flush();
        VertexConsumer v = g.bufferSource().getBuffer(faceType(texture));
        Matrix4f pose = g.pose().last().pose();
        int r = tint >> 16 & 0xFF, gr = tint >> 8 & 0xFF, b = tint & 0xFF, a = (int) (255 * Mth.clamp(alpha, 0, 1));
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i : new int[]{0, 3, 2, 1}) {
            float[] p = points[i];
            v.addVertex(pose, iso.px(p[0], p[1], p[2]), iso.py(p[0], p[1], p[2]), 0)
                    .setColor(r, gr, b, a).setUv(uv[i][0], uv[i][1]).setLight(15728880);
        }
        g.flush();
    }

    private static float[] xs(Iso iso, float[][] p) {
        float[] out = new float[p.length];
        for (int i = 0; i < p.length; i++) out[i] = iso.px(p[i][0], p[i][1], p[i][2]);
        return out;
    }

    private static float[] ys(Iso iso, float[][] p) {
        float[] out = new float[p.length];
        for (int i = 0; i < p.length; i++) out[i] = iso.py(p[i][0], p[i][1], p[i][2]);
        return out;
    }

    // =====================================================================
    // 箱体
    // =====================================================================

    /** 兼容旧调用点：无箱内光、原色。 */
    public static void crate(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                             int accent, float intensity) {
        crateLit(g, cx, baseY, width, lidAngle, 0xFFFFFFFF, 0, WARM_LIGHT, 1);
    }

    /**
     * 画一个开箱中的箱子。
     *
     * @param lidAngle 箱盖翻转角度，0 为关闭，约 112° 为完全掀开
     * @param ambient  整体受光色（压暗场景时传入灰色）
     * @param glow     箱内光强度 0..1：关盖时从缝隙漏出，开盖后照亮内腔与盖底
     * @param light    箱内光的颜色
     */
    public static void crateLit(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                                int ambient, float glow, int light, float alpha) {
        Iso iso = new Iso(cx, baseY, width);
        float theta = (float) Math.toRadians(Mth.clamp(lidAngle, 0, 120));
        float sin = (float) Math.sin(theta), cos = (float) Math.cos(theta);
        // The lid is shallow; its projected rise must not cross the title block.
        float lift = sin * .34F;
        float open = Mth.clamp(lidAngle / 100F, 0, 1);
        float lit = Mth.clamp(glow, 0, 1);

        // ---- 内腔：开盖后由箱内光自下而上照亮 ----
        float[][] mouth = {{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
        int deep = GuiFx.mix(BODY_INTERIOR_DEEP, light, 0.30F * lit * open);
        int near = GuiFx.mix(BODY_INTERIOR, light, 0.85F * lit * open);
        quadColors(g, xs(iso, mouth), ys(iso, mouth), GuiFx.fade(near, alpha), GuiFx.fade(near, alpha),
                GuiFx.fade(deep, alpha), GuiFx.fade(deep, alpha));

        // ---- 盖底：翻过 90° 之后可见 ----
        float[][] lidPoints = {{0, 1.012F, 1}, {1, 1.012F, 1}, {1, 1.012F + lift, 1 - cos}, {0, 1.012F + lift, 1 - cos}};
        if (lidAngle > 90) {
            int under = GuiFx.mix(GuiFx.shade(ambient | 0xFF000000, -0.62F), light, 0.25F * lit);
            face(g, iso, LID, lidPoints, under, alpha);
            // 箱内光从铰链一侧照上盖底
            float[] lx = xs(iso, lidPoints), ly = ys(iso, lidPoints);
            int hot = GuiFx.fade(light, 0.55F * lit * alpha);
            quadColorsAdd(g, lx, ly, hot, hot, light & 0xFFFFFF, light & 0xFFFFFF);
        }

        // ---- 箱体两面 ----
        int body = ambient | 0xFF000000;
        face(g, iso, FRONT, new float[][]{{0, 1, 0}, {1, 1, 0}, {1, 0, 0}, {0, 0, 0}}, body, alpha);
        face(g, iso, SIDE, new float[][]{{1, 1, 0}, {1, 1, 1}, {1, 0, 1}, {1, 0, 0}}, GuiFx.shade(body, -0.06F), alpha);

        // ---- 盖顶 ----
        if (lidAngle <= 90) face(g, iso, LID, lidPoints, GuiFx.shade(body, 0.04F), alpha);

        // ---- 盖沿厚度：翻开后的前沿是一条被照亮的金属边 ----
        if (lidAngle > 2) {
            float t = 0.035F;
            float[][] rim = {{0, 1.012F + lift, 1 - cos}, {1, 1.012F + lift, 1 - cos},
                    {1, 1.012F + lift - t * cos, 1 - cos - t * sin}, {0, 1.012F + lift - t * cos, 1 - cos - t * sin}};
            int edge = GuiFx.mix(GuiFx.shade(EDGE, -0.2F), light, 0.6F * lit);
            quadColors(g, xs(iso, rim), ys(iso, rim), GuiFx.fade(edge, alpha), GuiFx.fade(edge, alpha),
                    GuiFx.fade(GuiFx.shade(edge, -0.4F), alpha), GuiFx.fade(GuiFx.shade(edge, -0.4F), alpha));
        }
        // 箱口前沿的一条亮边
        line(g, iso.px(0, 1, 0), iso.py(0, 1, 0), iso.px(1, 1, 0), iso.py(1, 1, 0), 1.5F,
                GuiFx.fade(GuiFx.mix(EDGE, light, 0.7F * lit), alpha));

        // ---- 关盖时的漏光：缝隙、锁扣、徽标 ----
        float leak = lit * (1 - open);
        if (leak > 0.01F) {
            float w = iso.scale() * 2;
            CrateFx.lineGlow(g, iso.px(0, 1, 0), iso.py(0, 1, 0), iso.px(1, 1, 0), iso.py(1, 1, 0),
                    w * 0.006F, w * 0.05F, light, leak * alpha);
            CrateFx.lineGlow(g, iso.px(1, 1, 0), iso.py(1, 1, 0), iso.px(1, 1, 1), iso.py(1, 1, 1),
                    w * 0.005F, w * 0.04F, light, leak * 0.8F * alpha);
            CrateFx.glow(g, iso.px(1, .88F, .18F), iso.py(1, .88F, .18F), w * 0.07F, w * 0.07F, light, leak * 0.9F * alpha, true);
            CrateFx.glow(g, iso.px(.5F, .52F, 0), iso.py(.5F, .52F, 0), w * 0.22F, w * 0.26F, light, leak * 0.35F * alpha, true);
        }
    }

    /** 可配置箱子：同一套几何与光照，外加强调色面板与徽记。 */
    public static void crateStyled(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                                   int accent, float intensity, String preset, String badge) {
        crateStyledLit(g, cx, baseY, width, lidAngle, accent, preset, badge, 0xFFFFFFFF, 0, WARM_LIGHT, 1);
    }

    public static void crateStyledLit(GuiGraphics g, float cx, float baseY, float width, float lidAngle, int accent,
                                      String preset, String badge, int ambient, float glow, int light, float alpha) {
        crateLit(g, cx, baseY, width, lidAngle, ambient, glow, light, alpha);
        Iso iso = new Iso(cx, baseY, width);
        int tintedAccent = GuiFx.fade(multiply(accent, ambient), alpha);
        int dark = GuiFx.fade(0xFF000000 | ((multiply(accent, ambient) & 0xFEFEFE) >> 1), alpha);
        float low = "industrial".equals(preset) ? .23F : .30F;
        float high = "hazard".equals(preset) ? .65F : .55F;
        polygon(g, new float[]{iso.px(.12F, low, 0), iso.px(.88F, low, 0), iso.px(.88F, high, 0), iso.px(.12F, high, 0)},
                new float[]{iso.py(.12F, low, 0), iso.py(.88F, low, 0), iso.py(.88F, high, 0), iso.py(.12F, high, 0)}, 4, dark);
        line(g, iso.px(.12F, high, 0), iso.py(.12F, high, 0), iso.px(.88F, high, 0), iso.py(.88F, high, 0), 1.5F, tintedAccent);
        if ("diamond".equals(badge) || "star".equals(badge) || "bolt".equals(badge)) {
            float[] xs = {iso.px(.50F, .59F, 0), iso.px(.63F, .43F, 0), iso.px(.50F, .27F, 0), iso.px(.37F, .43F, 0)};
            float[] ys = {iso.py(.50F, .59F, 0), iso.py(.63F, .43F, 0), iso.py(.50F, .27F, 0), iso.py(.37F, .43F, 0)};
            polygon(g, xs, ys, 4, tintedAccent);
        }
    }

    private static int multiply(int color, int tint) {
        int r = (color >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int gr = (color >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int b = (color & 0xFF) * (tint & 0xFF) / 255;
        return (color & 0xFF000000) | r << 16 | gr << 8 | b;
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

    /** 四个顶点各自着色的四边形（常规混合，不剔除）。 */
    private static void quadColors(GuiGraphics g, float[] x, float[] y, int c0, int c1, int c2, int c3) {
        VertexConsumer v = g.bufferSource().getBuffer(CrateFx.BLEND);
        Matrix4f pose = g.pose().last().pose();
        v.addVertex(pose, x[0], y[0], 0).setColor(c0);
        v.addVertex(pose, x[1], y[1], 0).setColor(c1);
        v.addVertex(pose, x[2], y[2], 0).setColor(c2);
        v.addVertex(pose, x[3], y[3], 0).setColor(c3);
        g.flush();
    }

    private static void quadColorsAdd(GuiGraphics g, float[] x, float[] y, int c0, int c1, int c2, int c3) {
        VertexConsumer v = g.bufferSource().getBuffer(CrateFx.ADD);
        Matrix4f pose = g.pose().last().pose();
        v.addVertex(pose, x[0], y[0], 0).setColor(c0);
        v.addVertex(pose, x[1], y[1], 0).setColor(c1);
        v.addVertex(pose, x[2], y[2], 0).setColor(c2);
        v.addVertex(pose, x[3], y[3], 0).setColor(c3);
        g.flush();
    }

    /** 实心线段（一个细长四边形）。 */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, float width, int color) {
        if ((color >>> 24) == 0) return;
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01F) return;
        float nx = -dy / len * width * 0.5F, ny = dx / len * width * 0.5F;
        quadColors(g, new float[]{x0 + nx, x1 + nx, x1 - nx, x0 - nx}, new float[]{y0 + ny, y1 + ny, y1 - ny, y0 - ny},
                color, color, color, color);
    }

    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        line(g, x0, y0, x1, y1, 1, color);
    }

    /** 椭圆填充。 */
    public static void ellipse(GuiGraphics g, float cx, float cy, float rx, float ry, int color) {
        if ((color >>> 24) == 0 || rx <= 0 || ry <= 0) return;
        int steps = 40;
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

    /** 参考视频中庭院的重建背景，按合焦、推镜、右移三个进度绘制。 */
    public static void backdrop(GuiGraphics g, int width, int height, float focus, float dolly, float pan) {
        backdrop(g, width, height, focus, dolly, pan, 0);
    }

    /** {@code extraBlur} 0..1 把背景额外推向失焦，用于蓄力与展示阶段的景深。 */
    public static void backdrop(GuiGraphics g, int width, int height, float focus, float dolly, float pan, float extraBlur) {
        float blur = Math.min(2, (1 - CrateStage.easeInOutCubic(focus)) * 2 + extraBlur * 2);
        int low = Math.min(2, (int) blur), high = Math.min(2, low + 1);
        float mix = blur - low;
        g.pose().pushPose();
        g.pose().translate(width * .5F - pan * 120, height * .5F, 0);
        float zoom = 1.06F + .07F * dolly + .10F * pan;
        g.pose().scale(zoom, zoom, 1);
        g.pose().translate(-width * .5F, -height * .5F, 0);
        g.blit(COURTYARD[low], 0, 0, width, height, 0, 0, 1920, 1080, 1920, 1080);
        if (mix > .01F) {
            g.setColor(1, 1, 1, mix);
            g.blit(COURTYARD[high], 0, 0, width, height, 0, 0, 1920, 1080, 1920, 1080);
            g.setColor(1, 1, 1, 1);
        }
        g.pose().popPose();
    }

    /** 全屏暗场：转场与「切黑」桥接。 */
    public static void bridge(GuiGraphics g, int width, int height, int color, float strength) {
        float s = CrateStage.clamp01(strength);
        if (s <= 0.01F) return;
        g.fill(0, 0, width, height, GuiFx.fade(color, s));
    }

    // =====================================================================
    // 物品
    // =====================================================================

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
    // 卡片
    // =====================================================================

    /**
     * 品质卡片：暗色玻璃底、自底部升起的品质辉光、物品背后的径向光、底部品质条与细描边。
     *
     * @param glow  0..1 额外辉光（靠近光标、悬停、中奖）
     * @param tint  亮度系数，远离光标的卡片压暗
     */
    public static void rarityCard(GuiGraphics g, float x, float y, float w, float h, int rarity,
                                  float tint, float glow, float alpha) {
        if (w < 4 || h < 4 || alpha <= 0.01F) return;
        float t = Mth.clamp(tint, 0, 1), a = Mth.clamp(alpha, 0, 1), e = Mth.clamp(glow, 0, 1);
        int top = GuiFx.fade(GuiFx.shade(GuiFx.mix(0xFF1B2023, rarity, 0.06F), t - 1), a * 0.94F);
        int bottom = GuiFx.fade(GuiFx.shade(GuiFx.mix(0xFF101315, rarity, 0.20F), t - 1), a * 0.96F);
        CrateFx.vGradient(g, x, y, x + w, y + h, top, bottom, false);
        // 自底部升起的品质光
        CrateFx.vGradient(g, x, y + h * 0.35F, x + w, y + h, rarity & 0xFFFFFF,
                GuiFx.fade(rarity, (0.20F + 0.30F * e) * a * t), true);
        // 物品背后的径向光
        CrateFx.glow(g, x + w * .5F, y + h * .46F, w * .46F, h * .42F, rarity, (0.16F + 0.34F * e) * a * t, true);
        // 顶部高光与细描边
        CrateFx.hairline(g, x, x + w, y, 1, w * 0.25F, GuiFx.fade(0xFFFFFFFF, 0.22F * a * t), false);
        GuiFx.outline(g, (int) x, (int) y, (int) (x + w), (int) (y + h),
                GuiFx.fade(GuiFx.mix(0xFF2A3034, rarity, 0.35F + 0.4F * e), a * (0.55F + 0.45F * e)));
        // 底部品质条 + 向上的辉光
        float bar = Math.max(3, h * 0.022F);
        g.fill((int) x, (int) (y + h - bar), (int) (x + w), (int) (y + h), GuiFx.fade(GuiFx.shade(rarity, (t - 1) * 0.6F), a));
        CrateFx.vGradient(g, x, y + h - bar - h * 0.12F, x + w, y + h - bar, rarity & 0xFFFFFF,
                GuiFx.fade(rarity, (0.35F + 0.45F * e) * a * t), true);
        if (e > 0.02F) CrateFx.rectGlow(g, x, y, x + w, y + h, 10 + 18 * e, rarity, 0.45F * e * a);
    }

    /** 斜向扫光（悬停 / 中奖时掠过卡面），{@code sweep} 0..1。 */
    public static void sheen(GuiGraphics g, float x, float y, float w, float h, float sweep, float strength) {
        if (strength <= 0.01F) return;
        float band = w * 0.28F, skew = h * 0.45F;
        float cx = x - band - skew + (w + band * 2 + skew * 2) * sweep;
        int c = GuiFx.fade(0xFFFFFFFF, 0.22F * Mth.clamp(strength, 0, 1));
        g.enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));
        CrateFx.skewBand(g, cx, y, y + h, band, skew, c);
        g.disableScissor();
    }

    // =====================================================================
    // 面板 / 按钮 / 标签
    // =====================================================================

    /** 玻璃面板：斜切角、纵向渐变、顶部强调色条与细描边。 */
    public static void glassPanel(GuiGraphics g, float x0, float y0, float x1, float y1, float cut,
                                  int accent, float alpha) {
        float a = Mth.clamp(alpha, 0, 1);
        if (a <= 0.01F) return;
        CrateFx.bevelPanel(g, x0, y0, x1, y1, cut, GuiFx.fade(0xF01E2426, a), GuiFx.fade(0xF20D1012, a));
        CrateFx.vGradient(g, x0 + cut, y0 + 1, x1 - cut, y0 + (y1 - y0) * 0.45F,
                GuiFx.fade(accent, 0.10F * a), accent & 0xFFFFFF, true);
        CrateFx.bevelOutline(g, x0, y0, x1, y1, cut, 1, GuiFx.fade(0x3AFFFFFF, a));
        CrateFx.hairline(g, x0 + cut, x1 - cut, y0, 2, (x1 - x0) * 0.2F, GuiFx.fade(accent, 0.95F * a), false);
        CrateFx.hairline(g, x0 + cut, x1 - cut, y0 - 3, 8, (x1 - x0) * 0.25F, GuiFx.fade(accent, 0.28F * a), true);
    }

    /** 兼容仓库等旧调用点的弹窗底板。 */
    public static void modalPanel(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha) {
        glassPanel(g, x0, y0, x1, y1, 10, WarehouseTheme.TEAL, alpha);
    }

    /** 物品条底板。 */
    public static void stripPanel(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0 || y1 <= y0) return;
        CrateFx.vGradient(g, x0, y0, x1, y1, GuiFx.fade(0x9C0C0F10, a), GuiFx.fade(0xC80A0C0D, a), false);
        CrateFx.hairline(g, x0, x1, y0, 1, (x1 - x0) * 0.2F, GuiFx.fade(0x66FFFFFF, a), false);
    }

    /**
     * 主按钮：绿色渐变、内高光、外发光与周期扫光。
     *
     * @param hover 0..1 悬停
     * @param pulse 0..1 呼吸光（号召性按钮）
     */
    public static void primaryButton(GuiGraphics g, float x0, float y0, float x1, float y1, float alpha,
                                     float hover, float pulse, long time) {
        float a = Mth.clamp(alpha, 0, 1);
        if (a <= 0.01F || x1 <= x0) return;
        float h = Mth.clamp(hover, 0, 1);
        float breathe = Mth.clamp(pulse, 0, 1) * (0.5F + 0.5F * (float) Math.sin(time / 420.0));
        CrateFx.rectGlow(g, x0, y0, x1, y1, 14 + 10 * h, 0xFF5BD46A, (0.20F + 0.25F * h + 0.2F * breathe) * a);
        int top = GuiFx.mix(0xFF5FCB63, 0xFFFFFFFF, 0.14F * h), bottom = GuiFx.mix(0xFF2E8A3A, 0xFFFFFFFF, 0.08F * h);
        CrateFx.bevelPanel(g, x0, y0, x1, y1, 5, GuiFx.fade(top, a), GuiFx.fade(bottom, a));
        CrateFx.hairline(g, x0 + 5, x1 - 5, y0 + 1, 1, (x1 - x0) * 0.2F, GuiFx.fade(0xFFC8FFD0, 0.7F * a), false);
        CrateFx.bevelOutline(g, x0, y0, x1, y1, 5, 1, GuiFx.fade(0x5520F040, a));
        float sweep = ((time % 2600L) / 2600.0F) * 1.6F - 0.3F;
        if (sweep >= 0 && sweep <= 1) sheen(g, x0, y0, x1 - x0, y1 - y0, sweep, a * (0.8F + h));
    }

    /** 兼容旧签名。 */
    public static void primaryButton(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha, float lift) {
        primaryButton(g, x0, y0, x1, y1, alpha, lift, 0, 0);
    }

    /** 次级按钮：暗色玻璃 + 细描边，悬停时提亮。 */
    public static void ghostButton(GuiGraphics g, float x0, float y0, float x1, float y1, float alpha, float hover) {
        float a = Mth.clamp(alpha, 0, 1);
        if (a <= 0.01F || x1 <= x0) return;
        float h = Mth.clamp(hover, 0, 1);
        CrateFx.bevelPanel(g, x0, y0, x1, y1, 5, GuiFx.fade(GuiFx.mix(0xB0262C2E, 0xC0404A4C, h), a),
                GuiFx.fade(GuiFx.mix(0xB0141819, 0xC0262E30, h), a));
        CrateFx.bevelOutline(g, x0, y0, x1, y1, 5, 1, GuiFx.fade(GuiFx.mix(0x55FFFFFF, 0xAAFFFFFF, h), a));
        if (h > 0.01F) CrateFx.rectGlow(g, x0, y0, x1, y1, 10, 0xFFFFFFFF, 0.08F * h * a);
    }

    public static void ghostButton(GuiGraphics g, int x0, int y0, int x1, int y1, float alpha, float lift) {
        ghostButton(g, (float) x0, y0, x1, y1, alpha, lift);
    }

    /** 胶囊标签底：暗色 + 细描边，左侧一小段强调色。 */
    public static void chip(GuiGraphics g, float x0, float y0, float x1, float y1, int accent, float alpha) {
        float a = Mth.clamp(alpha, 0, 1);
        if (a <= 0.01F) return;
        CrateFx.bevelPanel(g, x0, y0, x1, y1, (y1 - y0) * 0.28F, GuiFx.fade(0xB0171B1D, a), GuiFx.fade(0xC00E1112, a));
        CrateFx.bevelOutline(g, x0, y0, x1, y1, (y1 - y0) * 0.28F, 1, GuiFx.fade(GuiFx.mix(0x40FFFFFF, accent, 0.3F), a));
        CrateFx.vGradient(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, GuiFx.fade(accent, 0.10F * a), accent & 0xFFFFFF, true);
    }

    /** 两端淡出的品质横条。 */
    public static void rarityRule(GuiGraphics g, int x0, int y0, int x1, int color, float alpha) {
        float a = CrateStage.clamp01(alpha);
        if (a <= 0.01F || x1 <= x0) return;
        CrateFx.hairline(g, x0, x1, y0, 2, (x1 - x0) * 0.3F, GuiFx.fade(color, a), false);
        CrateFx.hairline(g, x0, x1, y0 - 4, 10, (x1 - x0) * 0.35F, GuiFx.fade(color, 0.3F * a), true);
    }

    /** 箱子徽标（危险黄三角 + 白星 + 铭牌）。 */
    public static void crateBadge(GuiGraphics g, int x, int y, int size, int accent, float alpha) {
        g.setColor(1, 1, 1, alpha);
        g.blit(art("case_badge"), x, y, size, size, 0, 0, 96, 96, 96, 96);
        g.setColor(1, 1, 1, 1);
    }
}
