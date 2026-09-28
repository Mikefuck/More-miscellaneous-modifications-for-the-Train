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
 * <p>箱子是原版箱子比例的真 3D 模型（14×9×14 空心底座 + 14×5×14 后铰链箱盖 + 2×4×1 锁扣），
 * 按固定的偏航 / 俯仰角正交投影，逐面做背面剔除与受光（顶亮、正面中、侧面暗，暖色主光 + 冷色环境光，
 * 贴地一侧压暗）。贴图由 {@code tools/generate_chest_art.py} 生成，每个 MC 像素 4 个贴图像素。
 * {@code glow} 统一描述「箱内的光」：关盖时从盖缝与锁孔漏出，开盖后照亮内腔与盖底。</p>
 */
public final class CrateArt {

    // =====================================================================
    // 调色板
    // =====================================================================
    /** 界面金线（光标、分隔线）。 */
    public static final int GOLD_LINE = 0xFFF2D25A;
    /** 暖白：箱内光的基色，与箱子强调色混合，不泄露结果品质。 */
    public static final int WARM_LIGHT = 0xFFFFE6B3;
    /** 原版铁锁扣：灰阶贴图 × 该色。 */
    public static final int IRON = 0xFFE6E6EA;

    private CrateArt() {
    }

    // =====================================================================
    // 投影
    // =====================================================================

    /** 模型尺寸（MC 像素）：底座高 9、箱盖高 5，铰链在底座顶面后沿 (y=9, z=14)。 */
    private static final float SIZE = 14, BASE_H = 9, LID_H = 5, HINGE_Y = 9, HINGE_Z = 14;
    private static final double YAW = Math.toRadians(22), PITCH = Math.toRadians(24);
    private static final float CY = (float) Math.cos(YAW), SY = (float) Math.sin(YAW),
            CP = (float) Math.cos(PITCH), SP = (float) Math.sin(PITCH);
    /** 模型 1px 的屏幕尺寸 = crateWidth × FIT；0.82 让新箱子与旧箱子的视觉体量相当。 */
    private static final float FIT = .82F / (SIZE * (CY + SY));
    /** 指向观察者的单位向量，用于背面剔除。 */
    private static final float VX = CP * SY, VY = SP, VZ = -CP * CY;
    /** 主光方向（左上前方），已归一化。 */
    private static final float LX = -.449F, LY = .799F, LZ = -.399F;

    /**
     * 箱子的正交投影。{@code crateWidth} 沿用旧箱子的「屏幕总宽」语义，{@code baseY} 是箱底中心的屏幕位置。
     * {@link #px}/{@link #py} 的单位坐标：x/z 0..1 覆盖箱子占地（z=0 为正面），y 0..1 是底座高度，y=1 即箱口。
     */
    public static final class Iso {
        private final float ox, oy, s;
        public Iso(float centerX, float baseY, float crateWidth) {
            ox = centerX; oy = baseY; s = Math.max(4, crateWidth) * FIT;
        }
        /** 模型坐标（MC 像素）→ 屏幕。 */
        float sx(float x, float y, float z) { return ox + s * ((x - 7) * CY + (z - 7) * SY); }
        float sy(float x, float y, float z) { return oy - s * (-(x - 7) * SP * SY + y * CP + (z - 7) * SP * CY); }
        public float px(float x, float y, float z) { return sx(x * SIZE, y * BASE_H, z * SIZE); }
        public float py(float x, float y, float z) { return sy(x * SIZE, y * BASE_H, z * SIZE); }
        public float scale() { return s * SIZE * .5F; }
        public float heightScale() { return s * BASE_H; }
    }

    private static ResourceLocation art(String name) {
        return ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/crate/" + name + ".png");
    }
    private static final ResourceLocation CHEST = art("chest");
    private static final ResourceLocation[] COURTYARD = {art("courtyard_4"), art("courtyard_12"), art("courtyard_24")};
    private static final float ATLAS = 256;
    // 图集区域 {u, v, w, h}，与 tools/generate_chest_art.py 的 REGIONS 一致
    private static final int[] BASE_FRONT = {0, 0, 56, 36}, BASE_SIDE = {56, 0, 56, 36},
            LID_FRONT = {0, 36, 56, 20}, LID_SIDE = {56, 36, 56, 20}, LID_TOP = {112, 0, 56, 56},
            LID_UNDER = {168, 0, 56, 56}, RIM = {112, 56, 56, 56}, FLOOR = {168, 56, 48, 48},
            WALL_BACK = {0, 112, 48, 32}, WALL_SIDE = {48, 112, 48, 32},
            LOCK_FRONT = {96, 112, 8, 16}, LOCK_SIDE = {104, 112, 4, 16}, LOCK_TOP = {108, 112, 8, 4};

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

