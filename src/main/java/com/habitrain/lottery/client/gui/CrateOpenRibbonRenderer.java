package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.crate.CrateService;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开箱界面 1920×1080 参考画布排版、奖池橱窗、转盘丝带、结果面板与跳过提示渲染器（从 {@link CrateOpenScreen} 拆出）。
 */
final class CrateOpenRibbonRenderer {
    static final String KEY = "screen.habitrain_lottery.crate.";
    private static final ResourceLocation GREEN_APPLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/green_apple.png");
    private static final Map<String, ResourceLocation> CARD_TEXTURE_CACHE = new ConcurrentHashMap<>();
    static final float REF_W = 1920.0F, REF_H = 1080.0F;

    static final float STRIP_X0 = 150, STRIP_X1 = 1770, STRIP_Y0 = 778, STRIP_Y1 = 996;
    static final float STRIP_CARD_Y = 822, STRIP_CARD_W = 170, STRIP_CARD_H = 164, STRIP_GAP = 14;
    static final int STRIP_SLOTS = 8;

    static final float REEL_CY = 520, REEL_CARD_W = 300, REEL_CARD_H = 236, REEL_PITCH = 318,
            BAND_Y0 = 384, BAND_Y1 = 656;
    static final float Z_REEL = 100, Z_REEL_LIGHT = 105, Z_REEL_OVER = 300;

    static final float PANEL_X0 = 460, PANEL_X1 = 1460, PANEL_Y0 = 796, PANEL_Y1 = 992;
    static final float MINI_W = 100, MINI_H = 118, MINI_GAP = 12, MINI_Y = 836;
    static final int MINI_SLOTS = 8;

    static final int TEXT_BRIGHT = 0xFFF2F2F2, TEXT_BODY = 0xFFD2D2D2, TEXT_DIM = 0xFFA9B3B1,
            TEXT_GOLD = 0xFFF0D36E;

    record SummaryEntry(CrateService.Reward reward, CrateCatalog.RewardPreview preview,
                        ItemStack stack, SkinQuality quality) {
    }

    private CrateOpenRibbonRenderer() {
    }

