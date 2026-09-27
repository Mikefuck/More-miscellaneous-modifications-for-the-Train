package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.crate.CrateOutputQuota;
import com.habitrain.lottery.crate.CrateService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** Five independent material caps, with the same draft/save lifecycle as crate rewards. */
final class CrateQuotaEditor {
    private static final String KEY = "screen.habitrain_lottery.crate_manage.";
    private static final int ROW_HEIGHT = 40;
    private final CrateManageScreen owner;
    private final List<EditBox> boxes = new ArrayList<>();
    private int x, y, width, bottom, first, visible;
    private String crate = "";

    CrateQuotaEditor(CrateManageScreen owner) { this.owner = owner; }

    void build(int x, int y, int width, int bottom) {
        this.x = x; this.y = y; this.width = width; this.bottom = bottom;
        boxes.clear();
        if (!crate.equals(owner.rewardCrateId())) { crate = owner.rewardCrateId(); first = 0; }
        visible = Math.max(1, Math.min(5, (bottom - y - 34) / ROW_HEIGHT));
        first = Mth.clamp(first, 0, 5 - visible);
        int inputW = Math.min(106, Math.max(76, width / 3));
        int periodW = Math.min(90, Math.max(66, width / 4));
        for (int i = first; i < first + visible; i++) {
            SkinQuality quality = SkinQuality.values()[i];
            int rowY = y + 16 + (i - first) * ROW_HEIGHT;
            String root = "output." + quality.id() + ".";
            var saved = CrateOutputQuota.limit(owner.rewardPool(), quality);
            Button period = Button.builder(periodLabel(quality), button -> {
                String current = owner.rewardValue(root + "period", (Object)saved.period);
                owner.rewardValue(root + "period", "monthly".equals(current) ? "weekly" : "monthly");
                button.setMessage(periodLabel(quality));
                refreshHints();
            }).bounds(x + width - inputW - periodW - 4, rowY, periodW, 18).build();
            period.setTooltip(Tooltip.create(Component.translatable(KEY + "output_period_hint")));
            owner.rewardWidget(period);
            EditBox box = new EditBox(Minecraft.getInstance().font, x + width - inputW, rowY, inputW, 18,
                    Component.translatable(KEY + "output_limit_label", Component.translatable(quality.translationKey())));
            box.setMaxLength(11);
            box.setValue(owner.rewardValue(root + "limit", saved.limit));
            box.setResponder(value -> {
                owner.rewardValue(root + "limit", value);
                box.setTextColor(valid(value) ? 0xFFE5ECEF : 0xFFFF7777);
                refreshHints();
            });
            box.setTextColor(valid(box.getValue()) ? 0xFFE5ECEF : 0xFFFF7777);
            owner.rewardWidget(box); boxes.add(box);
        }
        if (visible < 5) {
            owner.rewardWidget(Button.builder(Component.literal("‹"), b -> page(-visible))
                    .bounds(x, bottom - 18, 26, 18).build()).active = first > 0;
            owner.rewardWidget(Button.builder(Component.literal("›"), b -> page(visible))
                    .bounds(x + width - 26, bottom - 18, 26, 18).build()).active = first + visible < 5;
        }
        refreshHints();
    }

    private Component periodLabel(SkinQuality quality) {
        var saved = CrateOutputQuota.limit(owner.rewardPool(), quality);
        return Component.translatable(KEY + "quota_" + owner.rewardValue("output." + quality.id() + ".period", (Object)saved.period));
    }

    private CrateOutputQuota.Progress progress(SkinQuality quality) {
        var saved = CrateOutputQuota.limit(owner.rewardPool(), quality);
        String period = owner.rewardValue("output." + quality.id() + ".period", (Object)saved.period);
        String raw = owner.rewardValue("output." + quality.id() + ".limit", saved.limit);
        int limit = valid(raw) ? Integer.parseInt(raw.trim()) : saved.limit;
        return CrateOutputQuota.progress(owner.quotaState(), crate, quality, new CrateOutputQuota.Limit(period, limit));
    }

    private Component progressLabel(SkinQuality quality) {
        var progress = progress(quality);
        String raw = owner.rewardValue("output." + quality.id() + ".limit", -1);
        if (!valid(raw)) return Component.translatable(KEY + "output_invalid");
        return progress.limit() < 0
                ? Component.translatable(KEY + "output_unlimited", progress.produced(), progress.reserved())
                : Component.translatable(KEY + "output_progress", progress.produced(), progress.limit(), progress.remaining(), progress.reserved());
    }

    void refreshHints() {
        for (int i = 0; i < boxes.size(); i++) {
            SkinQuality quality = SkinQuality.values()[first + i];
            Component hint = Component.translatable(KEY + "output_limit_hint").append("\n").append(progressLabel(quality));
            boxes.get(i).setTooltip(Tooltip.create(hint));
        }
    }

    void render(GuiGraphics graphics) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, font.plainSubstrByWidth(Component.translatable(KEY + "output_hint").getString(), width), x, y + 2, 0xFFB9CED2, false);
        int inputW = Math.min(106, Math.max(76, width / 3));
        int periodW = Math.min(90, Math.max(66, width / 4));
        for (int i = first; i < first + visible; i++) {
            SkinQuality quality = SkinQuality.values()[i];
            int rowY = y + 16 + (i - first) * ROW_HEIGHT;
            graphics.fill(x, rowY, x + 3, rowY + 18, quality.color());
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable(quality.translationKey()).getString(),
                    Math.max(16, width - inputW - periodW - 18)), x + 8, rowY + 5, quality.color(), false);
            graphics.drawString(font, font.plainSubstrByWidth(progressLabel(quality).getString(), width), x, rowY + 22, 0xFFD6E1E4, false);
            var progress = progress(quality);
            graphics.fill(x, rowY + 34, x + width, rowY + 38, 0xFF33444D);
            graphics.fill(x, rowY + 34, x + width * progress.percent() / 100, rowY + 38, quality.color());
        }
        if (visible < 5) graphics.drawCenteredString(font, Component.translatable(KEY + "output_page", first + 1, first + visible),
                x + width / 2, bottom - 13, 0xFFB9CED2);
    }

    private void page(int change) { first = Mth.clamp(first + change, 0, 5 - visible); owner.rewardRebuild(); }

    boolean mouseScrolled(double mx, double my, double amount) {
        if (mx < x || mx > x + width || my < y || my > bottom || visible >= 5 || amount == 0) return false;
        page(amount < 0 ? 1 : -1); return true;
    }

    private static boolean valid(String raw) {
        try { int value = Integer.parseInt(raw.trim()); return value >= -1 && value <= CrateOutputQuota.MAX_LIMIT; }
        catch (RuntimeException error) { return false; }
    }
}
