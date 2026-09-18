package com.habitrain.lottery.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 「角色卡」系列界面的共享视觉规范。
 *
 * <p>色板、圆角半径、背景与面板外壳原本只存在于 {@link RoleSelectScreen}（自选卡页）；
 * 角色卡背包重做后需要与它完全同源，于是把可复用的部分提取到这里，避免两处调色板各自
 * 漂移。这里的常量与方法都是纯静态、无状态的，任意自绘 Screen 都能直接调用。</p>
 *
 * <p>修改配色时请同时确认两处页面：自选角色页与角色卡背包页共用本类，因此改一处即
 * 同时生效；若某个页面需要例外配色，请在该页面内部做局部混合，而不要新增第二套常量。</p>
 */
public final class CardUiStyle {

    private CardUiStyle() {
    }

    // ---------------------------------------------------------------------
    // 背景
    // ---------------------------------------------------------------------
    public static final int BG_TOP = 0xF00A0D16;
    public static final int BG_BOTTOM = 0xF004060B;

    // ---------------------------------------------------------------------
    // 面板
    // ---------------------------------------------------------------------
    public static final int PANEL_TOP = 0xF21A2130;
    public static final int PANEL_BOTTOM = 0xF20B0F18;
    public static final int PANEL_BORDER = 0x8A5A6E8C;

    // ---------------------------------------------------------------------
    // 文本与语义色
    // ---------------------------------------------------------------------
    public static final int GOLD = 0xFFFFD76A;
    public static final int GOLD_DEEP = 0xFFB8862B;
    public static final int CYAN = 0xFF6FD3E8;
    public static final int TEXT = 0xFFF4EFE2;
    public static final int MUTED = 0xFF97A0B4;
    public static final int DIM = 0xFF6A7288;
    public static final int DANGER = 0xFFE2555F;
    public static final int OK = 0xFF7CF5A0;

    // ---------------------------------------------------------------------
    // 尺寸
    // ---------------------------------------------------------------------
    public static final int CARD_RADIUS = 7;
    public static final int PANEL_RADIUS = 12;

    // ---------------------------------------------------------------------
    // 角色卡配色（与自选卡页阵营色一致）
    // ---------------------------------------------------------------------
    public static final int ACCENT_CIVILIAN = 0xFF44BB66;
    public static final int ACCENT_KILLER = 0xFFE2555F;
    public static final int ACCENT_NEUTRAL = 0xFFD9B23C;
    public static final int ACCENT_NEUTRAL_FOR_KILLER = 0xFFB07CE8;
    /** 自选卡：沿用面板主金色。 */
    public static final int ACCENT_SELF_SELECT = GOLD;
    /** 突破上限卡：偏暖的琥珀色，与金色区分但仍在同一色域内。 */
    public static final int ACCENT_LIMIT_BREAK = 0xFFFFB56B;

    // =====================================================================
    // 共享绘制
    // =====================================================================

    /**
     * 整屏背景：纵向渐变 + 面板上下两团呼吸柔光 + 缓慢漂移的星点 + 顶部渐隐遮罩。
     *
     * @param panelX 面板左边界（柔光定位用），无面板时传 {@code width / 2} 之类的中心值
     */
    public static void drawBackdrop(GuiGraphics graphics, long nowMillis, int width, int height,
                                    int panelX, int panelY, int panelW, int panelH) {
        graphics.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);

        float breathe = 0.55F + GuiFx.triWave(nowMillis, 5200.0F, 0.0F) * 0.45F;
        GuiFx.glow(graphics, panelX + panelW / 2, panelY + 12, panelW / 2 + 40, 90,
                CYAN, 0.16F * breathe);
        GuiFx.glow(graphics, panelX + panelW / 2, panelY + panelH - 10, panelW / 2 + 40, 90,
                GOLD_DEEP, 0.14F * breathe);

        for (int i = 0; i < 26; i++) {
            float speed = 0.006F + (i % 5) * 0.0022F;
            float x = ((nowMillis * speed) + i * 137.0F) % Math.max(1.0F, width + 40.0F) - 20.0F;
            float phase = (i * 0.37F) + nowMillis * 0.00035F;
            float y = (float) (height * (0.12F + 0.78F * ((Math.sin(phase) + 1.0F) * 0.5F)));
            int size = 1 + (i % 2);
            int alphaValue = 30 + (int) (Math.sin(phase * 3.0F) * 12.0F);
            graphics.fill((int) x, (int) y, (int) x + size, (int) y + size,
                    GuiFx.alpha(CYAN, alphaValue));
        }

        graphics.fillGradient(0, 0, width, 60, 0x66000000, 0x00000000);
    }

    /**
     * 面板外壳：外发光 + 圆角渐变 + 顶边金色引线 + 沿上缘巡航的高光 + 底边投影。
     * 入场时整块面板自下而上滑入 {@code lift} 像素。
     *
     * @param enter 0..1 的面板入场进度（见 {@link GuiFx#easeOutCubic}）
     */
    public static void drawPanelShell(GuiGraphics graphics, long nowMillis, float enter,
                                      int panelX, int panelY, int panelW, int panelH) {
        int lift = (int) ((1.0F - enter) * 14.0F);
        int x0 = panelX;
        int y0 = panelY + lift;
        int x1 = panelX + panelW;
        int y1 = panelY + panelH + lift;

        GuiFx.glow(graphics, panelX + panelW / 2, (y0 + y1) / 2, panelW / 2 + 6, panelH / 2 + 6,
                0xFF2A3A5A, 0.24F);

        GuiFx.roundGradient(graphics, x0, y0, x1, y1, PANEL_RADIUS, PANEL_TOP, PANEL_BOTTOM);
        GuiFx.gradient(graphics, x0 + 14, y0 + 1, x1 - 14, y0 + 2,
                GuiFx.alpha(GOLD, 70), GuiFx.alpha(GOLD, 0));
        GuiFx.roundOutline(graphics, x0, y0, x1, y1, PANEL_RADIUS,
                GuiFx.mix(PANEL_BORDER, GOLD_DEEP, 0.35F));

        float cycle = nowMillis % 4200.0F;
        GuiFx.beginClip(graphics, x0, y0, x1, y1);
        GuiFx.sheen(graphics, x0 + 10, y0 + 1, x1 - 10, y0 + 13, cycle / 4200.0F,
                0.9F * GuiFx.pulse(cycle / 4200.0F * 1.5F));
        GuiFx.endClip(graphics);

        graphics.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, GuiFx.alpha(0xFF000000, 90));
    }

    // =====================================================================
    // 资源
    // =====================================================================

    /**
     * 资源管理器里是否真的能读到该贴图。
     *
     * <p>自绘界面里直接 {@code blit} 一张不存在的贴图会渲染成紫黑占位方块，比不画还糟；
     * 因此所有可选立绘在使用前都必须过一遍这个检查，缺失时退化成程序化徽记。</p>
     */
    public static boolean textureExists(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        try {
            return client.getResourceManager().getResource(id).isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