    static void layer(GuiGraphics g, float z, Runnable body) {
        g.flush();
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, z);
            body.run();
            g.flush();
        } finally {
            g.pose().popPose();
        }
    }

    static int rarityColor(SkinQuality quality) {
        return quality.color() | 0xFF000000;
    }

    // =====================================================================
    // 奖励图标与预览栈
    // =====================================================================

    static ItemStack previewReward(String type, String id) {
        if ("green_apples".equals(type)) {
            ItemStack stack = new ItemStack(Items.APPLE);
            stack.set(DataComponents.CUSTOM_NAME,
                    Component.translatable("screen.habitrain_lottery.warehouse.green_apples"));
            return stack;
        }
        if ("card".equals(type)) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.CUSTOM_NAME,
                    Component.translatable("screen.habitrain_lottery.config.cards." + id));
            return stack;
        }
        return preview(type, id);
    }

    static ItemStack preview(String type, String id) {
        ItemStack stack = SkinItems.preview(type + "/" + id);
        if (!stack.isEmpty()) {
            stack.set(DataComponents.CUSTOM_NAME, SkinWardrobeScreen.skinName(type, id));
        }
        return stack.isEmpty() ? new ItemStack(Items.NETHER_STAR) : stack;
    }

    static ItemStack previewCandidate(CrateCatalog.RewardPreview reward) {
        ItemStack stack;
        if ("skin".equals(reward.kind())) {
            String[] parts = reward.id().split("/", 2);
            stack = parts.length == 2 ? preview(parts[0], parts[1]) : new ItemStack(Items.BARRIER);
        } else {
            stack = previewReward(reward.kind(), reward.id());
        }
        if (reward.amount() > 1) {
            stack.set(DataComponents.CUSTOM_NAME,
                    Component.literal(stack.getHoverName().getString() + " ×" + reward.amount()));
        }
        return stack;
    }

    static void drawReward(GuiGraphics g, CrateCatalog.RewardPreview reward, ItemStack stack,
                           float x, float y, float scale, float yaw, float roll, float tint, float alpha) {
        if (reward == null || "skin".equals(reward.kind())) {
            CrateArt.item(g, stack, x, y, scale, yaw, roll, tint, alpha);
            return;
        }
        ResourceLocation texture = "green_apples".equals(reward.kind())
                ? GREEN_APPLE_TEXTURE
                : cardTexture(reward.id());
        g.pose().pushPose();
        try {
            g.pose().translate(x, y, 150);
            g.pose().mulPose(Axis.ZP.rotationDegrees(roll));
            g.pose().scale(scale, scale, 1);
            g.setColor(tint, tint, tint, alpha);
            try {
                g.blit(texture, -8, -8, 0, 0, 16, 16, 16, 16);
            } finally {
                g.setColor(1, 1, 1, 1);
            }
        } finally {
            g.pose().popPose();
        }
    }

    private static ResourceLocation cardTexture(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return GREEN_APPLE_TEXTURE;
        }
        return CARD_TEXTURE_CACHE.computeIfAbsent(cardId, id -> {
            ResourceLocation loc = ResourceLocation.tryBuild("habitrain_lottery", "textures/gui/cards/" + id + ".png");
            return loc != null ? loc : GREEN_APPLE_TEXTURE;
        });
    }

    // =====================================================================
    // 文字工具（在参考空间里按像素字号绘制）
    // =====================================================================

    static float textScale(Font font, float size) {
        return size / font.lineHeight;
    }

    static void textAt(GuiGraphics g, Font font, Component text, float x, float y, float size, int color, boolean shadow) {
        if ((color >>> 24) < 5) return;
        float scale = textScale(font, size);
        g.pose().pushPose();
        try {
            g.pose().translate(x, y, 0.0F);
            g.pose().scale(scale, scale, 1.0F);
            g.drawString(font, text, 0, 0, color, shadow);
        } finally {
            g.pose().popPose();
        }
    }

    static void textClipped(GuiGraphics g, Font font, Component text, float x, float y, float size,
                            float maxWidth, int color, boolean shadow) {
        float scale = textScale(font, size);
        int allowed = Math.max(1, (int) (maxWidth / scale));
        String raw = text.getString();
        String shown = font.width(raw) <= allowed ? raw
                : font.plainSubstrByWidth(raw, Math.max(1, allowed - font.width("…"))) + "…";
        textAt(g, font, Component.literal(shown), x, y, size, color, shadow);
    }

    static float textWidth(Font font, Component text, float size) {
        return font.width(text) * textScale(font, size);
    }

    static void textCentered(GuiGraphics g, Font font, Component text, float centerX, float y, float size, int color, boolean shadow) {
        textAt(g, font, text, centerX - textWidth(font, text, size) * 0.5F, y, size, color, shadow);
    }

    static void textLeft(GuiGraphics g, Font font, Component text, float x, float y, float size, int color, boolean shadow) {
        textAt(g, font, text, x, y, size, color, shadow);
    }

    static void textRight(GuiGraphics g, Font font, Component text, float right, float y, float size, int color, boolean shadow) {
        textAt(g, font, text, right - textWidth(font, text, size), y, size, color, shadow);
    }

    /** 带字距的居中文字（眉题）。 */
    static void textSpaced(GuiGraphics g, Font font, Component text, float centerX, float y, float size,
                           float spacing, int color, boolean shadow) {
        if ((color >>> 24) < 5) return;
        String raw = text.getString();
        float scale = textScale(font, size);
        float width = (font.width(raw) + spacing * Math.max(0, raw.length() - 1)) * scale;
        g.pose().pushPose();
        try {
            g.pose().translate(centerX - width * 0.5F, y, 0.0F);
            g.pose().scale(scale, scale, 1.0F);
            for (int i = 0; i < raw.length(); i++) {
                String ch = raw.substring(i, i + 1);
                g.drawString(font, ch, 0, 0, color, shadow);
                g.pose().translate(font.width(ch) + spacing, 0.0F, 0.0F);
            }
        } finally {
            g.pose().popPose();
        }
    }

    // =====================================================================
    // 奖励橱窗
    // =====================================================================

    static float stripLeft(int count) {
        return (REF_W - (count * STRIP_CARD_W + Math.max(0, count - 1) * STRIP_GAP)) / 2;
    }

    static void renderStrip(GuiGraphics g, Font font, long time, long enteredAt, CrateStage stage, boolean failure,
                            float alpha, float frameDelta, List<ItemStack> strip, List<SkinQuality> stripQuality,
                            List<CrateCatalog.RewardPreview> candidates, int stripPage, int hovered,
                            Map<Integer, Float> stripHover) {
        if (alpha <= .02F) return;
        float rise = CrateStage.stripOffset(time, enteredAt) + 30 * (failure ? 0 : stage.dismiss(time));
        CrateArt.stripPanel(g, (int) STRIP_X0, (int) (STRIP_Y0 + rise), (int) STRIP_X1, (int) (STRIP_Y1 + rise), alpha);
        float headY = STRIP_Y0 + 12 + rise;
        textSpaced(g, font, Component.translatable(KEY + "strip.header"), REF_W / 2, headY, 20, 1.2F,
                GuiFx.fade(TEXT_BRIGHT, alpha), true);
        CrateFx.hairline(g, REF_W / 2 - 330, REF_W / 2 - 150, headY + 10, 1, 60, GuiFx.fade(CrateArt.GOLD_LINE, 0.7F * alpha), false);
        CrateFx.hairline(g, REF_W / 2 + 150, REF_W / 2 + 330, headY + 10, 1, 60, GuiFx.fade(CrateArt.GOLD_LINE, 0.7F * alpha), false);
        CrateFx.diamond(g, REF_W / 2 - 140, headY + 10.5F, 4, 4, GuiFx.fade(CrateArt.GOLD_LINE, alpha), false);
        CrateFx.diamond(g, REF_W / 2 + 140, headY + 10.5F, 4, 4, GuiFx.fade(CrateArt.GOLD_LINE, alpha), false);
        int pages = Math.max(1, (strip.size() + STRIP_SLOTS - 1) / STRIP_SLOTS);
        textRight(g, font, Component.translatable(KEY + "strip.page", strip.size(), stripPage + 1, pages),
                STRIP_X1 - 24, headY + 2, 16, GuiFx.fade(TEXT_DIM, alpha), false);
        renderRarityLegend(g, font, STRIP_X0 + 24, headY + 4, alpha, stripQuality);
        if (strip.isEmpty()) {
            textCentered(g, font, Component.translatable(KEY + "strip.empty"), REF_W / 2, 890 + rise,
                    22, GuiFx.fade(TEXT_DIM, alpha), true);
            return;
        }
        int count = Math.max(0, Math.min(STRIP_SLOTS, strip.size() - stripPage * STRIP_SLOTS));
        float left = stripLeft(count);
        for (int i = 0; i < count; i++) {
            int index = stripPage * STRIP_SLOTS + i;
            if (index >= candidates.size() || index >= stripQuality.size()) break;
            float enter = CrateStage.easeOutCubic(CrateStage.progress(time,
                    enteredAt + CrateStage.STRIP_DELAY_MS + 60L * i, 420));
            float a = alpha * enter;
            if (a <= 0.02F) continue;
            float target = index == hovered ? 1 : 0;
            float h = GuiFx.approach(stripHover.getOrDefault(index, 0F), target, frameDelta, 60);
            stripHover.put(index, h);
            float x = left + i * (STRIP_CARD_W + STRIP_GAP);
            float y = STRIP_CARD_Y + rise + 24 * (1 - enter) - 8 * h;
            int rarity = rarityColor(stripQuality.get(index));
            CrateArt.rarityCard(g, x, y, STRIP_CARD_W, STRIP_CARD_H, rarity, 1, 0.15F + 0.85F * h, a);
            if (h > 0.02F) {
                CrateArt.sheen(g, x, y, STRIP_CARD_W, STRIP_CARD_H, ((time % 1400L) / 1400.0F), h * a);
            }
            var reward = candidates.get(index);
            drawReward(g, reward, strip.get(index), x + STRIP_CARD_W / 2, y + 56, 5.2F + 0.5F * h, 0, 0, 1, a);
            textClipped(g, font, strip.get(index).getHoverName(), x + 10, y + 110, 18, STRIP_CARD_W - 20,
                    GuiFx.fade(TEXT_BRIGHT, a), true);
            Component detail = "skin".equals(reward.kind())
                    ? Component.translatable(stripQuality.get(index).translationKey())
                    : Component.translatable(KEY + "strip." + reward.kind());
            textClipped(g, font, detail, x + 10, y + 134, 15, STRIP_CARD_W - 20,
                    GuiFx.fade(GuiFx.mix(TEXT_DIM, rarity, 0.55F), a), false);
        }
    }

    private static void renderRarityLegend(GuiGraphics g, Font font, float x, float y, float alpha,
                                           List<SkinQuality> stripQuality) {
        int[] counts = new int[SkinQuality.values().length];
        for (SkinQuality quality : stripQuality) counts[quality.ordinal()]++;
        for (SkinQuality quality : SkinQuality.values()) {
            if (counts[quality.ordinal()] == 0) continue;
            int color = rarityColor(quality);
            CrateFx.diamond(g, x + 5, y + 7, 5, 5, GuiFx.fade(color, alpha), false);
            CrateFx.glow(g, x + 5, y + 7, 12, color, 0.35F * alpha);
            Component label = Component.literal(Component.translatable(quality.translationKey()).getString()
                    + " " + counts[quality.ordinal()]);
            textLeft(g, font, label, x + 16, y, 15, GuiFx.fade(TEXT_BODY, alpha), false);
            x += 30 + textWidth(font, label, 15);
        }
    }

    // =====================================================================
    // 卡片转盘
    // =====================================================================

    static void renderReel(GuiGraphics g, long time, CrateStage stage, float canvasX, float canvasY, float unit,
                           List<ItemStack> reel, List<SkinQuality> reelQuality,
                           List<CrateCatalog.RewardPreview> reelRewards, int resultSlot,
                           CrateCatalog.RewardPreview resultPreview, SkinQuality resultQuality,
                           float tier, int light) {
        float entry = stage.wipe(time);
        if (entry <= 0.02F) return;
        float cx = REF_W * .5F;
        float rawSpeed = Math.max(0, stage.reelPosition(time) - stage.reelPosition(time - 16)) / 16.0F;
        float speed = CrateStage.clamp01(rawSpeed / CrateStage.REEL_SPEED);
        boolean stopped = stage.hasResult() && time >= stage.stopAt();
        float flare = stopped ? CrateStage.progress(time, stage.stopAt(), CrateStage.STOP_HOLD_MS) : 0;
        int winnerColor = rarityColor(resultQuality);

        g.flush();
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, Z_REEL);
            float half = REF_W * .5F * entry;
            CrateFx.hGradient(g, cx - half, BAND_Y0, cx - half + 420, BAND_Y1, 0x000A0C0D, 0xE00A0C0D, false);
            g.fill((int) (cx - half + 420), (int) BAND_Y0, (int) (cx + half - 420), (int) BAND_Y1, 0xE00A0C0D);
            CrateFx.hGradient(g, cx + half - 420, BAND_Y0, cx + half, BAND_Y1, 0xE00A0C0D, 0x000A0C0D, false);
            CrateFx.vGradient(g, cx - half, BAND_Y0, cx + half, BAND_Y0 + 60, GuiFx.fade(light, 0.10F), light & 0xFFFFFF, true);
            for (float railY : new float[]{BAND_Y0, BAND_Y1 - 2}) {
                CrateFx.hairline(g, cx - half, cx + half, railY, 2, 520, GuiFx.fade(light, 0.85F), false);
                CrateFx.hairline(g, cx - half, cx + half, railY - 5, 12, 520, GuiFx.fade(light, 0.28F), true);
            }
            if (speed > 0.05F) {
                for (int i = 0; i < 16; i++) {
                    float ry = BAND_Y0 + 14 + ((i * 97) % 23) / 23.0F * (BAND_Y1 - BAND_Y0 - 28);
                    float len = 120 + (i * 53) % 220;
                    float travel = REF_W + len * 2;
                    float x = REF_W + len - ((time * (1.4F + (i % 5) * 0.3F) * speed + i * 211) % travel);
                    CrateFx.hairline(g, x, x + len, ry, 1.5F, len * 0.45F, GuiFx.fade(0xFFFFFFFF, 0.22F * speed), true);
                }
            }

            g.enableScissor((int) (canvasX + (cx - half) * unit), (int) (canvasY + (BAND_Y0 + 2) * unit),
                    (int) (canvasX + (cx + half) * unit), (int) (canvasY + (BAND_Y1 - 2) * unit));
            try {
                for (int index = CrateStage.SLOTS - 1; index >= 0; index--) {
                    ItemStack stack = index < 0 || index >= reel.size() ? ItemStack.EMPTY : reel.get(index);
                    if (stack.isEmpty()) continue;
                    float offset = stage.reelOffset(time, index);
                    float distance = Math.abs(offset);
                    if (distance > CrateStage.VISIBLE_SPAN + 0.7F) continue;
                    boolean winner = index == resultSlot && stage.hasResult();
                    float scale = CrateStage.cardScale(offset);
                    if (winner && stopped) {
                        scale *= 1 + 0.06F * (float) Math.sin(Math.min(1, flare * 1.6F) * Math.PI) + 0.03F * flare;
                    }
                    float tint = CrateStage.cardTint(offset);
                    if (stopped && !winner) {
                        tint *= 1 - 0.45F * CrateStage.easeOutCubic(Math.min(1, flare * 3));
                    }
                    float w = REEL_CARD_W * scale, h = REEL_CARD_H * scale;
                    float x = cx + offset * REEL_PITCH - w * .5F, y = REEL_CY - h * .5F;
                    SkinQuality q = index < 0 || index >= reelQuality.size() ? SkinQuality.WHITE : reelQuality.get(index);
                    int rarity = rarityColor(q);
                    float near = CrateStage.clamp01(1 - distance / 1.2F);
                    float glow = 0.12F + 0.4F * near + (winner && stopped ? 0.9F * CrateStage.easeOutCubic(Math.min(1, flare * 2.5F)) : 0);
                    CrateArt.rarityCard(g, x, y, w, h, rarity, tint, Math.min(1, glow), entry);
                    if (winner && stopped && flare < 0.8F) {
                        CrateArt.sheen(g, x, y, w, h, flare / 0.8F, 1);
                    }
                    CrateCatalog.RewardPreview preview = index == resultSlot ? resultPreview
                            : (index < reelRewards.size() ? reelRewards.get(index) : null);
                    float itemScale = Math.min(w, h) * 0.60F / 16.0F;
                    if (speed > 0.12F) {
                        for (int k = 3; k >= 1; k--) {
                            float ghost = k * 26 * speed;
                            drawReward(g, preview, stack, x + w * .5F + ghost, y + h * .46F, itemScale, 0, 0,
                                    tint * 0.7F, entry * (0.28F - k * 0.06F) * speed);
                        }
                    }
                    drawReward(g, preview, stack, x + w * .5F, y + h * .46F, itemScale, 0, 0, tint, entry);
                }
                g.flush();
            } finally {
                g.disableScissor();
            }
        } finally {
            g.pose().popPose();
        }

        layer(g, Z_REEL_LIGHT, () -> {
            CrateFx.beam(g, cx, BAND_Y0, BAND_Y1 + 60, 180, 260, light, 0.16F * entry);
            if (stopped) {
                CrateFx.glow(g, cx, REEL_CY, 360, 190, winnerColor, 0.30F * CrateStage.easeOutCubic(flare), true);
            }
        });

        layer(g, Z_REEL_OVER, () -> {
            CrateFx.hGradient(g, 0, BAND_Y0 - 30, 560, BAND_Y1 + 30, 0xF0020303, 0x00020303, false);
            CrateFx.hGradient(g, REF_W - 560, BAND_Y0 - 30, REF_W, BAND_Y1 + 30, 0x00020303, 0xF0020303, false);
            renderNeedle(g, time, stage, entry, speed, stopped, flare, winnerColor);
            if (stopped) renderWinnerFlare(g, time, flare, winnerColor, tier);
        });
    }

    private static void renderNeedle(GuiGraphics g, long time, CrateStage stage, float entry, float speed,
                                     boolean stopped, float flare, int winner) {
        float alpha = CrateStage.clamp01((entry - 0.3F) / 0.7F);
        if (alpha <= 0.01F) return;
        float cx = REF_W * .5F;
        float frac = stage.reelPosition(time) % 1.0F;
        float pass = (float) Math.pow(Math.max(0, 1 - Math.min(frac, 1 - frac) * 5), 2) * (0.4F + 0.6F * speed);
        int color = stopped ? GuiFx.mix(CrateArt.GOLD_LINE, winner, 0.6F * flare) : CrateArt.GOLD_LINE;
        float y0 = BAND_Y0 - 14, y1 = BAND_Y1 + 14;
        CrateFx.lineGlow(g, cx, y0, cx, y1, 3, 14 + 10 * pass, color, alpha * (0.75F + 0.25F * pass));
        CrateFx.glow(g, cx, REEL_CY, 60, 170, color, alpha * (0.10F + 0.25F * pass + 0.3F * flare), true);
        for (int dir : new int[]{-1, 1}) {
            float tipY = dir < 0 ? y0 : y1;
            float baseY = tipY + dir * 20;
            CrateFx.triangle(g, cx - 13, baseY, cx + 13, baseY, cx, tipY + dir * 2, GuiFx.fade(color, alpha), false);
            CrateFx.glow(g, cx, tipY + dir * 8, 30, color, alpha * 0.5F);
        }
    }

    private static void renderWinnerFlare(GuiGraphics g, long time, float flare, int color, float tier) {
        float cx = REF_W * .5F;
        float in = CrateStage.easeOutCubic(Math.min(1, flare * 2.2F));
        float rot = time / 3200.0F;
        if (tier >= 0.5F) CrateFx.rays(g, cx, REEL_CY, 60, 560, 14, rot, 5, 0.62F, color, 0.35F * in * tier);
        float ring = CrateStage.easeOutCubic(flare);
        CrateFx.ring(g, cx, REEL_CY, 120 + 520 * ring, 26, 0.55F, color, 0.7F * (1 - ring));
        CrateFx.streak(g, cx, REEL_CY, 520 * in, 10, color, 0.55F * in);
        CrateFx.streak(g, cx, REEL_CY, 260 * in, 4, 0xFFFFFFFF, 0.6F * in);
    }

    // =====================================================================
    // 展示页标题与奖励汇总条
    // =====================================================================

    static void renderResultHeader(GuiGraphics g, Font font, float a, SkinQuality quality,
                                   ItemStack resultStack, String crateName) {
        if (a <= 0.02F) return;
        int color = rarityColor(quality);
        float cx = REF_W / 2, drop = 14 * (1 - a);
        textSpaced(g, font, Component.translatable(KEY + "reveal.eyebrow"), cx, 34 - drop, 16, 3.5F,
                GuiFx.fade(GuiFx.mix(0xFFFFFFFF, color, 0.6F), a), false);
        Component name = resultStack.isEmpty() ? Component.translatable(KEY + "skin_result", "") : resultStack.getHoverName();
        float nameW = textWidth(font, name, 50);
        CrateFx.glow(g, cx, 84 - drop, nameW * 0.7F + 80, 56, color, 0.35F * a, true);
        textCentered(g, font, name, cx, 58 - drop, 50, GuiFx.fade(TEXT_BRIGHT, a), true);
        Component chip = Component.translatable(KEY + "reveal.collection", Component.translatable(quality.translationKey()).getString());
        Component source = Component.translatable(KEY + "reveal.from", crateName);
        float w1 = textWidth(font, chip, 17) + 44, w2 = textWidth(font, source, 17) + 34, gap = 14;
        float x = cx - (w1 + w2 + gap) / 2, y0 = 124 - drop;
        CrateArt.chip(g, x, y0, x + w1, y0 + 32, color, a);
        CrateFx.diamond(g, x + 17, y0 + 16, 5, 5, GuiFx.fade(color, a), false);
        CrateFx.glow(g, x + 17, y0 + 16, 14, color, 0.6F * a);
        textLeft(g, font, chip, x + 30, y0 + 8, 17, GuiFx.fade(GuiFx.mix(0xFFFFFFFF, color, 0.45F), a), false);
        CrateArt.chip(g, x + w1 + gap, y0, x + w1 + gap + w2, y0 + 32, 0xFF8A9490, a);
        textLeft(g, font, source, x + w1 + gap + 17, y0 + 8, 17, GuiFx.fade(TEXT_BODY, a), false);
        CrateArt.rarityRule(g, (int) (cx - 420 * a), (int) (176 - drop), (int) (cx + 420 * a), color, a);
    }

    static void renderSummary(GuiGraphics g, Font font, long time, float a, CrateStage stage,
                              SkinQuality resultQuality, List<SummaryEntry> shown, int totalRewards,
                              int rewardPage, String crateName, String keyName) {
        if (a <= 0.02F) return;
        int color = rarityColor(resultQuality);
        float rise = 24 * (1 - a);
        CrateArt.glassPanel(g, PANEL_X0, PANEL_Y0 + rise, PANEL_X1, PANEL_Y1 + rise, 12, color, a * 0.96F);
        textLeft(g, font, Component.translatable(KEY + "reward_summary"), PANEL_X0 + 28, PANEL_Y0 + 14 + rise, 20,
                GuiFx.fade(TEXT_GOLD, a), true);
        int pages = Math.max(1, (totalRewards + MINI_SLOTS - 1) / MINI_SLOTS);
        textRight(g, font, Component.translatable(KEY + "strip.page", totalRewards, rewardPage + 1, pages),
                PANEL_X1 - 28, PANEL_Y0 + 16 + rise, 16, GuiFx.fade(TEXT_DIM, a), false);
        int from = rewardPage * MINI_SLOTS, to = Math.min(shown.size(), from + MINI_SLOTS);
        int count = Math.max(0, to - from);
        boolean single = shown.size() == 1;
        float rowW = count * MINI_W + Math.max(0, count - 1) * MINI_GAP;
        float left = single ? PANEL_X0 + 40 : (PANEL_X0 + PANEL_X1 - rowW) / 2;
        for (int i = from; i < to; i++) {
            SummaryEntry entry = shown.get(i);
            int local = i - from;
            float enter = CrateStage.easeOutCubic(CrateStage.progress(time, stage.revealAt() + 500 + 70L * local, 380));
            float x = left + local * (MINI_W + MINI_GAP), y = MINI_Y + rise + 16 * (1 - enter);
            CrateArt.rarityCard(g, x, y, MINI_W, MINI_H, rarityColor(entry.quality()), 1, 0.3F, a * enter);
            drawReward(g, entry.preview(), entry.stack(), x + MINI_W / 2, y + 44, 3.4F, 0, 0, 1, a * enter);
            if (entry.reward().amount() > 1) {
                textRight(g, font, Component.literal("×" + entry.reward().amount()), x + MINI_W - 8, y + 70, 16,
                        GuiFx.fade(TEXT_GOLD, a * enter), true);
            }
            textClipped(g, font, entry.stack().getHoverName(), x + 7, y + 92, 14, MINI_W - 14,
                    GuiFx.fade(TEXT_BODY, a * enter), false);
        }
        if (single) {
            float tx = left + MINI_W + 36, ty = MINI_Y + rise + 8;
            String[] lines = {
                    Component.translatable(KEY + "reveal.tip1", Component.translatable(resultQuality.translationKey())).getString(),
                    Component.translatable(KEY + "reveal.tip2", crateName, keyName).getString(),
                    Component.translatable(KEY + "reveal.tip3", crateName).getString()};
            int[] colors = {TEXT_BODY, 0xFFE0A060, TEXT_DIM};
            for (int i = 0; i < lines.length; i++) {
                CrateFx.diamond(g, tx + 4, ty + 10 + i * 36, 3.5F, 3.5F, GuiFx.fade(colors[i], a), false);
                textClipped(g, font, Component.literal(lines[i]), tx + 16, ty + i * 36, 18, PANEL_X1 - tx - 50,
                        GuiFx.fade(colors[i], a), false);
            }
        }
    }

    // =====================================================================
    // 长按空格跳过提示
    // =====================================================================

    static void renderSkipHint(GuiGraphics g, Font font, long time, float enter, CrateStage stage,
                               boolean show, boolean skipQueued, boolean holding, float skipShown) {
        if (!show || enter <= 0.02F || stage.cardsGone(time)) return;
        float a = enter * CrateStage.easeOutCubic(CrateStage.progress(time, stage.openedAt() + CrateStage.DISMISS_MS, 300));
        if (a <= 0.02F) return;
        float p = skipShown;

        Component cap = Component.translatable("key.keyboard.space");
        Component label = Component.translatable(KEY + (skipQueued ? "skip.queued"
                : holding ? "skip.holding" : "skip.hint"));
        float capW = Math.max(96, textWidth(font, cap, 17) + 40), capH = 34;
        float lift = 3 * CrateStage.easeOutCubic(p);
        float cy = 1045 - lift;
        float capX1 = REF_W - 72, capX0 = capX1 - capW, capY0 = cy - capH / 2, capY1 = cy + capH / 2;

        int base = GuiFx.fade(0xFF020303, a * 0.8F);
        CrateFx.bevelPanel(g, capX0, capY0 + 4 + lift, capX1, capY1 + 4 + lift, 6, base, base);
        CrateFx.bevelPanel(g, capX0, capY0, capX1, capY1, 6, GuiFx.fade(0xFF2A302E, a), GuiFx.fade(0xFF141817, a));
        if (p > 0.001F) {
            float fillX = capX0 + 1 + (capX1 - capX0 - 2) * p;
            CrateFx.hGradient(g, capX0 + 1, capY0 + 1, fillX, capY1 - 1,
                    GuiFx.fade(0xFF8C6A1E, a * 0.9F), GuiFx.fade(TEXT_GOLD, a * 0.95F), false);
            int edge = GuiFx.fade(0xFFFFF6D8, a);
            CrateFx.rect(g, fillX - 2, capY0 + 1, fillX, capY1 - 1, edge, edge, edge, edge, true);
            CrateFx.rectGlow(g, capX0, capY0, capX1, capY1, 10 + 14 * p, TEXT_GOLD, a * 0.35F * p);
        }
        CrateFx.bevelOutline(g, capX0, capY0, capX1, capY1, 6, 1.5F,
                holding ? GuiFx.fade(TEXT_GOLD, a) : GuiFx.fade(0x80FFFFFF, a));
        boolean dark = p > 0.55F;
        textCentered(g, font, cap, (capX0 + capX1) / 2, cy - 6, 17,
                dark ? GuiFx.fade(0xFF1A1405, a) : GuiFx.fade(TEXT_BRIGHT, a), !dark);

        float breathe = holding ? 1 : 0.7F + 0.3F * (float) Math.sin(time / 420.0);
        textRight(g, font, label, capX0 - 16, cy - 8, 18, GuiFx.fade(holding ? TEXT_GOLD : TEXT_DIM, a * breathe), true);

        float lineX0 = capX0 - 16 - textWidth(font, label, 18), lineY = capY1 + 7 + lift;
        int track = GuiFx.fade(0x30FFFFFF, a);
        CrateFx.rect(g, lineX0, lineY, capX1, lineY + 2, track, track, track, track, false);
        if (p > 0.001F) {
            CrateFx.hairline(g, lineX0, lineX0 + (capX1 - lineX0) * p, lineY, 2, 12,
                    GuiFx.fade(TEXT_GOLD, a), true);
        }
    }
}
