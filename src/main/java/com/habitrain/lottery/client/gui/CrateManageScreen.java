package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.crate.CrateOutputQuota;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Draft editor for crate identity, appearance and reward rules. The server validates every save. */
public final class CrateManageScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static final String KEY = "screen.habitrain_lottery.crate_manage.";
    private static final String[] TABS = {"profile", "rewards", "limits"};
    private final Screen parent;
    private CrateService.State draft;
    private CrateService.State latestQuotaState;
    private final Map<String, String> fields = new LinkedHashMap<>();
    private final Set<String> createdIds = new HashSet<>();
    private List<String> ids = List.of();
    private int selected, tab, scroll, seenVersion, x, w, contentTop, footerY, visibleRows;
    private boolean dirty, pending;
    private int pendingTicks;
    private boolean suppressNestedBackground;
    private String status = "";
    private List<Row> rows = List.of();
    private final List<EditBox> activeBoxes = new ArrayList<>();
    private int quotaRefreshTicks;
    private Button saveButton;
    private final CrateRewardEditor rewardEditor = new CrateRewardEditor(this);
    private final CrateQuotaEditor quotaEditor = new CrateQuotaEditor(this);

    private record Row(String key, String label, String value, int maxLength) {}

    public CrateManageScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        seenVersion = CrateClientNetwork.STATE.configVersion;
        readServerConfig();
    }

    private void readServerConfig() {
        try {
            CrateService.State loaded = GSON.fromJson(CrateClientNetwork.STATE.configJson, CrateService.State.class);
            if (loaded != null && loaded.crates != null && !loaded.crates.isEmpty()) {
                draft = loaded;
                latestQuotaState = loaded;
                ids = new ArrayList<>(draft.crates.keySet());
                selected = Mth.clamp(selected, 0, Math.max(0, ids.size() - 1));
                fields.clear(); createdIds.clear(); dirty = false;
            }
        } catch (RuntimeException error) { status = "crates.config_failed"; }
    }

    @Override protected void init() {
        if (!dirty && !pending) CrateClientNetwork.requestConfig();
        rebuild();
    }

    private String id() { return ids.isEmpty() ? "" : ids.get(Mth.clamp(selected, 0, ids.size() - 1)); }
    private CrateService.CratePool pool() { return draft == null ? null : draft.crates.get(id()); }
    private String fieldKey(String key) {
        return key.startsWith("cap.") || key.startsWith("weekly.") || key.startsWith("monthly.") || key.startsWith("unlimited.")
                ? key : id() + ":" + key;
    }
    private String value(String key, String initial) { return fields.getOrDefault(fieldKey(key), initial == null ? "" : initial); }
    private static String label(String suffix) {
        if (suffix.startsWith("extra.")) {
            String[] parts = suffix.split("\\.");
            if (parts.length == 3) {
                Component kind = "green_apples".equals(parts[1])
                        ? Component.translatable("screen.habitrain_lottery.warehouse.green_apples")
                        : Component.translatable("screen.habitrain_lottery.config.cards." + parts[1]);
                return kind.getString() + " · " + Component.translatable(KEY + "extra_" + parts[2]).getString();
            }
        }
        if (suffix.startsWith("skin.")) {
            String entry = suffix.substring(5);
            String[] parts = entry.split("/", 2);
            return parts.length == 2 ? SkinWardrobeScreen.skinName(parts[0], parts[1]).getString() : entry;
        }
        if (suffix.startsWith("weekly.") || suffix.startsWith("monthly.")) {
            String[] parts = suffix.split("\\.", 2);
            return Component.translatable("skin.habitrain_lottery.quality." + parts[1]).getString()
                    + " · " + Component.translatable(KEY + parts[0]).getString();
        }
        if (suffix.startsWith("unlimited.")) {
            String[] parts = suffix.split("\\.", 2);
            return Component.translatable("skin.habitrain_lottery.quality." + parts[1]).getString()
                    + " · " + Component.translatable(KEY + "unlimited").getString();
        }
        return Component.translatable(KEY + suffix).getString();
    }
    private Row row(String key, String initial, int max) { return new Row(key, label(key), value(key, initial), max); }

    private List<Row> buildRows() {
        CrateService.CratePool p = pool();
        if (p == null) return List.of();
        List<Row> result = new ArrayList<>();
        switch (tab) {
            case 0 -> {
                result.add(row("id", id(), 48));
                result.add(row("name", p.name, 64));
                // Built-in descriptions are stored as translation keys. Show the actual
                // warehouse text so editing this field writes a literal description.
                String description = p.description == null ? "" : p.description;
                result.add(row("description", Component.translatableWithFallback(description, description).getString(), 256));
                result.add(row("tier", p.tier, 24));
                result.add(row("enabled", String.valueOf(p.enabled), 5));
                result.add(row("archived", String.valueOf(p.archived), 5));
                result.add(row("key_name", p.keyName, 64));
            }
            default -> { }
        }
        return result;
    }

    private void rebuild() {
        clearWidgets(); activeBoxes.clear();
        boolean compact = height < 180 || (tab == 1 && height < 300) || (tab == 2 && height < 240);
        x = width < 420 ? 8 : 16; w = width - x * 2;
        contentTop = tab == 1 ? (compact ? 88 : 108) : (compact ? 64 : 84);
        footerY = height - 24;
        visibleRows = Math.max(1, (footerY - contentTop - (tab == 1 ? 20 : 6)) / 25);
        int tabW = (w - (TABS.length - 1) * 2) / TABS.length;
        int tabY = compact ? 18 : 30;
        int navY = compact ? 40 : 56;
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            addRenderableWidget(Button.builder(Component.translatable(KEY + TABS[i]), b -> {
                tab = index; scroll = 0; rebuild();
                }).bounds(x + i * (tabW + 2), tabY,
                        i == TABS.length - 1 ? width - x - (x + i * (tabW + 2)) : tabW, 20).build()).active = i != tab;
        }
        int newW = Math.max(36, Math.min(52, w / 6));
        int navW = Math.max(16, Math.min(22, w / 12));
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { selected = Math.floorMod(selected - 1, ids.size()); scroll = 0; rebuild(); })
                .bounds(x, navY, navW, 20).build()).active = ids.size() > 1;
        CrateService.CratePool selectedPool = pool();
        String selectedName = selectedPool == null ? id() : value("name", selectedPool.name);
        String selectedTitle = selectedName.isBlank() ? id() : Component.translatable(selectedName).getString() + " · " + id();
        Button selectedButton = addRenderableWidget(Button.builder(
                Component.literal(font.plainSubstrByWidth(selectedTitle, Math.max(12, w - navW * 2 - newW * 2 - 20))), b -> {
            Map<String, String> names = new LinkedHashMap<>();
            for (String crateId : ids) {
                CrateService.CratePool entry = draft.crates.get(crateId);
                if (entry != null) names.put(crateId, fields.getOrDefault(crateId + ":name", entry.name));
            }
            if (minecraft != null) minecraft.setScreen(new CrateListScreen(this, ids, names, chosen -> {
                selected = ids.indexOf(chosen); scroll = 0; rebuild();
            }));
        }).bounds(x + navW + 2, navY, Math.max(12, w - navW * 2 - newW * 2 - 8), 20).build());
        selectedButton.active = !ids.isEmpty();
        selectedButton.setTooltip(Tooltip.create(Component.literal(selectedTitle)));
        int right = width - x;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { selected = Math.floorMod(selected + 1, ids.size()); scroll = 0; rebuild(); })
                .bounds(right - newW * 2 - navW - 4, navY, navW, 20).build()).active = ids.size() > 1;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "new"), b -> create(false))
                .bounds(right - newW * 2 - 2, navY, newW, 20).build()).active = draft != null;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "copy"), b -> create(true))
                .bounds(right - newW, navY, newW, 20).build()).active = pool() != null;
        rows = tab == 1 ? List.of() : buildRows();
        if (tab == 1 && pool() != null) rewardEditor.build(x, navY + 24, w, footerY - 18);
        if (tab == 2 && pool() != null) quotaEditor.build(x, navY + 24, w, footerY - 18);
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows));
        int labelW = labelWidth();
        for (int i = scroll; i < rows.size() && i < scroll + visibleRows; i++) {
            Row row = rows.get(i);
            int y = contentTop + (i - scroll) * 25;
            if (isChoice(row.key())) {
                Button choice = Button.builder(choiceDisplay(row.key(), row.value()), b -> {
                    String current = fields.getOrDefault(fieldKey(row.key()), row.value());
                    String next = nextChoice(row.key(), current);
                    fields.put(fieldKey(row.key()), next);
                    b.setMessage(choiceDisplay(row.key(), next)); dirty = true;
                    if ("reward_mode".equals(row.key())) { scroll = 0; rebuild(); }
                    else if ("custom_pool".equals(row.key())) rebuild();
                    else if ("quota_period".equals(row.key())) rebuild();
                    else updateSave();
                }).bounds(x + labelW + 4, y, w - labelW - 4, 20).build();
                choice.setTooltip(Tooltip.create(Component.literal(row.label() + " · " + choiceDisplay(row.key(), row.value()).getString())));
                addRenderableWidget(choice);
                continue;
            }
            EditBox box = new EditBox(font, x + labelW + 4, y, w - labelW - 4, 20, Component.literal(row.label()));
            box.setMaxLength(row.maxLength());
            box.setValue(row.value());
            String hint = "description".equals(row.key())
                    ? Component.translatable(KEY + "description_hint").getString()
                    : row.key().startsWith("cap.") ? row.label() + " · " + row.key().substring(4)
                    + " · " + (draft.skinProduced == null ? 0 : draft.skinProduced.getOrDefault(row.key().substring(4), 0))
                    : row.label();
            box.setTooltip(Tooltip.create(Component.literal(hint)));
            box.setResponder(v -> {
                fields.put(fieldKey(row.key()), v); dirty = true;
                updateSave();
            });
            if (row.key().startsWith("skin.")) box.active = "true".equals(value("custom_pool", String.valueOf(pool().customPool)))
                    && HabiSkinApi.fromEntry(row.key().substring(5)).isPresent();
            addRenderableWidget(box); activeBoxes.add(box);
        }
        int footerW = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x, footerY, footerW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + ("conflict".equals(status) ? "reload" : "reset_current")),
                        b -> { if ("conflict".equals(status)) reloadAfterConflict(); else resetCurrent(); })
                .bounds(x + footerW + 4, footerY, footerW, 20).build()).active = pool() != null && !pending;
        saveButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "save"), b -> save())
                .bounds(x + (footerW + 4) * 2, footerY, w - (footerW + 4) * 2, 20).build());
        updateSave();
    }

    private int labelWidth() {
        return tab >= 2 ? Math.max(80, w - 110) : Mth.clamp(w / 3, 52, 132);
    }

    // The visual reward editor shares this screen's draft, validation and server save lifecycle.
    String rewardCrateId() { return id(); }
    CrateService.State quotaState() { return latestQuotaState; }
    CrateService.CratePool rewardPool() { return pool(); }
    String rewardValue(String key, Object fallback) { return value(key, String.valueOf(fallback)); }
    void rewardValue(String key, String next) { fields.put(fieldKey(key), next); dirty = true; status = ""; updateSave(); }
    void rewardChanged() { dirty = true; status = ""; rebuild(); }
    void rewardRebuild() { rebuild(); }
    void forgetRewardFields(String prefix) {
        String full = fieldKey(prefix);
        fields.keySet().removeIf(k -> prefix.endsWith(".") ? k.startsWith(full) : k.equals(full));
    }
    <T extends net.minecraft.client.gui.components.AbstractWidget> T rewardWidget(T widget) { return addRenderableWidget(widget); }

    private void resetCurrent() {
        if (draft == null || ids.isEmpty() || pending) return;
        String crateId = id();
        if (createdIds.remove(crateId)) {
            draft.crates.remove(crateId);
            ids = new ArrayList<>(draft.crates.keySet());
            selected = Mth.clamp(selected, 0, Math.max(0, ids.size() - 1));
        } else {
            try {
                CrateService.State saved = GSON.fromJson(CrateClientNetwork.STATE.configJson, CrateService.State.class);
                if (saved == null || saved.crates == null || !saved.crates.containsKey(crateId)) return;
                draft.crates.put(crateId, GSON.fromJson(GSON.toJson(saved.crates.get(crateId)), CrateService.CratePool.class));
            } catch (RuntimeException error) { status = "invalid"; return; }
        }
        fields.keySet().removeIf(key -> key.startsWith(crateId + ":"));
        dirty = true;
        status = "reset_current_done";
        scroll = 0;
        rebuild();
    }

    private void reloadAfterConflict() {
        if (minecraft == null) return;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                readServerConfig();
                status = "";
            }
            minecraft.setScreen(this);
        }, Component.translatable(KEY + "reload"), Component.translatable(KEY + "reload_confirm")));
    }

    private int intValue(String key, int fallback) {
        try { return Integer.parseInt(value(key, String.valueOf(fallback)).trim()); }
        catch (RuntimeException error) { return fallback; }
    }

    private static boolean isChoice(String key) {
        return switch (key) {
            case "tier", "enabled", "archived", "reward_mode", "same_skin",
                    "custom_pool", "duplicate_protection", "quota_period" -> true;
            default -> false;
        };
    }

    private static Component choiceDisplay(String key, String value) {
        return switch (key) {
            case "enabled" -> Component.translatable("screen.habitrain_lottery.crate_admin." + ("true".equals(value) ? "enabled" : "disabled"));
            case "archived", "same_skin" -> Component.translatable(KEY + key + "_" + ("true".equals(value) ? "yes" : "no"));
            case "tier" -> Component.translatable("skin.habitrain_lottery.quality." + value);
            case "reward_mode" -> Component.translatable(KEY + "mode_" + value);
            case "quota_period" -> Component.translatable(KEY + "quota_" + value);
            case "custom_pool" -> Component.translatable(KEY + ("true".equals(value) ? "pool_custom" : "pool_default"));
            case "duplicate_protection" -> Component.translatable(KEY + ("true".equals(value) ? "prefer_unowned" : "allow_owned"));
            default -> key.startsWith("unlimited.")
                    ? Component.translatable(KEY + ("true".equals(value) ? "unlimited_yes" : "unlimited_no"))
                    : Component.literal(value);
        };
    }

    private static String nextChoice(String key, String current) {
        String[] values = switch (key) {
            case "tier" -> new String[]{"white", "blue", "purple", "gold", "red"};
            case "reward_mode" -> new String[]{"skin_plus_bonus", "unified_pool"};
            case "quota_period" -> new String[]{"weekly", "monthly"};
            default -> new String[]{"false", "true"};
        };
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length];
        return values[0];
    }

    private void create(boolean copy) {
        if (draft == null || draft.crates.size() >= 256) return;
        String base = copy && pool() != null ? id() + "_copy" : "new_crate";
        String candidate = base;
        for (int i = 2; draft.crates.containsKey(candidate); i++) candidate = base + "_" + i;
        CrateService.CratePool source = null;
        if (copy) {
            try {
                CrateService.State current = GSON.fromJson(GSON.toJson(draft), CrateService.State.class);
                applyFields(current);
                source = current.crates.get(value("id", id()));
            } catch (RuntimeException error) { status = "invalid"; return; }
        }
        CrateService.CratePool next = source == null ? new CrateService.CratePool()
                : GSON.fromJson(GSON.toJson(source), CrateService.CratePool.class);
        next.name = candidate; next.keyName = candidate + " key";
        next.customPool = true; next.enabled = false;
        if (source == null) next.rewardMode = "unified_pool";
        next.skinWeights = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.skinWeights);
        draft.crates.put(candidate, next);
        createdIds.add(candidate);
        ids = new ArrayList<>(draft.crates.keySet()); selected = ids.indexOf(candidate);
        dirty = true; status = ""; tab = 0; scroll = 0; rebuild();
    }

    private static boolean booleanValue(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }

    private void applyFields(CrateService.State target) {
        Map<String, CrateService.CratePool> renamed = new LinkedHashMap<>();
        if (target.skinLifetimeCaps == null) target.skinLifetimeCaps = new LinkedHashMap<>();
        for (Map.Entry<String, CrateService.CratePool> item : target.crates.entrySet()) {
            String id = item.getKey(); CrateService.CratePool p = item.getValue();
            String prefix = id + ":";
            String nextId = fields.getOrDefault(prefix + "id", id).trim().toLowerCase(Locale.ROOT);
            if (!id.equals(nextId) && !createdIds.contains(id)) throw new IllegalArgumentException();
            if (!nextId.matches("[a-z0-9_.-]{1,48}") || renamed.containsKey(nextId)) throw new IllegalArgumentException();
            p.name = fields.getOrDefault(prefix + "name", p.name).trim();
            p.description = fields.getOrDefault(prefix + "description", p.description).trim();
            p.tier = fields.getOrDefault(prefix + "tier", p.tier).trim();
            p.enabled = booleanValue(fields.getOrDefault(prefix + "enabled", String.valueOf(p.enabled)));
            p.archived = booleanValue(fields.getOrDefault(prefix + "archived", String.valueOf(p.archived)));
            p.keyName = fields.getOrDefault(prefix + "key_name", p.keyName).trim();
            p.rewardMode = fields.getOrDefault(prefix + "reward_mode", p.rewardMode).trim();
            for (SkinQuality quality : SkinQuality.values()) {
                var cap = CrateOutputQuota.limit(p, quality);
                String root = prefix + "output." + quality.id() + ".";
                String period = fields.getOrDefault(root + "period", cap.period);
                int limit = Integer.parseInt(fields.getOrDefault(root + "limit", String.valueOf(cap.limit)).trim());
                if ((!"weekly".equals(period) && !"monthly".equals(period)) || limit < -1 || limit > CrateOutputQuota.MAX_LIMIT)
                    throw new IllegalArgumentException("invalid output limit");
                p.outputLimits.put(quality.id(), new CrateOutputQuota.Limit(period, limit));
            }
            p.skinDrawCount = Integer.parseInt(fields.getOrDefault(prefix + "skin_draw_count", String.valueOf(p.skinDrawCount)));
            p.rollCount = Integer.parseInt(fields.getOrDefault(prefix + "roll_count", String.valueOf(p.rollCount)));
            p.minimumSkinCount = Integer.parseInt(fields.getOrDefault(prefix + "minimum_skin_count", String.valueOf(p.minimumSkinCount)));
            p.allowSameSkinInOneOpen = booleanValue(fields.getOrDefault(prefix + "same_skin", String.valueOf(p.allowSameSkinInOneOpen)));
            p.customPool = booleanValue(fields.getOrDefault(prefix + "custom_pool", String.valueOf(p.customPool)));
            p.duplicateProtection = booleanValue(fields.getOrDefault(prefix + "duplicate_protection", String.valueOf(p.duplicateProtection)));
            if (p.skinWeights == null) p.skinWeights = new LinkedHashMap<>();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                String skinPrefix = prefix + "skin.";
                if (!field.getKey().startsWith(skinPrefix)) continue;
                int weight = Integer.parseInt(field.getValue().trim());
                if (weight < 0 || weight > 1_000_000_000) throw new IllegalArgumentException();
                p.skinWeights.put(field.getKey().substring(skinPrefix.length()), weight);
            }
            if (p.extraRewards != null) for (CrateService.ExtraReward extra : p.extraRewards) {
                String kind = "card".equals(extra.type) ? extra.cardKind : "green_apples";
                String root = prefix + "extra." + kind + ".";
                extra.amount = Integer.parseInt(fields.getOrDefault(root + "amount", String.valueOf(extra.amount)));
                extra.chance = Double.parseDouble(fields.getOrDefault(root + "chance", String.valueOf(extra.chance * 100))) / 100;
                extra.weight = Integer.parseInt(fields.getOrDefault(root + "weight", String.valueOf(extra.weight)));
                extra.maxPerOpen = Integer.parseInt(fields.getOrDefault(root + "max", String.valueOf(extra.maxPerOpen)));
            }
            renamed.put(nextId, p);
        }
        target.skinLifetimeCaps = new LinkedHashMap<>();
        for (SkinQuality quality : SkinQuality.values()) {
            String weekly = fields.get("weekly." + quality.id());
            String monthly = fields.get("monthly." + quality.id());
            boolean unlimited = "true".equalsIgnoreCase(fields.getOrDefault("unlimited." + quality.id(), "false"));
            if (unlimited) {
                target.weeklyCaps.put(quality.id(), -1);
                target.monthlyCaps.put(quality.id(), -1);
            } else {
                if (weekly != null) target.weeklyCaps.put(quality.id(), parseCapValue(weekly));
                if (monthly != null) target.monthlyCaps.put(quality.id(), parseCapValue(monthly));
            }
        }
        target.crates = renamed;
    }

    private static int parseCapValue(String raw) {
        int value = Integer.parseInt(raw.trim());
        if (value < 0 || value > 1_000_000_000) throw new IllegalArgumentException();
        return value;
    }

    private void save() {
        if (draft == null || pending || !dirty) return;
        try {
            CrateService.State proposed = GSON.fromJson(GSON.toJson(draft), CrateService.State.class);
            applyFields(proposed);
            pending = true; pendingTicks = 0; status = "saving"; updateSave();
            CrateClientNetwork.saveConfig(GSON.toJson(proposed));
        } catch (RuntimeException error) { status = "invalid"; pending = false; rebuild(); }
    }

    private void updateSave() {
        if (saveButton != null) saveButton.active = draft != null && dirty && !pending && !"conflict".equals(status)
                && CrateClientNetwork.connected() && CrateClientNetwork.canEdit() && CrateClientNetwork.STATE.configEditable
                && !MenuAccessBridge.isLocked() && validDraftInput();
        if (pending) for (var child : children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
            widget.active = false;
    }

    private boolean validDraftInput() {
        if (draft == null || pool() == null) return false;
        for (String value : fields.values()) {
            if (value == null || value.length() > 256) return false;
        }
        try {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String key = entry.getKey();
                if (key.contains(":skin.") || key.startsWith("cap.") || key.startsWith("weekly.") || key.startsWith("monthly.")) {
                    if (key.startsWith("cap.") && ("∞".equals(entry.getValue()) || entry.getValue().isBlank())) continue;
                    parseCapValue(entry.getValue());
                } else if (key.endsWith(":skin_draw_count") || key.endsWith(":minimum_skin_count")) {
                    int count = Integer.parseInt(entry.getValue().trim());
                    if (count < 0 || count > 10) return false;
                } else if (key.endsWith(":roll_count")) {
                    int count = Integer.parseInt(entry.getValue().trim());
                    if (count < 1 || count > 10) return false;
                } else if (key.contains(":output.") && key.endsWith(".limit")) {
                    int limit = Integer.parseInt(entry.getValue().trim());
                    if (limit < -1 || limit > CrateOutputQuota.MAX_LIMIT) return false;
                } else if (key.contains(":extra.")) {
                    String raw = entry.getValue().trim();
                    if (key.endsWith(".chance")) {
                        double chance = Double.parseDouble(raw);
                        if (!Double.isFinite(chance) || chance < 0 || chance > 100) return false;
                    } else {
                        int amount = Integer.parseInt(raw);
                        if (key.endsWith(".amount") && (amount < 1 || amount > 100_000)) return false;
                        if (key.endsWith(".weight") && (amount < 0 || amount > 1_000_000_000)) return false;
                        if (key.endsWith(".max") && (amount < 1 || amount > 10)) return false;
                    }
                }
            }
        } catch (RuntimeException error) { return false; }
        return rewardEditor.validationError() == null;
    }

    public void refreshFromState() {
        if (seenVersion == CrateClientNetwork.STATE.configVersion) return;
        seenVersion = CrateClientNetwork.STATE.configVersion;
        try {
            var latest = GSON.fromJson(CrateClientNetwork.STATE.configJson, CrateService.State.class);
            if (latest != null && latest.schemaVersion >= 5) latestQuotaState = latest;
        } catch (RuntimeException ignored) { }
        quotaEditor.refreshHints();
        String message = CrateClientNetwork.STATE.configMessage;
        if ("crates.no_permission".equals(message) || "crates.client_outdated".equals(message)) {
            pending = false; status = message; rebuild(); return;
        }
        if (pending) {
            if (message.isBlank()) return;
            pending = false;
            if ("crates.config_saved".equals(message)) {
                readServerConfig(); status = "saved"; rebuild();
            } else {
                status = serverRevisionChanged() ? "conflict" : message;
                rebuild();
            }
        } else if (!dirty) {
            // Usage-only responses must not steal keyboard focus or an in-progress selection.
            if (tab == 3 && draft != null && !serverRevisionChanged()) return;
            readServerConfig(); rebuild();
        }
        else if (serverRevisionChanged()) { status = "conflict"; rebuild(); }
    }

    private boolean serverRevisionChanged() {
        try {
            CrateService.State latest = GSON.fromJson(CrateClientNetwork.STATE.configJson, CrateService.State.class);
            return draft != null && latest != null && latest.revision != draft.revision;
        } catch (RuntimeException error) { return false; }
    }

    @Override public void tick() {
        super.tick(); refreshFromState();
        if (pending && ++pendingTicks > 200) { pending = false; status = "crates.config_failed"; rebuild(); }
        if (tab == 3 && !pending && ++quotaRefreshTicks >= 100) {
            quotaRefreshTicks = 0;
            CrateClientNetwork.requestConfig();
        }
        updateSave();
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (tab == 2 && rewardEditor.mouseScrolled(mx, my, horizontal, vertical)) return true;
        if (tab == 3 && quotaEditor.mouseScrolled(mx, my, vertical)) return true;
        if (my >= contentTop && my < footerY) {
            scroll = Mth.clamp(scroll + (vertical < 0 ? 1 : -1), 0, Math.max(0, rows.size() - visibleRows));
            rebuild(); return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.fill(x - 4, 4, width - x + 4, footerY - 2, 0xE51B242C);
        g.fill(x - 4, 4, width - x + 4, 7, 0xFF57C6D6);
        g.drawCenteredString(font, getTitle(), width / 2, (height < 180 || (tab == 2 && height < 300) || (tab == 3 && height < 240)) ? 4 : 9, 0xFFFFFFFF);
        if (tab == 2 && pool() != null) rewardEditor.render(g, mx, my);
        if (tab == 3 && pool() != null) quotaEditor.render(g);
        int labelW = labelWidth();
        for (int i = scroll; i < rows.size() && i < scroll + visibleRows; i++) {
            Row row = rows.get(i);
            String label = row.label();
            g.drawString(font, font.plainSubstrByWidth(label, labelW - 3), x, contentTop + (i - scroll) * 25 + 6,
                    0xFFC9D2D1, false);
        }
        if (height >= 180 && !(tab == 1 && height < 300) && !(tab == 2 && height < 240)) {
            if (!CrateClientNetwork.connected()) g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate_admin.offline"),
                    width / 2, 20, 0xFFFFD18A);
            else if (!CrateClientNetwork.canEdit()) g.drawCenteredString(font, Component.translatable("crates.client_outdated"),
                    width / 2, 20, 0xFFFFD18A);
            else if (!status.isBlank()) g.drawCenteredString(font,
                    Component.translatable(status.startsWith("crates.") || status.startsWith("screen.") ? status : KEY + status), width / 2,
                    20, 0xFFFFD18A);
        }
        if ((tab == 1 || tab == 2) && pool() != null) {
            Component ruleError = rewardEditor.validationError();
            Component feedback = !CrateClientNetwork.connected() ? Component.translatable("screen.habitrain_lottery.crate_admin.offline")
                    : !CrateClientNetwork.canEdit() ? Component.translatable("crates.client_outdated")
                    : !CrateClientNetwork.STATE.configEditable || MenuAccessBridge.isLocked() ? Component.translatable("crates.no_permission")
                    : ruleError != null ? ruleError : !validDraftInput() ? Component.translatable(KEY + "invalid")
                    : !status.isBlank() ? Component.translatable(status.startsWith("crates.") || status.startsWith("screen.") ? status : KEY + status)
                    : Component.translatable("screen.habitrain_lottery.reward_editor." + (dirty ? "unsaved" : "saved_hint"));
            g.drawString(font, font.plainSubstrByWidth(feedback.getString(), w), x, footerY - 12, 0xFFD4C595, false);
        }
        suppressNestedBackground = true;
        try { super.render(g, mx, my, delta); }
        finally { suppressNestedBackground = false; }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (!suppressNestedBackground) super.renderBackground(g, mx, my, delta);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
