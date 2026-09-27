package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinQuality;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 开箱增强光效：光晕、光柱、辉光边缘、品质特效等。
 * 
 * <p>分层渲染策略：背景光 → 主体 → 前景特效 → 粒子。</p>
 */
public final class CrateEffects {
    
    private CrateEffects() {}
    
    // =====================================================================
    // 品质色彩
    // =====================================================================
    
    /** 获取品质主色 */
    public static int qualityColor(SkinQuality quality) {
        return switch (quality) {
            case WHITE -> 0xFFB8B8B8;
            case BLUE -> 0xFF4A90E2;
            case PURPLE -> 0xFFA855F7;
            case GOLD -> 0xFFFBBF24;
            case RED -> 0xFFEF4444;
        };
    }
    
    /** 获取品质辉光色（更亮） */
    public static int qualityGlow(SkinQuality quality) {
        return switch (quality) {
            case WHITE -> 0xFFE8E8E8;
            case BLUE -> 0xFF60A5FA;
            case PURPLE -> 0xFFC084FC;
            case GOLD -> 0xFFFCD34D;
            case RED -> 0xFFF87171;
        };
    }
    
    /** 获取品质暗色（用于阴影） */
    public static int qualityDark(SkinQuality quality) {
        return switch (quality) {
            case WHITE -> 0xFF505050;
            case BLUE -> 0xFF1E3A5F;
            case PURPLE -> 0xFF4A1D6B;
            case GOLD -> 0xFF7C4A03;
            case RED -> 0xFF7C1D1D;
        };
    }
    
    // =====================================================================
    // 箱子增强光效
    // =====================================================================
    
    /** 箱子底部脉动光晕 - 替代简单阴影 */
    public static void crateGlow(GuiGraphics g, float cx, float baseY, float width, 
                                 int color, float intensity, long time) {
        float pulse = 1.0F + (float)Math.sin(time * 0.002) * 0.15F;
        float radius = width * 0.65F * pulse;
        float alpha = CrateStage.clamp01(intensity) * 0.85F;
        
        // 多层辉光
        for (int layer = 3; layer >= 0; layer--) {
            float layerRadius = radius * (0.4F + layer * 0.2F);
            float layerAlpha = alpha * (1.0F - layer * 0.2F);
            CrateArt.ellipse(g, cx, baseY + width * 0.03F, 
                layerRadius, layerRadius * 0.25F, 
                GuiFx.fade(color, layerAlpha * 0.5F));
        }
    }
    
    /** 箱子边缘辉光线 - 勾勒轮廓 */
    public static void crateEdgeGlow(GuiGraphics g, float cx, float baseY, float width,
                                     int color, float intensity) {
        if (intensity < 0.1F) return;
        
        CrateArt.Iso iso = new CrateArt.Iso(cx, baseY, width);
        int glowColor = GuiFx.fade(color, intensity * 0.7F);
        
        // 顶边高光
        drawGlowLine(g, 
            iso.px(0, 1, 1), iso.py(0, 1, 1),
            iso.px(1, 1, 1), iso.py(1, 1, 1),
            glowColor, 2.5F);
        
        // 前边高光
        drawGlowLine(g,
            iso.px(0, 1, 0), iso.py(0, 1, 0),
            iso.px(1, 1, 0), iso.py(1, 1, 0),
            glowColor, 2.0F);
    }
    
    /** 绘制带辉光的线条 */
    private static void drawGlowLine(GuiGraphics g, float x0, float y0, float x1, float y1,
                                     int color, float thickness) {
        // 外层模糊
        for (int offset = 2; offset >= 0; offset--) {
            float alpha = 0.3F - offset * 0.1F;
            int c = GuiFx.fade(color, alpha);
            drawThickLine(g, x0, y0, x1, y1, thickness + offset * 2, c);
        }
        // 核心亮线
        drawThickLine(g, x0, y0, x1, y1, thickness * 0.5F, color);
    }
    
    private static void drawThickLine(GuiGraphics g, float x0, float y0, float x1, float y1,
                                      float thickness, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float)Math.sqrt(dx * dx + dy * dy);
        if (len < 0.1F) return;
        
        float nx = -dy / len * thickness / 2;
        float ny = dx / len * thickness / 2;
        
