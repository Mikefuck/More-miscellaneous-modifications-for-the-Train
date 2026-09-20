package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.LotteryNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 职业卡使用菜单：在背包点击职业卡后显示直接使用/自选角色两个入口。
 *
 * <p>这个页面刻意使用了和上游职业选择页相同的深色渐变、金色描边和卡牌式
 * 操作区域。选项本身是自绘控件，因此悬停效果不会被原版按钮背景盖住。</p>
 *
 * <p>职业卡页面不暂停游戏。游戏一旦离开大厅，本页会立即返回父页面，并且不会
 * 发送任何确认包；服务端也会再次检查对局状态。</p>
 */
public class CardUseMenuScreen extends Screen {
    private static final int BG_TOP = 0xF0181420;
    private static final int BG_BOTTOM = 0xF0060B12;
    private static final int PANEL_TOP = 0xF01B1524;
    private static final int PANEL_BOTTOM = 0xF00B111B;
    private static final int BORDER = 0xFF8B6914;
    private static final int GOLD = 0xFFFFD76A;
    private static final int TEXT = 0xFFFFF4DC;
    private static final int MUTED = 0xFFB9A98C;
    private static final int ACCENT_DIRECT = 0xFFE06B65;
    private static final int ACCENT_SELF = 0xFFB18AE6;

    private static final String[] FACTION_LABELS = {
            "killer", "civilian", "neutral", "neutral_for_killer"
    };

    /** 本页所有文案统一走 lang；卡名与背包 / 配置端共用 {@code config.cards.*} 键。 */
    private static final String LANG_PREFIX = "screen.habitrain_lottery.";
    private static final String CARD_USE_PREFIX = LANG_PREFIX + "card_use.";
    private static final String CARD_NAME_PREFIX = LANG_PREFIX + "config.cards.";

    /** questKey 白名单：只允许小写字母与下划线，避免服务端可控字符串拼出越界路径。 */
    private static final Pattern SAFE_CARD_ID = Pattern.compile("[a-z_]{1,32}");

    private static final int ART_TEXTURE_SIZE = 128;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int tickCounter;

    /**
     * 上一帧到这一帧的毫秒数。悬停插值必须用它而不是固定系数，否则动画速度会随帧率变化
     * （与角色卡背包 / 自选角色页保持一致）。
     */
    private float frameDelta = 16.0F;
    private long lastFrameMillis = System.currentTimeMillis();

    private OptionCardWidget directOption;
    private boolean closedForGameStart;
    private boolean selectionSubmitted;