    // =====================================================================
    // 箱体
    // =====================================================================

    private static final float[] N_FRONT = {0, 0, -1}, N_BACK = {0, 0, 1}, N_RIGHT = {1, 0, 0},
            N_LEFT = {-1, 0, 0}, N_UP = {0, 1, 0}, N_DOWN = {0, -1, 0};
    private static final int WHITE = 0xFFFFFFFF;

    /** 兼容旧调用点：无箱内光、原色。 */
    public static void crate(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                             int accent, float intensity) {
        crateLit(g, cx, baseY, width, lidAngle, 0xFFFFFFFF, 0, WARM_LIGHT, 1);
    }

    /**
     * 画一个开箱中的箱子（铁锁扣）。
     *
     * @param lidAngle 箱盖翻转角度，0 为关闭，约 112° 为完全掀开
     * @param ambient  整体受光色（压暗场景时传入灰色）
     * @param glow     箱内光强度 0..1：关盖时从缝隙漏出，开盖后照亮内腔与盖底
     * @param light    箱内光的颜色
     */
    public static void crateLit(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                                int ambient, float glow, int light, float alpha) {
        chest(g, cx, baseY, width, lidAngle, ambient, glow, light, IRON, alpha);
    }

    /** 可配置箱子：同一套橡木箱体，锁扣按强调色染色。{@code preset}/{@code badge} 仅为兼容保留。 */
    public static void crateStyled(GuiGraphics g, float cx, float baseY, float width, float lidAngle,
                                   int accent, float intensity, String preset, String badge) {
        crateStyledLit(g, cx, baseY, width, lidAngle, accent, preset, badge, 0xFFFFFFFF, 0, WARM_LIGHT, 1);
    }

    public static void crateStyledLit(GuiGraphics g, float cx, float baseY, float width, float lidAngle, int accent,
                                      String preset, String badge, int ambient, float glow, int light, float alpha) {
        chest(g, cx, baseY, width, lidAngle, ambient, glow, light, GuiFx.mix(IRON, accent | 0xFF000000, .55F), alpha);
    }

    private static void chest(GuiGraphics g, float cx, float baseY, float width, float lidAngle, int ambient,
                              float glow, int light, int metal, float alpha) {
        Iso iso = new Iso(cx, baseY, width);
        float angle = Mth.clamp(lidAngle, 0, 120);
        Chest p = new Chest(g, iso, angle, ambient, alpha);
        float lit = Mth.clamp(glow, 0, 1), open = Mth.clamp(lidAngle / 100F, 0, 1);
        // 盖子翻过 90° 后整体落在底座后方，先画；之前整体在底座上方，后画
        if (angle > 90) drawLid(p, metal, light, lit * open);
        drawBase(p, light, lit * open);
        if (angle <= 90) drawLid(p, metal, light, lit * open);

        // 关盖时的漏光：前 / 右盖缝与锁孔
        float leak = lit * (1 - open);
        if (leak > .01F) {
            float w = iso.scale() * 2;
            float[] a = {0, BASE_H, 0}, b = {SIZE, BASE_H, 0}, c = {SIZE, BASE_H, SIZE};
            CrateFx.lineGlow(g, p.x(a), p.y(a), p.x(b), p.y(b), w * .008F, w * .07F, light, leak * p.alpha);
            CrateFx.lineGlow(g, p.x(b), p.y(b), p.x(c), p.y(c), w * .006F, w * .05F, light, leak * .8F * p.alpha);
            float[] key = p.lid(new float[]{7, 8.6F, -1.05F});
            CrateFx.glow(g, p.x(key), p.y(key), w * .07F, w * .08F, light, leak * .9F * p.alpha, true);
        }
    }