        CrateArt.polygon(g,
            new float[]{x0 + nx, x1 + nx, x1 - nx, x0 - nx},
            new float[]{y0 + ny, y1 + ny, y1 - ny, y0 - ny},
            4, color);
    }
    
    // =====================================================================
    // 卡片增强特效
    // =====================================================================
    
    /** 卡片动态边框 - 品质色流动 */
    public static void cardBorderGlow(GuiGraphics g, float x, float y, float w, float h,
                                      int color, float intensity, long time) {
        if (intensity < 0.1F) return;
        
        float phase = (time % 2000) / 2000.0F;
        
        // 顶部流光
        float glowX = x + w * phase;
        GuiFx.gradientFadeX(g, 
            (int)(glowX - 30), (int)y, 
            (int)(glowX + 30), (int)(y + 2),
            GuiFx.fade(color, intensity * 0.9F), 0.0F, 1.0F, 12);
        
        // 底部品质条增强
        float stripeY = y + h - 6;
        g.fill((int)x, (int)stripeY, (int)(x + w), (int)(y + h),
            GuiFx.fade(color, intensity));
        
        // 品质条上沿高光
        GuiFx.gradientFadeX(g,
            (int)x, (int)(stripeY - 1), (int)(x + w), (int)stripeY,
            GuiFx.fade(qualityGlow(SkinQuality.WHITE), intensity * 0.5F),
            0.3F, 1.0F, 8);
    }
    
    /** 卡片选中高光 - 中心聚焦 */
    public static void cardSpotlight(GuiGraphics g, float x, float y, float w, float h,
                                     int color, float intensity) {
        if (intensity < 0.2F) return;
        
        float cx = x + w / 2;
        float cy = y + h / 2;
        
        // 径向渐变聚光
        for (int r = 4; r >= 1; r--) {
            float radius = Math.min(w, h) * 0.4F * r / 4.0F;
            float alpha = intensity * (1.0F - r / 5.0F) * 0.6F;
            CrateArt.ellipse(g, cx, cy, radius, radius * h / w,
                GuiFx.fade(color, alpha));
        }
    }
    
    /** 卡片3D景深效果 - 近大远小的模糊感 */
    public static void cardDepthBlur(GuiGraphics g, float x, float y, float w, float h,
                                     float depth) {
        if (depth < 0.1F) return;
        
        // 用半透明暗层模拟失焦
        int blurColor = GuiFx.alpha(0xFF1A1A2E, (int)(depth * 140));
        g.fill((int)x, (int)y, (int)(x + w), (int)(y + h), blurColor);
    }
    
    // =====================================================================
    // 转盘特效
    // =====================================================================
    
    /** 转盘背景光圈 - 脉动扩散 */
    public static void carouselBackglow(GuiGraphics g, float cx, float cy, float radius,
                                        int color, float intensity, long time) {
        float pulse1 = (time % 3000) / 3000.0F;
        float pulse2 = ((time + 1500) % 3000) / 3000.0F;
        
        drawPulseRing(g, cx, cy, radius * (0.6F + pulse1 * 0.4F), 
            color, intensity * (1.0F - pulse1) * 0.6F);
        drawPulseRing(g, cx, cy, radius * (0.6F + pulse2 * 0.4F),
            color, intensity * (1.0F - pulse2) * 0.4F);
    }
    
    private static void drawPulseRing(GuiGraphics g, float cx, float cy, float radius,
                                      int color, float alpha) {
        if (alpha < 0.05F) return;
        CrateArt.ellipse(g, cx, cy, radius, radius * 0.3F, GuiFx.fade(color, alpha));
    }
    
    /** 光标增强 - 扫描线效果 */
    public static void cursorScanline(GuiGraphics g, float cx, float y0, float y1,
                                      int color, float intensity, long time) {
        if (intensity < 0.1F) return;
        
        // 主光标线
        int x = (int)cx;
        g.fill(x - 2, (int)y0, x + 2, (int)y1, GuiFx.fade(color, intensity * 0.9F));
        
        // 扫描动画
        float scanPos = (time % 2000) / 2000.0F;
        float sy = y0 + (y1 - y0) * scanPos;
        
        // 扫描光束
        for (int i = 0; i < 3; i++) {
            float offset = (i - 1) * 8;
            float yPos = sy + offset;
            float fade = 1.0F - Math.abs(i - 1) * 0.4F;
            g.fill(x - 3, (int)yPos - 1, x + 3, (int)yPos + 1,
                GuiFx.fade(color, intensity * fade * 0.7F));
        }
        
        // 十字光标
        g.fill(x - 8, (int)sy - 1, x - 3, (int)sy + 1, GuiFx.fade(color, intensity * 0.8F));
        g.fill(x + 3, (int)sy - 1, x + 8, (int)sy + 1, GuiFx.fade(color, intensity * 0.8F));
    }
    
    // =====================================================================
    // 物品展示特效
    // =====================================================================
    
    /** 物品背景光轮 - 旋转光环 */
    public static void itemHalo(GuiGraphics g, float cx, float cy, float radius,
                                int color, float intensity, long time, int segments) {
        if (intensity < 0.1F) return;
        
        float rotation = (time % 6000) / 6000.0F * Mth.TWO_PI;
        
        for (int i = 0; i < segments; i++) {
            float angle = rotation + i * Mth.TWO_PI / segments;
            float x = cx + Mth.cos(angle) * radius;
            float y = cy + Mth.sin(angle) * radius * 0.6F; // 椭圆
            
            float fade = 0.5F + (float)Math.sin(time * 0.003 + i) * 0.3F;
            GuiFx.glow(g, (int)x, (int)y, 
                (int)(radius * 0.15F), (int)(radius * 0.15F),
                color, intensity * fade);
        }
    }
    
    /** 物品能量脉冲 - 从中心向外的波纹 */
    public static void itemPulse(GuiGraphics g, float cx, float cy, float maxRadius,
                                 int color, float progress, float intensity) {
        if (intensity < 0.1F) return;
        
        float currentRadius = maxRadius * progress;
        float alpha = intensity * (1.0F - progress * 0.7F);
        
        // 多层波纹
        for (int layer = 0; layer < 3; layer++) {
            float layerProgress = CrateStage.clamp01(progress - layer * 0.15F);
            float layerRadius = maxRadius * layerProgress;
            float layerAlpha = alpha * (1.0F - layerProgress);
            
            if (layerRadius > 5.0F) {
                CrateArt.ellipse(g, cx, cy, layerRadius, layerRadius * 0.7F,
                    GuiFx.fade(color, layerAlpha * 0.5F));
            }
        }
    }
    
    /** 物品登场射线 - 四周发射 */
    public static void itemRays(GuiGraphics g, float cx, float cy, float length,
                                int color, float intensity, long time, int count) {
        if (intensity < 0.1F) return;
        
        float rotation = (time % 8000) / 8000.0F * Mth.TWO_PI;
        
        for (int i = 0; i < count; i++) {
            float angle = rotation + i * Mth.TWO_PI / count;
            float x1 = cx + Mth.cos(angle) * length;
            float y1 = cy + Mth.sin(angle) * length * 0.6F;
            
            drawRay(g, cx, cy, x1, y1, color, intensity);
        }
    }
    
    private static void drawRay(GuiGraphics g, float x0, float y0, float x1, float y1,
                                int color, float intensity) {
        int steps = 20;
        for (int i = 0; i < steps; i++) {
            float t = i / (float)steps;
            float x = x0 + (x1 - x0) * t;
            float y = y0 + (y1 - y0) * t;
            float fade = (1.0F - t) * intensity;
            float size = 3.0F * (1.0F - t * 0.5F);
            
            g.fill((int)(x - size), (int)(y - size/2),
                   (int)(x + size), (int)(y + size/2),
                   GuiFx.fade(color, fade * 0.6F));
        }
    }
    
    // =====================================================================
    // 环境特效
    // =====================================================================
    
    /** 全屏色差效果 - 高品质物品的视觉冲击 */
    public static void chromaticAberration(GuiGraphics g, int width, int height,
                                           float strength, int centerX, int centerY) {
        if (strength < 0.05F) return;
        
        // 边缘径向色差（用半透明色层模拟）
        int steps = 32;
        for (int i = 0; i < steps; i++) {
            float angle = i * Mth.TWO_PI / steps;
            int x = (int)(centerX + Mth.cos(angle) * width * 0.45F);
            int y = (int)(centerY + Mth.sin(angle) * height * 0.45F);
            
            int redShift = GuiFx.alpha(0xFFFF0000, (int)(strength * 15));
            int blueShift = GuiFx.alpha(0xFF0000FF, (int)(strength * 15));
            
            g.fill(x - 2, y - 2, x + 2, y + 2, redShift);
            g.fill(x - 3, y, x - 1, y + 1, blueShift);
        }
    }
    
    /** 镜头光晕 - 高光场景的镜头效果 */
    public static void lensFlare(GuiGraphics g, float cx, float cy, int color,
                                 float intensity, long time) {
        if (intensity < 0.1F) return;
        
        float pulse = 1.0F + (float)Math.sin(time * 0.004) * 0.2F;
        
        // 中心强光
        GuiFx.glow(g, (int)cx, (int)cy, 
            (int)(40 * pulse), (int)(40 * pulse),
            color, intensity);
        
        // 六边形光斑（模拟镜头光圈）
        drawHexFlare(g, cx, cy, 60 * pulse, color, intensity * 0.5F);
        
        // 二次反射光斑
        float offsetX = 50;
        GuiFx.glow(g, (int)(cx + offsetX), (int)cy,
            20, 20, color, intensity * 0.3F);
    }
    
    private static void drawHexFlare(GuiGraphics g, float cx, float cy, float radius,
                                     int color, float intensity) {
        float[] xs = new float[6];
        float[] ys = new float[6];
        
        for (int i = 0; i < 6; i++) {
            float angle = i * Mth.PI / 3.0F;
            xs[i] = cx + Mth.cos(angle) * radius;
            ys[i] = cy + Mth.sin(angle) * radius;
        }
        
        CrateArt.polygon(g, xs, ys, 6, GuiFx.fade(color, intensity * 0.3F));
    }
    
    /** 扫光特效 - 成功时的横扫高光 */
    public static void sweepLight(GuiGraphics g, int x0, int y0, int x1, int y1,
                                  int color, float progress, float intensity) {
        if (intensity < 0.1F) return;
        
        int width = x1 - x0;
        int sweepWidth = width / 4;
        int sweepX = x0 - sweepWidth + (int)((width + sweepWidth * 2) * progress);
        
        GuiFx.gradientFadeX(g, 
            Math.max(x0, sweepX - sweepWidth), y0,
            Math.min(x1, sweepX + sweepWidth), y1,
            GuiFx.fade(color, intensity * 0.7F),
            0.0F, 1.0F, 16);
    }
}