    public CardUseMenuScreen(Screen parent) {
        super(Component.translatable(CARD_USE_PREFIX + "title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        tickCounter = 0;
        computeLayout();
        addWidgets();

        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
        }
    }

    private void computeLayout() {
        int availableW = Math.max(240, width - 20);
        int availableH = Math.max(200, height - 20);
        panelW = Math.min(560, Math.max(320, availableW));
        panelH = Math.min(320, Math.max(220, availableH));
        panelW = Math.min(panelW, width);
        panelH = Math.min(panelH, height);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    private void addWidgets() {
        int optionX = panelX + 18;
        int optionW = panelW - 36;
        int optionH = panelH >= 280 ? 58 : 48;
        int firstY = panelY + (panelH >= 280 ? 96 : 82);

        directOption = new OptionCardWidget(
                optionX,
                firstY,
                optionW,
                optionH,
                ACCENT_DIRECT,
                Component.translatable(CARD_USE_PREFIX + "direct"),
                Component.translatable(CARD_USE_PREFIX + "direct_hint"),
                Component.translatable(CARD_USE_PREFIX + "cost_one"),
                false);
        addRenderableWidget(directOption);
        // Faction cards only activate their own faction. Exact-role selection has its own backpack entry.
        directOption.active = LotteryNetwork.ClientLotteryState.cardBalances.getOrDefault(questKey(), 0) > 0
                && LotteryNetwork.ClientLotteryState.cardUseRemainingUses > 0;

        addRenderableWidget(Button.builder(Component.translatable(CARD_USE_PREFIX + "back"),
                        button -> onClose())
                .bounds(panelX + panelW / 2 - 52, panelY + panelH - 27, 104, 20)
                .build());
    }

    private String questKey() {
        String key = LotteryNetwork.ClientLotteryState.cardUseMenuQuestKey;
        return key == null ? "" : key;
    }

    /** 卡名与背包 / 配置端共用 {@code config.cards.*}，同一张卡不会出现两个名字。 */
    private Component factionName() {
        String key = questKey();
        for (String label : FACTION_LABELS) {
            if (label.equalsIgnoreCase(key)) {
                return Component.translatable(CARD_NAME_PREFIX + label);
            }
        }
        return Component.translatable(CARD_USE_PREFIX + "fallback_card");
    }

    /**
     * 当前卡牌的立绘贴图，与角色卡背包 / 自选角色页共用同一套资源。
     *
     * <p>{@code questKey} 来自服务端下发的 {@code CardUseMenuS2C}，属于外部输入，因此先用
     * 白名单正则约束成 {@code [a-z_]} 再拼路径；资源不存在时返回 {@code null}，由调用方
     * 回退到程序化图标，绝不渲染紫黑占位图。</p>
     */
    private static ResourceLocation cardArt(String questKey) {
        if (questKey == null || questKey.isBlank()) {
            return null;
        }
        String id = questKey.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_CARD_ID.matcher(id).matches()) {
            return null;
        }
        try {
            ResourceLocation art = ResourceLocation.fromNamespaceAndPath(
                    "habitrain_lottery", "textures/gui/cards/" + id + ".png");
            return CardUiStyle.textureExists(art) ? art : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean canSubmit() {
        if (closedForGameStart || selectionSubmitted) {
            return false;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return false;
        }
        return !questKey().isBlank();
    }

    private void useDirect() {
        if (!canSubmit()) {
            return;
        }
        selectionSubmitted = true;
        try {
            LotteryClientNetwork.clientCardUseConfirm(questKey(), "direct", "");
        } finally {
            // 确认按钮只允许触发一次；无论网络层是否立即返回，都先离开
            // 卡牌页面，避免玩家重复点击或卡在已经完成的页面。
            closeToParent();
        }
    }

    @Override
    public void tick() {
        if (CardGuiGameState.gameActiveOrStarting() && !closedForGameStart) {
            closeForGameStart();
            return;
        }
        tickCounter++;
        super.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (closedForGameStart) {
            return;
        }
        long now = System.currentTimeMillis();
        frameDelta = Mth.clamp(now - lastFrameMillis, 0.0F, 120.0F);
        lastFrameMillis = now;
        renderBackground(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics);
        // 1.21 的 Screen#render 内部必定再调一次 renderBackground（走的是本类的覆写）。
        // 第二次背景 alpha 高达 240/255，会把刚画好的面板底、金边、标题与页脚按
        // 0.059 的权重压掉，几乎全部不可见；这里用守卫屏蔽嵌套的那一次。
        suppressNestedBackground = true;
        try {
            super.render(graphics, mouseX, mouseY, partialTick);
        } finally {
            suppressNestedBackground = false;
        }
    }

    private void drawPanel(GuiGraphics graphics) {
        graphics.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH,
                PANEL_TOP, PANEL_BOTTOM);
        graphics.renderOutline(panelX, panelY, panelW, panelH, BORDER);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 3, 0x44FFE8C0);

        int iconX = panelX + 18;
        int iconY = panelY + 15;
        graphics.fill(iconX, iconY, iconX + 26, iconY + 26, 0xAA291F32);
        graphics.renderOutline(iconX, iconY, 26, 26, 0xCCB18AE6);
        graphics.fill(iconX + 6, iconY + 6, iconX + 20, iconY + 20, ACCENT_SELF);
        graphics.fill(iconX + 9, iconY + 3, iconX + 17, iconY + 23, ACCENT_SELF);

        graphics.drawString(font, Component.translatable(CARD_USE_PREFIX + "title"),
                panelX + 54, panelY + 13, TEXT, false);
        graphics.drawString(font,
                Component.translatable(CARD_USE_PREFIX + "subtitle", factionName(),
                        Math.max(0, LotteryNetwork.ClientLotteryState.cardUseRemainingUses)),
                panelX + 54, panelY + 29, MUTED, false);
        graphics.fill(panelX + 18, panelY + 52, panelX + panelW - 18, panelY + 53, 0x448B6914);

        graphics.drawString(font, Component.translatable(CARD_USE_PREFIX + "section"),
                panelX + 18, panelY + 65, GOLD, false);
        Component effectHint = Component.translatable(CARD_USE_PREFIX + "effect_hint");
        graphics.drawString(font, effectHint,
                panelX + panelW - 18 - font.width(effectHint),
                panelY + 65, MUTED, false);

        graphics.fill(panelX + 18, panelY + panelH - 40, panelX + panelW - 18,
                panelY + panelH - 39, 0x332E5A66);
        graphics.drawCenteredString(font, Component.translatable(CARD_USE_PREFIX + "keys"),
                panelX + panelW / 2, panelY + panelH - 37, MUTED);
    }

    /** 嵌套调用守卫：见 {@link #render}。 */
    private boolean suppressNestedBackground;

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (suppressNestedBackground) {
            return;
        }
        graphics.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
        graphics.fillGradient(0, 0, width, 58, 0xAA000000, 0x00000000);

        long time = minecraft != null && minecraft.level != null
                ? minecraft.level.getGameTime() : tickCounter;
        for (int i = 0; i < 10; i++) {
            int x = (int) ((time * (i + 1L) + i * 97L) % Math.max(1, width));
            int y = 70 + (i * 37) % Math.max(1, height - 70);
            graphics.fill(x, y, x + 2, y + 2, 0x223D8BA3);
        }
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    /** 开局时从嵌套的角色页直接回到背包，不经过中间菜单。 */
    void closeForGameStart() {
        if (closedForGameStart) {
            return;
        }
        closedForGameStart = true;
        selectionSubmitted = false;
        // 角色选择页会把本菜单作为 parent；此时本菜单不是当前 screen，
        // 所以不能用“仅当当前窗口是自己才切换”的普通返回逻辑。
        Minecraft.getInstance().setScreen(parent);
    }

    /** 自选角色确认后关闭整条职业卡页面链，直接回到打开卡牌前的页面。 */
    void closeAfterSelection() {
        selectionSubmitted = true;
        Minecraft.getInstance().setScreen(parent);
    }

    private void closeToParent() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen != parent) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class OptionCardWidget extends AbstractWidget {
        private final int accent;
        private final Component heading;
        private final Component description;
        private final Component cost;
        private final boolean selfSelect;
        private float hoverAnimation;

        private OptionCardWidget(int x, int y, int width, int height, int accent,
                                 Component heading, Component description, Component cost,
                                 boolean selfSelect) {
            super(x, y, width, height, heading);
            this.accent = accent;
            this.heading = heading;
            this.description = description;
            this.cost = cost;
            this.selfSelect = selfSelect;
            setTooltip(Tooltip.create(Component.empty()
                    .append(heading).append("\n").append(description)));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = active && isMouseOver(mouseX, mouseY);
            // 约 47ms 半衰期，与原固定系数 0.22 在 60fps 下的手感一致，但不受帧率影响。
            hoverAnimation = GuiFx.approach(hoverAnimation, hovered ? 1.0F : 0.0F, frameDelta, 47.0F);

            int x = getX();
            int y = getY();
            int w = width;
            int h = height;
            int top = blend(0xFF211A2A, accent, hoverAnimation * 0.30F);
            int bottom = blend(0xFF100E18, accent, hoverAnimation * 0.16F);

            if (hoverAnimation > 0.02F) {
                int glow = ((int) (hoverAnimation * 70) << 24) | (accent & 0xFFFFFF);
                graphics.renderOutline(x - 1, y - 1, w + 2, h + 2, glow);
            }
            graphics.fillGradient(x, y, x + w, y + h, top, bottom);
            graphics.renderOutline(x, y, w, h,
                    hovered ? GOLD : blend(0xFF5A4530, accent, 0.45F));
            graphics.fill(x + 1, y + 1, x + 5, y + h - 1, accent);

            int iconSize = Math.min(34, h - 14);
            int iconX = x + 12;
            int iconY = y + (h - iconSize) / 2;
            ResourceLocation art = cardArt(questKey());
            if (art != null) {
                // 与背包 / 自选角色页同一张立绘：柔光 + 立绘 + 悬停金边
                GuiFx.glow(graphics, iconX + iconSize / 2, iconY + iconSize / 2,
                        iconSize / 2 + 5, iconSize / 2 + 5, accent,
                        0.22F + hoverAnimation * 0.35F);
                graphics.blit(art, iconX, iconY, iconSize, iconSize, 0.0F, 0.0F,
                        ART_TEXTURE_SIZE, ART_TEXTURE_SIZE, ART_TEXTURE_SIZE, ART_TEXTURE_SIZE);
                GuiFx.roundOutline(graphics, iconX - 1, iconY - 1, iconX + iconSize + 1,
                        iconY + iconSize + 1, 6,
                        GuiFx.fade(hovered ? GOLD : 0xCCFFF4DC, 0.5F + hoverAnimation * 0.5F));
            } else {
                graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize,
                        withAlpha(accent, hovered ? 0xD0 : 0xA0));
                graphics.renderOutline(iconX, iconY, iconSize, iconSize, 0xCCFFF4DC);
                String icon = selfSelect ? "✦" : "↻";
                graphics.drawCenteredString(font, Component.literal(icon),
                        iconX + iconSize / 2, iconY + iconSize / 2 - 4, 0xFFFFFFFF);
            }

            int textX = iconX + iconSize + 12;
            graphics.drawString(font, heading, textX, y + 9, TEXT, false);
            graphics.drawString(font, description, textX, y + 25, MUTED, false);

            int chipW = font.width(cost) + 14;
            int chipX = x + w - chipW - 24;
            int chipY = y + (h - 18) / 2;
            graphics.fill(chipX, chipY, chipX + chipW, chipY + 18,
                    withAlpha(accent, hovered ? 0xAA : 0x66));
            graphics.renderOutline(chipX, chipY, chipW, 18, 0x669F8AAB);
            graphics.drawCenteredString(font, cost, chipX + chipW / 2,
                    chipY + 5, TEXT);
            graphics.drawString(font, Component.literal("›"), x + w - 15, y + h / 2 - 5,
                    hovered ? GOLD : MUTED, false);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            useDirect();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (active && isFocused() && (keyCode == 257 || keyCode == 335 || keyCode == 32)) {
                useDirect();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT, description);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private static int blend(int first, int second, float amount) {
        float t = Mth.clamp(amount, 0.0F, 1.0F);
        int r1 = first >> 16 & 0xFF;
        int g1 = first >> 8 & 0xFF;
        int b1 = first & 0xFF;
        int r2 = second >> 16 & 0xFF;
        int g2 = second >> 8 & 0xFF;
        int b2 = second & 0xFF;
        return 0xFF000000
                | ((int) (r1 + (r2 - r1) * t) << 16)
                | ((int) (g1 + (g2 - g1) * t) << 8)
                | (int) (b1 + (b2 - b1) * t);
    }
}