    /** 底座：内腔（底板、内后壁、内左壁 + 箱内光）→ 外壁 → 箱沿。 */
    private static void drawBase(Chest p, int light, float inner) {
        if (p.angle > .5F) {
            float[] floor = p.face(new float[][]{{1, 1, 13}, {13, 1, 13}, {13, 1, 1}, {1, 1, 1}}, N_UP, FLOOR, false, WHITE, true);
            float[] back = p.face(new float[][]{{1, 9, 13}, {13, 9, 13}, {13, 1, 13}, {1, 1, 13}}, N_FRONT, WALL_BACK, false, WHITE, true);
            float[] side = p.face(new float[][]{{1, 9, 1}, {1, 9, 13}, {1, 1, 13}, {1, 1, 1}}, N_RIGHT, WALL_SIDE, false, WHITE, true);
            if (inner > .01F) {
                float k = inner * p.alpha;
                glowFace(p.g, floor, light, .45F * k, .45F * k, .90F * k, .90F * k);
                glowFace(p.g, back, light, .18F * k, .18F * k, .62F * k, .62F * k);
                glowFace(p.g, side, light, .16F * k, .16F * k, .50F * k, .50F * k);
            }
        }
        p.face(new float[][]{{0, 9, 0}, {14, 9, 0}, {14, 0, 0}, {0, 0, 0}}, N_FRONT, BASE_FRONT, false, WHITE, false);
        p.face(new float[][]{{14, 9, 0}, {14, 9, 14}, {14, 0, 14}, {14, 0, 0}}, N_RIGHT, BASE_SIDE, false, WHITE, false);
        p.face(new float[][]{{0, 9, 14}, {0, 9, 0}, {0, 0, 0}, {0, 0, 14}}, N_LEFT, BASE_SIDE, false, WHITE, false);
        p.face(new float[][]{{14, 9, 14}, {0, 9, 14}, {0, 0, 14}, {14, 0, 14}}, N_BACK, BASE_SIDE, false, WHITE, false);
        if (p.angle > .5F) {
            p.face(new float[][]{{0, 9, 14}, {14, 9, 14}, {14, 9, 0}, {0, 9, 0}}, N_UP, RIM, false, WHITE, false);
        }
    }

    /** 箱盖与锁扣：锁扣在盖正面外侧，正面朝向镜头时锁扣在前，否则被盖挡住。 */
    private static void drawLid(Chest p, int metal, int light, float inner) {
        boolean lockInFront = p.visible(p.lidNormal(N_FRONT));
        if (!lockInFront) drawLock(p, metal);
        p.face(new float[][]{{0, 14, 0}, {14, 14, 0}, {14, 9, 0}, {0, 9, 0}}, N_FRONT, LID_FRONT, true, WHITE, false);
        p.face(new float[][]{{14, 14, 0}, {14, 14, 14}, {14, 9, 14}, {14, 9, 0}}, N_RIGHT, LID_SIDE, true, WHITE, false);
        p.face(new float[][]{{0, 14, 14}, {0, 14, 0}, {0, 9, 0}, {0, 9, 14}}, N_LEFT, LID_SIDE, true, WHITE, false);
        p.face(new float[][]{{14, 14, 14}, {0, 14, 14}, {0, 9, 14}, {14, 9, 14}}, N_BACK, LID_SIDE, true, WHITE, false);
        float[] top = p.face(new float[][]{{0, 14, 14}, {14, 14, 14}, {14, 14, 0}, {0, 14, 0}}, N_UP, LID_TOP, true, WHITE, false);
        float[] under = p.face(new float[][]{{0, 9, 0}, {14, 9, 0}, {14, 9, 14}, {0, 9, 14}}, N_DOWN, LID_UNDER, true, WHITE, false);
        // 盖底被箱内光照亮，靠铰链一侧最亮
        if (under != null && inner > .01F) {
            float k = inner * p.alpha;
            glowFace(p.g, under, light, .16F * k, .16F * k, .72F * k, .72F * k);
        }
        // 盖顶前沿迎着主光的一道细高光
        if (top != null) {
            int sheen = GuiFx.fade(0xFFFFF1D8, .16F * p.alpha * p.brightness);
            line(p.g, top[6], top[7], top[4], top[5], Math.max(1, p.iso.s * .45F), sheen);
        }
        if (lockInFront) drawLock(p, metal);
    }

    private static void drawLock(Chest p, int metal) {
        p.face(new float[][]{{6, 11, -1}, {8, 11, -1}, {8, 7, -1}, {6, 7, -1}}, N_FRONT, LOCK_FRONT, true, metal, false);
        p.face(new float[][]{{8, 11, -1}, {8, 11, 0}, {8, 7, 0}, {8, 7, -1}}, N_RIGHT, LOCK_SIDE, true, metal, false);
        p.face(new float[][]{{6, 11, 0}, {6, 11, -1}, {6, 7, -1}, {6, 7, 0}}, N_LEFT, LOCK_SIDE, true, metal, false);
        p.face(new float[][]{{6, 11, 0}, {8, 11, 0}, {8, 11, -1}, {6, 11, -1}}, N_UP, LOCK_TOP, true, metal, false);
        p.face(new float[][]{{6, 7, -1}, {8, 7, -1}, {8, 7, 0}, {6, 7, 0}}, N_DOWN, LOCK_TOP, true, metal, false);
    }

    /** 投影后的面叠一层加色辉光；{@code k0..k3} 为四个角（左上、右上、右下、左下）的强度。 */
    private static void glowFace(GuiGraphics g, float[] q, int light, float k0, float k1, float k2, float k3) {
        if (q == null) return;
        quadColorsAdd(g, new float[]{q[0], q[2], q[4], q[6]}, new float[]{q[1], q[3], q[5], q[7]},
                GuiFx.fade(light, k0), GuiFx.fade(light, k1), GuiFx.fade(light, k2), GuiFx.fade(light, k3));
    }

