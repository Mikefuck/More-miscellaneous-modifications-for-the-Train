package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 开箱特效粒子系统：光点、星尘、能量流、光柱等华丽特效。
 * 
 * <p>设计原则：轻量级2D粒子，按品质分层渲染，支持批量更新与淡入淡出。</p>
 */
public final class CrateParticles {
    
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    
    public void clear() {
        particles.clear();
    }
    
    public void tick(float delta) {
        particles.removeIf(p -> !p.tick(delta));
    }
    
    public void render(GuiGraphics g, float alpha) {
        for (Particle p : particles) {
            p.render(g, alpha);
        }
    }
    
    // =====================================================================
    // 粒子生成器
    // =====================================================================
    
    /** 箱子周围的环绕星尘 - 持续生成 */
    public void spawnAmbientDust(float cx, float cy, float radius, int color, float rate) {
        if (random.nextFloat() > rate) return;
        
        float angle = random.nextFloat() * Mth.TWO_PI;
        float dist = radius * (0.8F + random.nextFloat() * 0.4F);
        float px = cx + Mth.cos(angle) * dist;
        float py = cy + Mth.sin(angle) * dist;
        
        particles.add(new FloatingDust(px, py, color, 
            1.5F + random.nextFloat() * 2.5F, // size
            1000 + random.nextInt(1500),     // lifetime
            angle + Mth.HALF_PI,             // drift direction
            20.0F + random.nextFloat() * 30.0F // drift speed
        ));
    }
    
    /** 开盖爆发 - 一次性大量粒子 */
    public void spawnOpenBurst(float cx, float cy, int color, int count) {
        for (int i = 0; i < count; i++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float speed = 80.0F + random.nextFloat() * 120.0F;
            float vx = Mth.cos(angle) * speed;
            float vy = Mth.sin(angle) * speed - 60.0F; // 向上偏移
            
            particles.add(new BurstParticle(cx, cy, vx, vy, color,
                2.0F + random.nextFloat() * 3.0F,
                600 + random.nextInt(800)
            ));
        }
    }
    
    /** 转盘光柱 - 从底部向上的光带 */
    public void spawnLightBeam(float x, float y0, float y1, int color, float width) {
        particles.add(new LightBeam(x, y0, y1, color, width, 800));
    }
    
    /** 卡片轨迹光尾 - 运动时产生拖尾 */
    public void spawnCardTrail(float x, float y, int color, float rate) {
        if (random.nextFloat() > rate) return;
        
        particles.add(new TrailParticle(x, y, color, 
            1.5F + random.nextFloat() * 1.5F,
            300 + random.nextInt(300)
        ));
    }
    
    /** 品质爆发特效 - 中奖时的华丽粒子 */
    public void spawnQualityExplosion(float cx, float cy, int color, int count) {
        // 内圈快速爆发
        for (int i = 0; i < count / 2; i++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float speed = 150.0F + random.nextFloat() * 100.0F;
            particles.add(new BurstParticle(
                cx, cy,
                Mth.cos(angle) * speed,
                Mth.sin(angle) * speed - 40.0F,
                color,
                3.0F + random.nextFloat() * 2.0F,
                400 + random.nextInt(400)
            ));
        }
        
        // 外圈慢速扩散
        for (int i = 0; i < count / 2; i++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float speed = 40.0F + random.nextFloat() * 60.0F;
            particles.add(new FloatingDust(
                cx + Mth.cos(angle) * 20.0F,
                cy + Mth.sin(angle) * 20.0F,
                color,
                2.0F + random.nextFloat() * 3.0F,
                1200 + random.nextInt(800),
                angle,
                speed
            ));
        }
    }
    
    /** 物品展示光环 - 环绕旋转的光点 */
    public void spawnItemHalo(float cx, float cy, float radius, int color, int count) {
        for (int i = 0; i < count; i++) {
            float angle = i * Mth.TWO_PI / count;
            particles.add(new OrbitParticle(cx, cy, radius, angle, color, 
                2.5F, 2000, 0.5F // size, life, rotation speed
            ));
        }
    }
    
    /** 地面光晕扩散 - 从中心向外的光波 */
    public void spawnGroundRipple(float cx, float cy, int color, float maxRadius) {
        particles.add(new RippleParticle(cx, cy, color, maxRadius, 1000));
    }
    
    // =====================================================================
    // 粒子类型
    // =====================================================================
    
    /** 基础粒子 */
    private static abstract class Particle {
        protected float x, y;
        protected int color;
        protected float size;
        protected int life, maxLife;
        
        Particle(float x, float y, int color, float size, int life) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.size = size;
            this.life = this.maxLife = life;
        }
        
        boolean tick(float delta) {
            life -= (int) delta;
            if (life <= 0) return false;
            update(delta);
            return true;
        }
        
        protected abstract void update(float delta);
        protected abstract void render(GuiGraphics g, float alpha);
        
