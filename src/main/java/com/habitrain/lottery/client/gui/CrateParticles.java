package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 开箱界面的 2D 粒子：浮尘、余烬、火花、星芒与彩屑。
 *
 * <p>坐标一律是 1920×1080 参考画布；每个渲染帧用真实毫秒推进一次，与帧率无关。
 * 除彩屑外都走 {@link CrateFx#ADD} 加色，叠在一起只会更亮。</p>
 */
public final class CrateParticles {

    public enum Kind {
        /** 悬浮在光里的尘埃：极慢、极淡。 */
        MOTE,
        /** 自下而上飘的余烬，带轻微左右摆动。 */
        EMBER,
        /** 高速火花：按速度方向拉出拖尾，受重力。 */
        SPARK,
        /** 四角星芒：原地闪一下再消失。 */
        STAR,
        /** 旋转的彩色纸片（金/红品质的庆祝）。 */
        CONFETTI,
        /** 被吸向中心的能量点（蓄力阶段）。 */
        CHARGE
    }

    private static final int LIMIT = 900;

    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();

    public void clear() { particles.clear(); }

    public int size() { return particles.size(); }

    public Random random() { return random; }

    public void tick(float deltaMs) {
        float dt = Math.min(0.08F, deltaMs / 1000.0F);
        particles.removeIf(p -> !p.update(dt));
    }

    /** 按层绘制：{@code front=false} 画在主体之后，{@code true} 画在主体之前。 */
    public void render(GuiGraphics g, float alpha, boolean front) {
        if (alpha <= 0.01F) return;
        for (Particle p : particles) if (p.front == front) p.render(g, alpha);
    }

    public Particle spawn(Kind kind, float x, float y, float vx, float vy, float size, float life, int color) {
        Particle p = new Particle(kind, x, y, vx, vy, size, life, color);
        if (particles.size() < LIMIT) particles.add(p);
        return p;
    }

    // =====================================================================
    // 组合发射器
    // =====================================================================

    /** 环形爆发的火花，{@code up} 为额外的上抛速度。 */
    public void burst(float x, float y, int count, float speedMin, float speedMax, float up, int color, boolean front) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = speedMin + random.nextFloat() * (speedMax - speedMin);
            spawn(Kind.SPARK, x, y, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed * 0.7F - up,
                    2.2F + random.nextFloat() * 2.8F, 0.55F + random.nextFloat() * 0.75F,
                    random.nextFloat() < 0.3F ? 0xFFFFFFFF : color).front = front;
        }
    }

    /** 从矩形区域底部往上飘的余烬。 */
    public void embers(float x0, float x1, float y, int count, int color) {
        for (int i = 0; i < count; i++) {
            spawn(Kind.EMBER, x0 + random.nextFloat() * (x1 - x0), y + random.nextFloat() * 20,
                    (random.nextFloat() - 0.5F) * 30, -40 - random.nextFloat() * 90,
                    1.6F + random.nextFloat() * 2.6F, 1.6F + random.nextFloat() * 2.2F, color);
        }
    }

    /** 光里的浮尘：矩形范围内随机出现，缓慢漂移。 */
    public void motes(float x0, float y0, float x1, float y1, int count, int color) {
        for (int i = 0; i < count; i++) {
            spawn(Kind.MOTE, x0 + random.nextFloat() * (x1 - x0), y0 + random.nextFloat() * (y1 - y0),
                    (random.nextFloat() - 0.5F) * 14, -4 - random.nextFloat() * 10,
                    1.2F + random.nextFloat() * 2.2F, 3.0F + random.nextFloat() * 3.0F, color);
        }
    }

    /** 围绕一点随机闪烁的星芒。 */
    public void stars(float cx, float cy, float rx, float ry, int count, int color, boolean front) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float dist = (float) Math.sqrt(random.nextFloat());
            spawn(Kind.STAR, cx + (float) Math.cos(angle) * rx * dist, cy + (float) Math.sin(angle) * ry * dist,
                    0, -6, 10 + random.nextFloat() * 22, 0.6F + random.nextFloat() * 0.6F,
                    random.nextFloat() < 0.4F ? 0xFFFFFFFF : color).front = front;
        }
    }

    /** 从画面上缘洒下的彩屑。 */
    public void confetti(float x0, float x1, float y, int count, int[] palette) {
        for (int i = 0; i < count; i++) {
            Particle p = spawn(Kind.CONFETTI, x0 + random.nextFloat() * (x1 - x0), y - random.nextFloat() * 80,
                    (random.nextFloat() - 0.5F) * 160, 60 + random.nextFloat() * 120,
                    6 + random.nextFloat() * 6, 2.6F + random.nextFloat() * 1.6F,
                    palette[random.nextInt(palette.length)]);
            p.spin = (random.nextFloat() - 0.5F) * 14;
            p.front = true;
        }
    }

    /** 从外圈被吸向 {@code (cx, cy)} 的能量点。 */
    public void charge(float cx, float cy, float radius, int count, int color) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            float r = radius * (0.75F + random.nextFloat() * 0.5F);
            Particle p = spawn(Kind.CHARGE, cx + (float) Math.cos(angle) * r, cy + (float) Math.sin(angle) * r * 0.62F,
                    0, 0, 2 + random.nextFloat() * 2.5F, 0.55F + random.nextFloat() * 0.35F, color);
            p.tx = cx;
            p.ty = cy;
        }
    }

    // =====================================================================
    // 粒子
    // =====================================================================

    public static final class Particle {
        final Kind kind;
        float x, y, vx, vy, size, spin, angle, tx, ty;
        final float life;
        float age;
        final int color;
        boolean front;

        Particle(Kind kind, float x, float y, float vx, float vy, float size, float life, int color) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
            this.life = life;
            this.color = color;
            this.angle = (float) (Math.random() * Math.PI * 2);
        }

        boolean update(float dt) {
            age += dt;
            if (age >= life) return false;
            switch (kind) {
                case SPARK -> {
                    float drag = (float) Math.pow(0.12, dt);
                    vx *= drag;
                    vy = vy * drag + 520 * dt;
                }
                case EMBER -> {
                    vx += (float) Math.sin((age + x) * 3.1F) * 40 * dt;
                    vy *= (float) Math.pow(0.8, dt);
                }
                case CONFETTI -> {
                    vx *= (float) Math.pow(0.5, dt);
                    vy = Math.min(vy + 140 * dt, 190);
                    vx += (float) Math.sin(age * 5 + angle) * 90 * dt;
                    angle += spin * dt;
                }
                case CHARGE -> {
                    float k = age / life;
                    float pull = k * k * 9.0F;
                    vx = (tx - x) * pull;
                    vy = (ty - y) * pull;
                }
                default -> { }
            }
            x += vx * dt;
            y += vy * dt;
            return true;
        }

        /** 淡入 12%、淡出末段 40%。 */
        float fade() {
            float k = age / life;
            float in = Math.min(1, k / 0.12F);
            float out = Math.min(1, (1 - k) / 0.4F);
            return Math.max(0, Math.min(in, out));
        }

        void render(GuiGraphics g, float alpha) {
            float a = fade() * alpha;
            if (a <= 0.01F) return;
            switch (kind) {
                case MOTE -> CrateFx.glow(g, x, y, size * 2.4F, color, a * 0.55F);
                case EMBER -> {
                    CrateFx.glow(g, x, y, size * 3.2F, color, a * 0.7F);
                    CrateFx.glow(g, x, y, size, 0xFFFFFFFF, a * 0.5F);
                }
                case SPARK -> {
                    float tail = 0.045F;
                    CrateFx.trail(g, x - vx * tail, y - vy * tail, x, y, size, color, a);
                    CrateFx.glow(g, x, y, size * 2.2F, color, a * 0.8F);
                }
                case STAR -> {
                    float k = age / life;
                    float pop = (float) Math.sin(Math.min(1, k) * Math.PI);
                    CrateFx.sparkle(g, x, y, size * (0.4F + 0.6F * pop), angle * 0.2F, color, a * pop);
                }
                case CONFETTI -> {
                    float w = size, h = size * 0.5F * Math.abs((float) Math.cos(angle * 1.7F)) + 0.8F;
                    g.pose().pushPose();
                    g.pose().translate(x, y, 0);
                    g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(angle));
                    int c = GuiFx.fade(color, a);
                    CrateFx.rect(g, -w / 2, -h / 2, w / 2, h / 2, c, c, GuiFx.shade(c, -0.3F), GuiFx.shade(c, -0.3F), false);
                    g.pose().popPose();
                }
                case CHARGE -> {
                    CrateFx.trail(g, x - vx * 0.06F, y - vy * 0.06F, x, y, size, color, a);
                    CrateFx.glow(g, x, y, size * 2.5F, color, a * 0.9F);
                }
            }
        }
    }
}