    /** 一次绘制的箱子状态：投影、箱盖角度、整体受光与透明度；负责面的剔除、受光与贴图。 */
    private static final class Chest {
        final GuiGraphics g;
        final Iso iso;
        final float angle, alpha, brightness;
        private final float cos, sin, ar, ag, ab;

        Chest(GuiGraphics g, Iso iso, float angle, int ambient, float alpha) {
            this.g = g;
            this.iso = iso;
            this.angle = angle;
            this.alpha = Mth.clamp(alpha, 0, 1);
            double t = Math.toRadians(angle);
            cos = (float) Math.cos(t);
            sin = (float) Math.sin(t);
            ar = (ambient >> 16 & 0xFF) / 255F;
            ag = (ambient >> 8 & 0xFF) / 255F;
            ab = (ambient & 0xFF) / 255F;
            brightness = (ar + ag + ab) / 3;
        }

        /** 箱盖局部坐标 → 模型坐标：绕铰链 (y=9, z=14) 在 y-z 平面内向后翻。 */
        float[] lid(float[] p) {
            float dy = p[1] - HINGE_Y, dz = p[2] - HINGE_Z;
            return new float[]{p[0], HINGE_Y + dy * cos - dz * sin, HINGE_Z + dy * sin + dz * cos};
        }

        float[] lidNormal(float[] n) {
            return new float[]{n[0], n[1] * cos - n[2] * sin, n[1] * sin + n[2] * cos};
        }

        boolean visible(float[] n) {
            return n[0] * VX + n[1] * VY + n[2] * VZ > 1e-3F;
        }

        float x(float[] p) { return iso.sx(p[0], p[1], p[2]); }
        float y(float[] p) { return iso.sy(p[0], p[1], p[2]); }

        /**
         * 画一个面：{@code pts} 按贴图的左上、右上、右下、左下给出（MC 像素，箱盖件为局部坐标）。
         * 背对镜头时不画并返回 null，否则返回投影后的 {x0,y0,…,x3,y3}。
         */
        float[] face(float[][] pts, float[] normal, int[] uv, boolean onLid, int tint, boolean interior) {
            float[] n = onLid ? lidNormal(normal) : normal;
            if (!visible(n)) return null;
            float diffuse = Math.max(0, n[0] * LX + n[1] * LY + n[2] * LZ);
            // 右侧 ≈ .46、正面 ≈ .71、顶面 ≈ .96，接近原版方块的分面明暗
            float lum = .46F + .62F * diffuse;
            // 暖色主光 + 冷色天光：迎光面偏暖，背光面偏冷
            float wr = Mth.lerp(diffuse, .86F, 1.00F), wg = Mth.lerp(diffuse, .90F, .97F), wb = Mth.lerp(diffuse, 1.00F, .88F);
            float tr = (tint >> 16 & 0xFF) / 255F, tg = (tint >> 8 & 0xFF) / 255F, tb = (tint & 0xFF) / 255F;
            float u0 = uv[0] / ATLAS, v0 = uv[1] / ATLAS, u1 = (uv[0] + uv[2]) / ATLAS, v1 = (uv[1] + uv[3]) / ATLAS;
            float[] us = {u0, u1, u1, u0}, vs = {v0, v0, v1, v1};
            int a = (int) (255 * alpha);
            float[] out = new float[8];
            g.flush();
            VertexConsumer v = g.bufferSource().getBuffer(faceType(CHEST));
            Matrix4f pose = g.pose().last().pose();
            for (int i = 0; i < 4; i++) {
                float[] p = onLid ? lid(pts[i]) : pts[i];
                float k = lum * occlusion(p[1], interior);
                out[i * 2] = x(p);
                out[i * 2 + 1] = y(p);
                v.addVertex(pose, out[i * 2], out[i * 2 + 1], 0)
                        .setColor(channel(wr * tr * ar * k), channel(wg * tg * ag * k), channel(wb * tb * ab * k), a)
                        .setUv(us[i], vs[i]).setLight(15728880);
            }
            g.flush();
            return out;
        }
    }

    /** 贴地压暗：外壁下半部渐暗；内腔越深越暗。 */
    private static float occlusion(float y, boolean interior) {
        if (interior) return .40F + .60F * Mth.clamp((y - 1) / (BASE_H - 1), 0, 1);
        return .74F + .26F * Mth.clamp(y / 5F, 0, 1);
    }

    private static int channel(float v) {
        return (int) (255 * Mth.clamp(v, 0, 1));
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
}
