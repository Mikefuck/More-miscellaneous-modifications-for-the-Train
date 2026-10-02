package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.crate.CrateService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Visual reward strip and a focused editor; persistence remains owned by CrateManageScreen. */
final class CrateRewardEditor {
    private static final String KEY = "screen.habitrain_lottery.reward_editor.";
    private static final String[] EXTRAS = {"green_apples", "civilian", "neutral", "neutral_for_killer", "killer", "self_select", "limit_break"};
    private static final int PANEL = 0xFF243442, INK = 0xFFE6EDF2, MUTED = 0xFFB1C4D0, ACCENT = 0xFF72CFD7;
    private final CrateManageScreen host;
    private final Font font = Minecraft.getInstance().font;
    private final List<Label> labels = new ArrayList<>();
    private List<String> rewards = List.of(), catalog = List.of();
    private String crateId = "", selected = "", query = "";
    private int x, y, w, bottom, stripY, stripH, toolbarY, bodyY, page, stripOffset, bodyOffset, pageSize, visibleRows;
    private int filter, quality = -1;
    private boolean replacing;
    private record Label(Component text, int x, int y, int width) {}

    CrateRewardEditor(CrateManageScreen host) { this.host = host; }
    private CrateService.CratePool pool() { return host.rewardPool(); }
    private static Component tr(String key, Object... args) { return Component.translatable(KEY + key, args); }
    private String value(String key, Object fallback) { return host.rewardValue(key, fallback); }
    private boolean unified() { return "unified_pool".equals(value("reward_mode", pool().rewardMode)); }
    private int number(String key, int fallback) {
        try { return Integer.parseInt(value(key, fallback).trim()); }
        catch (RuntimeException ignored) { return fallback; }
    }
    private double decimal(String key, double fallback) {
        try { double n = Double.parseDouble(value(key, fallback)); return Double.isFinite(n) ? n : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private boolean custom() { return Boolean.parseBoolean(value("custom_pool", pool().customPool)); }
    private static boolean skin(String key) { return key.startsWith("skin."); }
    private String extraKind(CrateService.ExtraReward extra) { return "card".equals(extra.type) ? extra.cardKind : "green_apples"; }
    private CrateService.ExtraReward extra(String key) {
        return pool().extraRewards.stream().filter(e -> extraKind(e).equals(key.substring(6))).findFirst().orElse(null);
    }
    private int weight(String key) {
        if (skin(key)) return number(key, pool().skinWeights.getOrDefault(key.substring(5), 0));
        var e = extra(key);
        return e == null ? 0 : number(key + ".weight", e.weight);
    }
    private String name(String key) {
        if (skin(key)) {
            String[] pair = key.substring(5).split("/", 2);
            return pair.length == 2 ? SkinWardrobeScreen.skinName(pair[0], pair[1]).getString() : key;
        }
        String kind = key.substring(6);
        return Component.translatable(kind.equals("green_apples") ? "screen.habitrain_lottery.warehouse.green_apples"
                : "screen.habitrain_lottery.config.cards." + kind).getString();
    }
    private int color(String key) {
        return skin(key) ? HabiSkinApi.fromEntry(key.substring(5)).map(s -> s.quality().color()).orElse(0xFF8899AA)
                : key.endsWith("green_apples") ? 0xFF8ED073 : 0xFFE9B86E;
    }
    private boolean missing(String key) { return skin(key) && HabiSkinApi.fromEntry(key.substring(5)).isEmpty(); }
    private void manualPool() {
        // Freeze the visible legacy pool on the first edit, so later providers cannot silently add rewards.
        if (!custom()) host.rewardValue("custom_pool", "true");
    }
    private void collectRewards() {
        List<String> keys = new ArrayList<>();
        pool().skinWeights.forEach((key, weight) -> keys.add("skin." + key));
        for (var e : pool().extraRewards) keys.add("extra." + extraKind(e));
        rewards = keys;
        if (!rewards.contains(selected)) selected = rewards.isEmpty() ? "" : rewards.getFirst();
    }

    void build(int x, int y, int w, int bottom) {
        this.x = x; this.y = y; this.w = w; this.bottom = bottom;
        if (!crateId.equals(host.rewardCrateId())) {
            crateId = host.rewardCrateId(); selected = ""; page = 0; stripOffset = bodyOffset = 0; replacing = false;
        }
        labels.clear(); collectRewards();
        stripY = y + 13; stripH = bottom - y < 150 ? 40 : bottom - y < 170 ? 52 : 68;
        toolbarY = stripY + stripH + 5; bodyY = toolbarY + 26;
        int previousPageSize = pageSize;
        pageSize = Math.max(1, (w - 44) / 106);
        if (previousPageSize != pageSize && !selected.isEmpty()) {
            int index = rewards.indexOf(selected);
            if (index < stripOffset) stripOffset = index;
            else if (index >= stripOffset + pageSize) stripOffset = index - pageSize + 1;
        }
        stripOffset = Mth.clamp(stripOffset, 0, Math.max(0, rewards.size() - pageSize));
        button(Component.literal("‹"), x, stripY, 18, stripH, b -> { stripOffset = Math.max(0, stripOffset - pageSize); host.rewardRebuild(); }).active = stripOffset > 0;
        button(Component.literal("›"), x + w - 18, stripY, 18, stripH, b -> { stripOffset += pageSize; host.rewardRebuild(); }).active = stripOffset + pageSize < rewards.size();
        int cardW = (w - 44 - (pageSize - 1) * 4) / pageSize;
        for (int i = stripOffset; i < Math.min(rewards.size(), stripOffset + pageSize); i++) {
            String key = rewards.get(i);
            host.rewardWidget(new RewardButton(key, x + 22 + (i - stripOffset) * (cardW + 4), stripY, cardW, stripH, false,
                    b -> { selected = key; page = 0; bodyOffset = 0; host.rewardRebuild(); }));
        }
        int third = (w - 8) / 3;
        button(tr("edit"), x, toolbarY, third, 20, b -> show(0)).active = page != 0;
        button(tr("add"), x + third + 4, toolbarY, third, 20, b -> { replacing = false; show(1); }).active = page != 1 || replacing;
        button(tr("rules"), x + (third + 4) * 2, toolbarY, w - (third + 4) * 2, 20, b -> show(2)).active = page != 2;
        if (page == 1) buildCatalog();
        else if (page == 2) buildRules();
        else buildDetails();
    }
    private void show(int next) { page = next; bodyOffset = 0; host.rewardRebuild(); }
    private Button button(Component text, int x, int y, int w, int h, Button.OnPress press) {
        Component description = switch (text.getString()) {
            case "‹" -> tr("previous_rewards"); case "›" -> tr("next_rewards");
            case "↑" -> tr("previous_settings"); case "↓" -> tr("next_settings");
            default -> text;
        };
        Button b = host.rewardWidget(Button.builder(text, press).bounds(x, y, Math.max(12, w), h)
                .createNarration(supplier -> description.copy()).build());
        b.setTooltip(Tooltip.create(description)); return b;
    }
    private void label(Component text, int x, int y, int width) { labels.add(new Label(text, x, y, width)); }
    private void input(String key, Object initial, Component title, int x, int y, int width, int min, int max, boolean percent) {
        int labelW = Math.min(126, width / 2);
        label(title, x, y + 6, labelW - 4);
        EditBox box = new EditBox(font, x + labelW, y, width - labelW, 20, title);
        box.setMaxLength(12); box.setValue(value(key, initial));
        Component hint = tr(percent ? "percent_range" : "range", min, max);
        box.setTooltip(Tooltip.create(Component.empty().append(title).append(" · ").append(hint)));
        Consumer<String> validate = raw -> {
            boolean valid;
            try {
                double parsed = percent ? Double.parseDouble(raw.trim()) : Integer.parseInt(raw.trim());
                valid = Double.isFinite(parsed) && parsed >= min && parsed <= max;
            } catch (RuntimeException ignored) { valid = false; }
            box.setTextColor(valid ? INK : 0xFFFF8D8D);
        };
        validate.accept(box.getValue());
        box.setResponder(raw -> {
            if (raw.equals(value(key, initial))) return;
            manualPool(); host.rewardValue(key, raw); validate.accept(raw);
        });
        host.rewardWidget(box);
    }
    private void buildDetails() {
        if (selected.isEmpty()) return;
        int detailX = x, detailW = w;
        if (w >= 480) { detailX += 144; detailW -= 144; }
        int actionW = Math.min(74, detailW / 4);
        label(Component.literal(name(selected)), detailX, bodyY + 5, detailW - actionW * 2 - 12);
        button(tr("remove"), detailX + detailW - actionW, bodyY, actionW, 20, b -> removeSelected());
        if (skin(selected)) button(tr("replace"), detailX + detailW - actionW * 2 - 4, bodyY, actionW, 20, b -> {
            replacing = true; filter = 1; show(1);
        });
        int top = bodyY + 26;
        int count = skin(selected) ? 2 : 4;
        visibleRows = Math.max(1, (bottom - top - 4) / 25);
        bodyOffset = Mth.clamp(bodyOffset, 0, Math.max(0, count - visibleRows));
        int fieldW = detailW - 44;
        for (int slot = bodyOffset; slot < Math.min(count, bodyOffset + visibleRows); slot++) {
            int at = top + (slot - bodyOffset) * 25;
            if (skin(selected)) {
                if (slot == 0) input(selected, pool().skinWeights.getOrDefault(selected.substring(5), 0), tr("weight"), detailX, at, fieldW, 0, 1_000_000_000, false);
                if (slot == 1) button(tr("only_reward"), detailX, at, fieldW, 20, b -> onlySelectedReward()).active = !missing(selected);
            }
            else {
                var e = extra(selected);
                if (slot == 0) input(selected + ".amount", e.amount, tr("amount"), detailX, at, fieldW, 1, 100_000, false);
                if (slot == 1 && unified()) input(selected + ".weight", e.weight, tr("weight"), detailX, at, fieldW, 0, 1_000_000_000, false);
                if (slot == 1 && !unified()) input(selected + ".chance", e.chance * 100, tr("chance"), detailX, at, fieldW, 0, 100, true);
                if (slot == 2) input(selected + ".max", e.maxPerOpen, tr("max"), detailX, at, fieldW, 1, 10, false);
                if (slot == 3) button(tr("only_reward"), detailX, at, fieldW, 20, b -> onlySelectedReward());
            }
        }
        scrollButtons(detailX + detailW - 40, top, count, visibleRows);
        int hintY = top + Math.min(count, visibleRows) * 25 + 3;
        if (hintY + 10 < bottom) label(tr("weight_hint"), detailX, hintY, detailW);
    }
    private void scrollButtons(int x, int y, int count, int visible) {
        if (count <= visible) return;
        button(Component.literal("↑"), x, y, 18, 20, b -> { bodyOffset--; host.rewardRebuild(); }).active = bodyOffset > 0;
        button(Component.literal("↓"), x + 20, y, 18, 20, b -> { bodyOffset++; host.rewardRebuild(); }).active = bodyOffset + visible < count;
    }
    private void buildRules() {
        int count = 5;
        visibleRows = Math.max(1, (bottom - bodyY - 4) / 25);
        bodyOffset = Mth.clamp(bodyOffset, 0, Math.max(0, count - visibleRows));
        int fieldW = w - 46;
        for (int row = bodyOffset; row < Math.min(count, bodyOffset + visibleRows); row++) {
            int at = bodyY + (row - bodyOffset) * 25;
            if (row == 0) label(tr("unified_hint"), x, at + 6, fieldW);
            if (row == 1) input("roll_count", pool().rollCount, tr("rolls"), x, at, fieldW, 1, 10, false);
            if (row == 2) toggle("same_skin", pool().allowSameSkinInOneOpen, "same", x, at, fieldW);
            if (row == 3) toggle("duplicate_protection", pool().duplicateProtection, "unowned", x, at, fieldW);
            if (row == 4) label(tr("probability_hint"), x, at + 5, fieldW);
        }
        scrollButtons(x + w - 40, bodyY, count, visibleRows);
    }
    private void toggle(String key, boolean initial, String title, int x, int y, int w) {
        boolean on = Boolean.parseBoolean(value(key, initial));
        button(tr(title, Component.translatable(on ? "options.on" : "options.off")), x, y, w, 20, b -> {
            host.rewardValue(key, String.valueOf(!on)); host.rewardRebuild();
        });
    }
    private void buildCatalog() {
        int filterW = Math.min(72, w / 4), qualityW = Math.min(80, w / 4);
        button(tr("filter_" + filter), x, bodyY, filterW, 20, b -> { filter = (filter + 1) % 3; bodyOffset = 0; host.rewardRebuild(); });
        button(quality < 0 ? tr("all_quality") : Component.translatable(SkinQuality.values()[quality].translationKey()),
                x + filterW + 4, bodyY, qualityW, 20, b -> { quality = quality >= SkinQuality.values().length - 1 ? -1 : quality + 1; bodyOffset = 0; host.rewardRebuild(); });
        EditBox search = new EditBox(font, x + filterW + qualityW + 8, bodyY, w - filterW - qualityW - 8, 20, tr("search"));
        search.setMaxLength(80); search.setHint(tr("search")); search.setValue(query);
        search.setResponder(raw -> {
            if (query.equals(raw)) return;
            query = raw; bodyOffset = 0; host.rewardRebuild();
            // Focus the rebuilt search, retaining typing continuity.
            for (var child : host.children()) if (child instanceof EditBox box && box.getY() == bodyY) {
                host.setFocused(box); box.setFocused(true); box.moveCursorToEnd(false); break;
            }
        });
        host.rewardWidget(search);
        List<String> entries = new ArrayList<>();
        if (filter != 1 && !replacing) for (String kind : EXTRAS) entries.add("extra." + kind);
        if (filter != 2) for (SkinDefinition skin : HabiSkinApi.registrations()) {
            if (quality < 0 || skin.quality().ordinal() == quality) entries.add("skin." + skin.type() + "/" + skin.id());
        }
        String lowered = query.trim().toLowerCase(Locale.ROOT);
        catalog = entries.stream().filter(k -> lowered.isEmpty() || k.toLowerCase(Locale.ROOT).contains(lowered)
                || name(k).toLowerCase(Locale.ROOT).contains(lowered)).toList();
        int top = bodyY + 24, columns = w >= 500 ? 2 : 1;
        visibleRows = Math.max(1, (bottom - top) / 28);
        int totalRows = (catalog.size() + columns - 1) / columns;
        bodyOffset = Mth.clamp(bodyOffset, 0, Math.max(0, totalRows - visibleRows));
        int cw = (w - 46 - (columns - 1) * 4) / columns;
        for (int i = bodyOffset * columns; i < Math.min(catalog.size(), (bodyOffset + visibleRows) * columns); i++) {
            String key = catalog.get(i);
            host.rewardWidget(new RewardButton(key, x + (i % columns) * (cw + 4), top + (i / columns - bodyOffset) * 28,
                    cw, 25, true, b -> addReward(key)));
        }
        scrollButtons(x + w - 40, top, totalRows, visibleRows);
        if (catalog.isEmpty()) label(tr("no_results"), x + 4, top + 7, w - 8);
    }
    private void addReward(String key) {
        // An already configured entry is a selection, never an implicit merge/overwrite.
        if (rewards.contains(key) && !selected.equals(key)) {
            selected = key; replacing = false; page = 0; bodyOffset = 0;
            stripOffset = Math.max(0, rewards.indexOf(key) - pageSize + 1);
            host.rewardRebuild(); return;
        }
        manualPool();
        int previous = !selected.isEmpty() && skin(selected) ? weight(selected) : 100;
        if (replacing && skin(selected) && !selected.equals(key)) {
            pool().skinWeights.remove(selected.substring(5)); host.forgetRewardFields(selected);
        }
        if (skin(key)) {
            pool().skinWeights.putIfAbsent(key.substring(5), 0);
            if (replacing || weight(key) <= 0) host.rewardValue(key, String.valueOf(replacing ? Math.max(1, previous) : 100));
        } else if (extra(key) == null) {
            CrateService.ExtraReward reward = new CrateService.ExtraReward();
            String kind = key.substring(6);
            reward.type = kind.equals("green_apples") ? kind : "card";
            reward.cardKind = kind.equals("green_apples") ? "" : kind;
            reward.chance = 1;
            pool().extraRewards.add(reward);
        }
        selected = key; replacing = false; page = 0; bodyOffset = 0;
        collectRewards(); stripOffset = Math.max(0, rewards.indexOf(key) - pageSize + 1);
        host.rewardChanged();
    }
    private void removeSelected() {
        if (selected.isEmpty()) return;
        manualPool();
        if (skin(selected)) pool().skinWeights.remove(selected.substring(5));
        else pool().extraRewards.remove(extra(selected));
        host.forgetRewardFields(skin(selected) ? selected : selected + ".");
        selected = ""; bodyOffset = 0; host.rewardChanged();
    }
    private String probability(String key) {
        if (missing(key)) return tr("missing").getString();
        if (!skin(key) && !unified()) {
            var e = extra(key); return format(e == null ? 0 : decimal(key + ".chance", e.chance * 100));
        }
        if (skin(key) && !unified() && number("skin_draw_count", pool().skinDrawCount) == 0) return tr("inactive").getString();
        double total = 0;
        for (String k : rewards) if (!missing(k) && (unified() || skin(k))) total += Math.max(0, weight(k));
        return format(total > 0 ? Math.max(0, weight(key)) * 100D / total : 0);
    }
    private String format(double chance) { return String.format(Locale.ROOT, "%.2f%%", chance); }
    Component validationError() {
        if (pool() == null) return null;
        int required = unified() ? number("minimum_skin_count", pool().minimumSkinCount) : number("skin_draw_count", pool().skinDrawCount);
        if (unified() && required > number("roll_count", pool().rollCount)) return tr("min_invalid");
        long total = 0, candidates = 0;
        for (var entry : pool().skinWeights.entrySet()) {
            int weight = number("skin." + entry.getKey(), entry.getValue() == null ? 0 : entry.getValue());
            total += Math.max(0, weight);
            if (weight > 0) candidates++;
        }
        if (total > 4_000_000_000L) return tr("total_invalid");
        if (!Boolean.parseBoolean(value("enabled", pool().enabled))) return null;
        if (custom() && required > 0 && total <= 0) return tr("empty_invalid");
        if (custom() && !Boolean.parseBoolean(value("same_skin", pool().allowSameSkinInOneOpen)) && candidates < required) return tr("skins_invalid");
        boolean extraEnabled = pool().extraRewards.stream().anyMatch(e -> unified()
                ? number("extra." + extraKind(e) + ".weight", e.weight) > 0
                : decimal("extra." + extraKind(e) + ".chance", e.chance * 100) > 0);
        if ((unified() ? total <= 0 : required == 0) && !extraEnabled) return tr("empty_invalid");
        return null;
    }
    private void onlySelectedReward() {
        if (selected.isEmpty() || missing(selected)) return;
        manualPool();
        boolean selectedSkin = skin(selected);
        for (String key : rewards) {
            boolean chosen = key.equals(selected);
            host.rewardValue(skin(key) ? key : key + ".weight",
                    String.valueOf(chosen ? Math.max(1, weight(key)) : 0));
            if (!skin(key)) {
                // In skin_plus_bonus, items use independent chances instead of weights.
                host.rewardValue(key + ".chance", chosen ? "100" : "0");
            }
        }
        host.rewardValue("skin_draw_count", selectedSkin ? "1" : "0");
        host.rewardValue("minimum_skin_count", "0");
        host.rewardValue("roll_count", "1");
        host.rewardChanged();
    }
    private void icon(GuiGraphics g, String key, int x, int y, float scale) {
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(scale, scale, 1);
        if (skin(key)) {
            ItemStack stack = SkinItems.preview(key.substring(5));
            g.renderItem(stack.isEmpty() ? new ItemStack(Items.BARRIER) : stack, 0, 0);
        } else {
            String kind = key.substring(6);
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/"
                    + (kind.equals("green_apples") ? "green_apple" : "cards/" + kind) + ".png");
            g.blit(texture, 0, 0, 0, 0, 16, 16, 16, 16);
        }
        g.pose().popPose();
    }
    void render(GuiGraphics g, int mx, int my) {
        g.drawString(font, tr("strip", rewards.size()), x, y, INK, false);
        String scope = tr(unified() ? "per_roll" : "per_skin").getString();
        if (font.width(scope) + font.width(tr("strip", rewards.size())) + 16 < w)
            g.drawString(font, scope, x + w - font.width(scope), y, MUTED, false);
        g.fill(x + 20, stripY, x + w - 20, stripY + stripH, 0xFF15232E);
        if (rewards.isEmpty()) {
            g.drawCenteredString(font, tr("empty"), x + w / 2, stripY + stripH / 2 - 10, INK);
            g.drawCenteredString(font, tr("empty_hint"), x + w / 2, stripY + stripH / 2 + 4, MUTED);
        }
        g.fill(x, bodyY - 2, x + w, bottom, PANEL);
        if (page == 0 && selected.isEmpty()) g.drawCenteredString(font, tr("start"), x + w / 2, bodyY + 12, MUTED);
        if (page == 0 && !selected.isEmpty() && w >= 480) {
            icon(g, selected, x + 48, bodyY + 8, 2.5F);
            if (bodyY + 64 < bottom) g.drawCenteredString(font, probability(selected), x + 70, bodyY + 54, color(selected));
            if (bodyY + 78 < bottom) g.drawCenteredString(font, tr(skin(selected) ? "skin" : "item"), x + 70, bodyY + 68, MUTED);
        }
        for (Label label : labels) {
            g.drawString(font, font.plainSubstrByWidth(label.text.getString(), Math.max(8, label.width)), label.x, label.y, MUTED, false);
        }
    }
    boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx < x || mx > x + w || my < y || my >= bottom) return false;
        double delta = vertical != 0 ? vertical : horizontal;
        if (delta == 0) return false;
        if (my >= stripY && my < stripY + stripH) stripOffset += delta < 0 ? 1 : -1;
        else if (my >= bodyY) bodyOffset += delta < 0 ? 1 : -1;
        else return false;
        host.rewardRebuild(); return true;
    }

    private final class RewardButton extends Button {
        private final String key;
        private final boolean catalogItem;
        RewardButton(String key, int x, int y, int w, int h, boolean catalogItem, OnPress press) {
            super(x, y, w, h, Component.literal(name(key)), press, DEFAULT_NARRATION);
            this.key = key; this.catalogItem = catalogItem;
            setTooltip(Tooltip.create(Component.literal(name(key) + "\n" + key.substring(skin(key) ? 5 : 6))
                    .append("\n").append(tr(catalogItem ? "click_add" : "probability_hint"))));
        }
        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
            int bx = getX(), by = getY(), bw = getWidth(), bh = getHeight();
            boolean chosen = !catalogItem && selected.equals(key);
            g.fill(bx, by, bx + bw, by + bh, isHoveredOrFocused() ? 0xFF3B5366 : chosen ? 0xFF30495A : PANEL);
            g.fill(bx, by + bh - 2, bx + bw, by + bh, color(key));
            if (chosen || isFocused()) g.renderOutline(bx, by, bw, bh, chosen ? ACCENT : INK);
            if (catalogItem) {
                icon(g, key, bx + 4, by + 4, 1);
                g.drawString(font, font.plainSubstrByWidth(name(key), bw - 48), bx + 26, by + 8, INK, false);
                g.drawString(font, rewards.contains(key) ? "✓" : "+", bx + bw - 15, by + 8, ACCENT, false);
            } else if (bh <= 40) {
                icon(g, key, bx + 4, by + 5, 1);
                g.drawString(font, font.plainSubstrByWidth(name(key), bw - 28), bx + 25, by + 6, INK, false);
                g.drawCenteredString(font, probability(key), bx + bw / 2, by + bh - 13, color(key));
            } else {
                float scale = bh > 60 ? 1.7F : 1.1F;
                icon(g, key, bx + bw / 2 - (int)(8 * scale), by + 4, scale);
                g.drawCenteredString(font, font.plainSubstrByWidth(name(key), bw - 8), bx + bw / 2, by + bh - 26, INK);
                String odds = probability(key);
                if (!skin(key)) { var e = extra(key); if (e != null) odds += " ×" + number(key + ".amount", e.amount); }
                g.drawCenteredString(font, font.plainSubstrByWidth(odds, bw - 6), bx + bw / 2, by + bh - 13, color(key));
            }
        }
    }
}