        protected float alpha() {
            float fade = Math.min(life / 200.0F, (maxLife - life) / 300.0F);
            return Mth.clamp(fade, 0.0F, 1.0F);
        }
    }
    
    /** 漂浮尘埃 - 缓慢飘动 */
    private static class FloatingDust extends Particle {
        private final float angle, speed;
        
        FloatingDust(float x, float y, int color, float size, int life, float angle, float speed) {
            super(x, y, color, size, life);
            this.angle = angle;
            this.speed = speed;
        }
        
        @Override
        protected void update(float delta) {
            x += Mth.cos(angle) * speed * delta / 1000.0F;
            y += Mth.sin(angle) * speed * delta / 1000.0F;
        }
        
        @Override
        protected void render(GuiGraphics g, float alpha) {
            float a = alpha() * alpha;
            int c = GuiFx.fade(color, a);
            GuiFx.glow(g, (int)x, (int)y, (int)(size * 1.5F), (int)(size * 1.5F), c, a * 0.6F);
            g.fill((int)(x - size/2), (int)(y - size/2), 
                   (int)(x + size/2), (int)(y + size/2), c);
        }
    }
    
    /** 爆发粒子 - 快速向外飞散后减速 */
    private static class BurstParticle extends Particle {
        private float vx, vy;
        
        BurstParticle(float x, float y, float vx, float vy, int color, float size, int life) {
            super(x, y, color, size, life);
            this.vx = vx;
            this.vy = vy;
        }
        
        @Override
        protected void update(float delta) {
            float dt = delta / 1000.0F;
            x += vx * dt;
            y += vy * dt;
            
            // 空气阻力
            vx *= Math.pow(0.92, delta / 16.0);
            vy *= Math.pow(0.92, delta / 16.0);
            
            // 重力
            vy += 120.0F * dt;
        }
        
        @Override
        protected void render(GuiGraphics g, float alpha) {
            float a = alpha() * alpha;
            int c = GuiFx.fade(color, a);
            float r = size * (1.0F + (1.0F - alpha()) * 0.3F); // 逐渐变大
            GuiFx.glow(g, (int)x, (int)y, (int)(r * 2), (int)(r * 2), c, a * 0.5F);
        }
    }
    
    /** 光束 - 垂直光柱 */
    private static class LightBeam extends Particle {
        private final float y1, width;
        
        LightBeam(float x, float y0, float y1, int color, float width, int life) {
            super(x, y0, color, width, life);
            this.y1 = y1;
            this.width = width;
        }
        
        @Override
        protected void update(float delta) {}
        
        @Override
        protected void render(GuiGraphics g, float alpha) {
            float a = alpha() * alpha;
            int steps = 16;
            float w = width * (1.0F + (float)Math.sin(life * 0.003) * 0.2F); // 脉动
            
            for (int i = 0; i < steps; i++) {
                float t = i / (float)steps;
                float yPos = y + (y1 - y) * t;
                float fadeOut = 1.0F - t * t; // 向上渐隐
                int c = GuiFx.fade(color, a * fadeOut * 0.6F);
                g.fill((int)(x - w/2), (int)yPos, (int)(x + w/2), (int)(yPos + 2), c);
            }
        }
    }
    
    /** 拖尾粒子 - 静止后快速消失 */
    private static class TrailParticle extends Particle {
        TrailParticle(float x, float y, int color, float size, int life) {
            super(x, y, color, size, life);
        }
        
        @Override
        protected void update(float delta) {}
        
        @Override
        protected void render(GuiGraphics g, float alpha) {
            float a = alpha() * alpha * 0.7F;
            int c = GuiFx.fade(color, a);
            g.fill((int)(x - size), (int)(y - size), 
                   (int)(x + size), (int)(y + size), c);
        }
    }
    
    /** 轨道粒子 - 环绕旋转 */
    private static class OrbitParticle extends Particle {
        private final float cx, cy, radius, rotSpeed;
        private float angle;
        
        OrbitParticle(float cx, float cy, float radius, float angle, int color, float size, int life, float rotSpeed) {
            super(cx, cy, color, size, life);
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
            this.angle = angle;
            this.rotSpeed = rotSpeed;
        }
        
        @Override
        protected void update(float delta) {
            angle += rotSpeed * delta / 1000.0F;
            x = cx + Mth.cos(angle) * radius;
            y = cy + Mth.sin(angle) * radius;
        }
        
        @Override
        protected void render(GuiGraphics g, float alpha) {
            float a = alpha() * alpha;
            int c = GuiFx.fade(color, a);
            GuiFx.glow(g, (int)x, (int)y, (int)(size * 2.5F), (int)(size * 2.5F), c, a * 0.8F);
        }
    }
    
    /** 涟漪粒子 - 扩散圆环 */
    private static class RippleParticle extends Particle {
        private final float maxRadius;
        private float currentRadius;
        
        RippleParticle(float cx, float cy, int color, float maxRadius, int life) {
            super(cx, cy, color, 3.0F, life);
            this.maxRadius = maxRadius;
            this.currentRadius = 0.0F;
        }
        
        @Override
        protected void update(float delta) {
            currentRadius += maxRadius * delta / maxLife;
        }
        
        @Override
        protected void render(GuiGraphics g, float alpha) {
            float a = alpha() * 0.4F;
            int c = GuiFx.fade(color, a);
            CrateArt.ellipse(g, x, y, currentRadius, currentRadius * 0.3F, c);
        }
    }
}
