package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.LotteryNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-authoritative crate editor. It lives behind the root Mod Menu
 * section, but keeps its own scrollable editor so a large skin catalogue does
 * not make the main operations console unwieldy.
 */
public final class CrateAdminScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static final int ROWS = 8;
    private static final int ROW_H = 24;

    private final Screen parent;
    private final List<CrateService.Definition> definitions = new ArrayList<>();
    private final Map<String, DraftPool> pools = new LinkedHashMap<>();
    private final EnumMap<SkinQuality, Integer> weeklyCaps = new EnumMap<>(SkinQuality.class);
    private final EnumMap<SkinQuality, Integer> monthlyCaps = new EnumMap<>(SkinQuality.class);
    private final EnumMap<SkinQuality, Integer> weeklyUsed = new EnumMap<>(SkinQuality.class);
    private final EnumMap<SkinQuality, Integer> monthlyUsed = new EnumMap<>(SkinQuality.class);
    private final List<SkinDefinition> visibleSkins = new ArrayList<>();
    private final String[] rowKeys = new String[ROWS];
    private final Button[] rowButtons = new Button[ROWS];
    private final EditBox[] rowWeights = new EditBox[ROWS];
    private final EditBox[] weeklyBoxes = new EditBox[SkinQuality.values().length];
    private final EditBox[] monthlyBoxes = new EditBox[SkinQuality.values().length];
    private final String[] weeklyDrafts = new String[SkinQuality.values().length];
    private final String[] monthlyDrafts = new String[SkinQuality.values().length];

    private int selectedCrate;
    private int skinScroll;
    private int configVersion = -1;
    private boolean draftLoaded;
    private String status = "";
    private String skinQuery = "";
    private int qualityFilter = -1;
    private boolean quotaPage;
    private boolean dirty;
    private boolean savePending;
    private int savePendingTicks;
    private boolean updatingWidgets;
    private boolean suppressNestedBackground;
    private int editorX;
    private int editorW;
    private int rowsTop;
    private int footerY;
    private int visibleRows;
    private int visibleCapRows;
    private int capScroll;

    private Button selectedCrateButton;
    private Button poolPageButton;
    private Button quotaPageButton;
    private Button enabledButton;
    private Button customPoolButton;
    private Button duplicateButton;
    private Button qualityFilterButton;
    private Button saveButton;
    private Button resetButton;
    private EditBox skinFilterBox;

    private static final class DraftPool {
        boolean enabled = true;
        boolean duplicateProtection;
        boolean customPool;
        final Map<String, Integer> weights = new LinkedHashMap<>();
    }

    public CrateAdminScreen(Screen parent) {
        this(parent, 0);
    }

    public CrateAdminScreen(Screen parent, int selectedCrate) {
        super(Component.translatable("screen.habitrain_lottery.crate_admin.title"));
        this.parent = parent;
        this.configVersion = CrateClientNetwork.STATE.configVersion;
        definitions.addAll(CrateService.definitions());
        this.selectedCrate = Mth.clamp(selectedCrate, 0, Math.max(0, definitions.size() - 1));
        for (SkinQuality quality : SkinQuality.values()) {
            weeklyCaps.put(quality, defaultWeekly(quality));
            monthlyCaps.put(quality, defaultMonthly(quality));
            weeklyUsed.put(quality, 0);
            monthlyUsed.put(quality, 0);
            weeklyDrafts[quality.ordinal()] = String.valueOf(defaultWeekly(quality));
            monthlyDrafts[quality.ordinal()] = String.valueOf(defaultMonthly(quality));
        }
        ensureDraftDefaults();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        ensureDraftDefaults();
        buildWidgets();
        if (!draftLoaded) CrateClientNetwork.requestConfig();
        refreshFromState();
    }

    private void ensureDraftDefaults() {
        for (CrateService.Definition definition : definitions) {
            pools.computeIfAbsent(definition.id(), ignored -> defaultPool(definition));
        }
    }

    private DraftPool defaultPool(CrateService.Definition definition) {
        DraftPool pool = new DraftPool();
        for (SkinDefinition skin : HabiSkinApi.registrations()) {
            pool.weights.put(key(skin), definition.prism() || definition.primary() == skin.quality() ? 100 : 0);
        }
        return pool;
    }

    private void buildWidgets() {
        editorW = Math.min(600, Math.max(180, width - 16));
        editorX = (width - editorW) / 2;
        footerY = height - 26;
        int controlsY = 84;
        rowsTop = controlsY + 62;
        visibleRows = Mth.clamp((footerY - rowsTop - 8) / ROW_H, 1, ROWS);
        visibleCapRows = Mth.clamp((footerY - 112) / 26, 1, SkinQuality.values().length);
        int third = (editorW - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> selectCrate(selectedCrate - 1))
                .bounds(editorX, 34, 24, 20).build());
        selectedCrateButton = addRenderableWidget(Button.builder(Component.empty(), b -> selectCrate(selectedCrate + 1))
                .bounds(editorX + 28, 34, editorW - 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("›"), b -> selectCrate(selectedCrate + 1))
                .bounds(editorX + editorW - 24, 34, 24, 20).build());
        poolPageButton = addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.crate_admin.pool_title"),
                b -> showPage(false)).bounds(editorX, 58, (editorW - 4) / 2, 20).build());
        quotaPageButton = addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.crate_admin.quotas"),
                b -> showPage(true)).bounds(editorX + (editorW + 4) / 2, 58, (editorW - 4) / 2, 20).build());

        enabledButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleEnabled())
                .bounds(editorX, controlsY, third, 20).build());
        customPoolButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleCustomPool())
                .bounds(editorX + third + 4, controlsY, third, 20).build());
        duplicateButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleDuplicates())
                .bounds(editorX + (third + 4) * 2, controlsY, editorW - (third + 4) * 2, 20).build());

        int filterWidth = Math.min(130, Math.max(70, (editorW - 4) / 2));
        skinFilterBox = addRenderableWidget(new EditBox(font, editorX, controlsY + 24, editorW - filterWidth - 4, 20,
                Component.translatable("screen.habitrain_lottery.crate_admin.skin_filter")));
        skinFilterBox.setMaxLength(64);
        skinFilterBox.setHint(Component.translatable("screen.habitrain_lottery.crate_admin.skin_filter"));
        skinFilterBox.setValue(skinQuery);
        skinFilterBox.setResponder(value -> {
            if (updatingWidgets) return;
            if (!persistVisibleWeights()) return;
            skinQuery = value == null ? "" : value;
            skinScroll = 0;
            refreshSkinRows();
        });
        qualityFilterButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleQualityFilter())
                .bounds(editorX + editorW - filterWidth, controlsY + 24, filterWidth, 20).build());

        for (int i = 0; i < SkinQuality.values().length; i++) {
            final int qualityIndex = i;
            int y = 112 + i * 26;
            weeklyBoxes[i] = addRenderableWidget(new EditBox(font, editorX + editorW - 150, y, 70, 20,
                    Component.translatable("screen.habitrain_lottery.crate_admin.weekly")));
            monthlyBoxes[i] = addRenderableWidget(new EditBox(font, editorX + editorW - 74, y, 70, 20,
                    Component.translatable("screen.habitrain_lottery.crate_admin.monthly")));
            weeklyBoxes[i].setMaxLength(10);
            monthlyBoxes[i].setMaxLength(10);
            weeklyBoxes[i].setValue(weeklyDrafts[i]);
            monthlyBoxes[i].setValue(monthlyDrafts[i]);
            weeklyBoxes[i].setResponder(value -> {
                if (!updatingWidgets) { weeklyDrafts[qualityIndex] = value; dirty = true; }
                updateSaveState();
            });
            monthlyBoxes[i].setResponder(value -> {
                if (!updatingWidgets) { monthlyDrafts[qualityIndex] = value; dirty = true; }
                updateSaveState();
            });
        }

        for (int i = 0; i < ROWS; i++) {
            final int slot = i;
            int y = rowsTop + i * ROW_H;
            rowButtons[i] = addRenderableWidget(Button.builder(Component.empty(), b -> toggleRow(slot))
                    .bounds(editorX, y, editorW - 84, 20).build());
            rowWeights[i] = addRenderableWidget(new EditBox(font, editorX + editorW - 78, y, 78, 20,
                    Component.translatable("screen.habitrain_lottery.crate_admin.weight")));
            rowWeights[i].setMaxLength(10);
            rowWeights[i].setResponder(value -> {
                if (updatingWidgets) return;
                if (rowKeys[slot] != null) {
                    Integer parsed = parseWeight(value);
                    if (parsed != null) {
                        currentPool().weights.put(rowKeys[slot], parsed);
                        dirty = true;
                        refreshProbabilityLabels();
                    }
                    updateSaveState();
                }
            });
        }

        int footerButtonW = (editorW - 8) / 3;
        resetButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
                    if (quotaPage) resetAll(); else resetCurrent();
                }).bounds(editorX, footerY, footerButtonW, 20).build());
        saveButton = addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.crate_admin.save"), b -> saveConfig())
                .bounds(editorX + footerButtonW + 4, footerY, footerButtonW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(editorX + (footerButtonW + 4) * 2, footerY, editorW - (footerButtonW + 4) * 2, 20).build());
        refreshWidgetsFromDraft();
    }

    private void selectCrate(int index) {
        if (!persistVisibleWeights()) return;
        if (!definitions.isEmpty()) {
            selectedCrate = Math.floorMod(index, definitions.size());
            skinScroll = 0;
            refreshWidgetsFromDraft();
        }
    }

    private void showPage(boolean quotas) {
        if (!persistVisibleWeights()) return;
        quotaPage = quotas;
        refreshWidgetsFromDraft();
    }

    private DraftPool currentPool() {
        if (definitions.isEmpty()) return new DraftPool();
        int index = Mth.clamp(selectedCrate, 0, definitions.size() - 1);
        return pools.computeIfAbsent(definitions.get(index).id(), ignored -> defaultPool(definitions.get(index)));
    }

    private CrateService.Definition currentDefinition() {
        return definitions.isEmpty() ? null : definitions.get(Mth.clamp(selectedCrate, 0, definitions.size() - 1));
    }

    private void toggleEnabled() {
        if (!persistVisibleWeights()) return;
        currentPool().enabled = !currentPool().enabled;
        dirty = true;
        refreshWidgetsFromDraft();
    }

    private void toggleCustomPool() {
        if (!persistVisibleWeights()) return;
        DraftPool pool = currentPool();
        pool.customPool = !pool.customPool;
        if (!pool.customPool && currentDefinition() != null) {
            pool.weights.clear();
            pool.weights.putAll(defaultPool(currentDefinition()).weights);
        }
        dirty = true;
        refreshWidgetsFromDraft();
    }

    private void toggleDuplicates() {
        if (!persistVisibleWeights()) return;
        currentPool().duplicateProtection = !currentPool().duplicateProtection;
        dirty = true;
        refreshWidgetsFromDraft();
    }

    private void cycleQualityFilter() {
        if (!persistVisibleWeights()) return;
        qualityFilter++;
        if (qualityFilter >= SkinQuality.values().length) qualityFilter = -1;
        skinScroll = 0;
        refreshSkinRows();
    }

    private void resetCurrent() {
        CrateService.Definition definition = currentDefinition();
        if (definition != null) pools.put(definition.id(), defaultPool(definition));
        dirty = true;
        status = "screen.habitrain_lottery.crate_admin.reset_done";
        refreshWidgetsFromDraft();
    }

    private void resetAll() {
        for (CrateService.Definition definition : definitions) pools.put(definition.id(), defaultPool(definition));
        for (SkinQuality quality : SkinQuality.values()) {
            weeklyCaps.put(quality, defaultWeekly(quality));
            monthlyCaps.put(quality, defaultMonthly(quality));
        }
        syncCapBoxes();
        dirty = true;
        status = "screen.habitrain_lottery.crate_admin.reset_done";
        refreshWidgetsFromDraft();
    }

    public void refreshFromState() {
        if (CrateClientNetwork.STATE.configVersion <= 0
                || configVersion == CrateClientNetwork.STATE.configVersion) return;
        configVersion = CrateClientNetwork.STATE.configVersion;
        String message = CrateClientNetwork.STATE.configMessage;
        if (savePending) {
            savePending = false;
            savePendingTicks = 0;
            if (!"crates.config_saved".equals(message)) {
                status = message.isBlank() ? "crates.config_failed" : message;
                refreshWidgetsFromDraft();
                return;
            }
            dirty = false;
        } else if (dirty) {
            status = "screen.habitrain_lottery.crate_admin.unsaved_refresh";
            return;
        }
        String json = CrateClientNetwork.STATE.configJson;
        try {
            JsonObject root = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json).getAsJsonObject();
            readCaps(root);
            readPools(root);
            draftLoaded = true;
            syncCapBoxes();
            refreshWidgetsFromDraft();
        } catch (RuntimeException error) {
            status = "screen.habitrain_lottery.crate_admin.invalid";
        }
        if (!message.isBlank()) status = message;
    }

    private void readCaps(JsonObject root) {
        readCapMap(root, "weeklyCaps", weeklyCaps);
        readCapMap(root, "monthlyCaps", monthlyCaps);
        readCapMap(root, "weeklyUsed", weeklyUsed);
        readCapMap(root, "monthlyUsed", monthlyUsed);
    }

    private void readCapMap(JsonObject root, String name, EnumMap<SkinQuality, Integer> target) {
        JsonObject map = root.has(name) && root.get(name).isJsonObject() ? root.getAsJsonObject(name) : null;
        if (map == null) return;
        for (SkinQuality quality : SkinQuality.values()) {
            if (!map.has(quality.id())) continue;
            try { target.put(quality, Math.max(0, map.get(quality.id()).getAsInt())); }
            catch (RuntimeException ignored) { }
        }
    }

    private void readPools(JsonObject root) {
        ensureDraftDefaults();
        if (!root.has("pools") || !root.get("pools").isJsonObject()) return;
        JsonObject all = root.getAsJsonObject("pools");
        for (CrateService.Definition definition : definitions) {
            DraftPool pool = defaultPool(definition);
            if (all.has(definition.id()) && all.get(definition.id()).isJsonObject()) {
                JsonObject raw = all.getAsJsonObject(definition.id());
                pool.enabled = !raw.has("enabled") || raw.get("enabled").getAsBoolean();
                pool.customPool = raw.has("customPool") && raw.get("customPool").getAsBoolean();
                pool.duplicateProtection = raw.has("duplicateProtection") && raw.get("duplicateProtection").getAsBoolean();
                if (raw.has("skinWeights") && raw.get("skinWeights").isJsonObject()) {
                    pool.weights.clear();
                    for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject("skinWeights").entrySet()) {
                        try { pool.weights.put(entry.getKey().toLowerCase(Locale.ROOT), Math.max(0, entry.getValue().getAsInt())); }
                        catch (RuntimeException ignored) { }
                    }
                }
            }
            pools.put(definition.id(), pool);
        }
    }

    private void saveConfig() {
        if (!persistVisibleWeights()) return;
        if (!CrateClientNetwork.connected()) { status = "screen.habitrain_lottery.crate_admin.offline"; return; }
        if (!LotteryNetwork.ClientLotteryState.op) { status = "crates.no_permission"; return; }
        JsonObject root = new JsonObject();
        JsonObject weekly = new JsonObject();
        JsonObject monthly = new JsonObject();
        try {
            for (SkinQuality quality : SkinQuality.values()) {
                int week = parseRequired(weeklyBoxes[quality.ordinal()]);
                int month = parseRequired(monthlyBoxes[quality.ordinal()]);
                if (week < 0 || month < 0 || week > 1_000_000_000 || month > 1_000_000_000) throw new IllegalArgumentException();
                weekly.addProperty(quality.id(), week);
                monthly.addProperty(quality.id(), month);
            }
            root.add("weeklyCaps", weekly);
            root.add("monthlyCaps", monthly);
            JsonObject all = new JsonObject();
            for (CrateService.Definition definition : definitions) {
                DraftPool pool = pools.get(definition.id());
                if (pool == null) pool = defaultPool(definition);
                JsonObject out = new JsonObject();
                out.addProperty("enabled", pool.enabled);
                out.addProperty("customPool", pool.customPool);
                out.addProperty("duplicateProtection", pool.duplicateProtection);
                JsonObject weights = new JsonObject();
                long total = 0;
                for (Map.Entry<String, Integer> entry : pool.weights.entrySet()) {
                    int value = entry.getValue() == null ? 0 : entry.getValue();
                    if (value < 0 || value > 1_000_000_000) throw new IllegalArgumentException();
                    total += value;
                    if (total > 4_000_000_000L) throw new IllegalArgumentException();
                    weights.addProperty(entry.getKey(), value);
                }
                if (pool.customPool && pool.enabled && total <= 0) throw new IllegalArgumentException();
                out.add("skinWeights", weights);
                all.add(definition.id(), out);
            }
            root.add("pools", all);
            savePending = true;
            savePendingTicks = 0;
            CrateClientNetwork.saveConfig(GSON.toJson(root));
            status = "screen.habitrain_lottery.crate_admin.saving";
            refreshWidgetsFromDraft();
        } catch (RuntimeException error) {
            status = "screen.habitrain_lottery.crate_admin.invalid";
        }
    }

    private void refreshWidgetsFromDraft() {
        if (enabledButton == null) return;
        DraftPool pool = currentPool();
        selectedCrateButton.setMessage(crateLabel(selectedCrate));
        enabledButton.setMessage(Component.translatable(pool.enabled
                ? "screen.habitrain_lottery.crate_admin.enabled"
                : "screen.habitrain_lottery.crate_admin.disabled"));
        customPoolButton.setMessage(Component.translatable(pool.customPool
                ? "screen.habitrain_lottery.crate_admin.custom_pool"
                : "screen.habitrain_lottery.crate_admin.default_pool"));
        duplicateButton.setMessage(Component.translatable(pool.duplicateProtection
                ? "screen.habitrain_lottery.crate_admin.no_duplicates"
                : "screen.habitrain_lottery.crate_admin.allow_duplicates"));
        enabledButton.setTooltip(Tooltip.create(enabledButton.getMessage()));
        customPoolButton.setTooltip(Tooltip.create(customPoolButton.getMessage()));
        duplicateButton.setTooltip(Tooltip.create(duplicateButton.getMessage()));
        boolean editable = draftLoaded && !savePending && CrateClientNetwork.connected() && LotteryNetwork.ClientLotteryState.op
                && !MenuAccessBridge.isLocked();
        enabledButton.visible = customPoolButton.visible = duplicateButton.visible = !quotaPage;
        skinFilterBox.visible = qualityFilterButton.visible = !quotaPage;
        for (int i = 0; i < SkinQuality.values().length; i++) {
            boolean visible = quotaPage && i >= capScroll && i < capScroll + visibleCapRows;
            weeklyBoxes[i].visible = monthlyBoxes[i].visible = visible;
            weeklyBoxes[i].setY(112 + (i - capScroll) * 26);
            monthlyBoxes[i].setY(112 + (i - capScroll) * 26);
            weeklyBoxes[i].active = monthlyBoxes[i].active = editable;
        }
        enabledButton.active = customPoolButton.active = duplicateButton.active = editable;
        resetButton.active = editable;
        resetButton.setMessage(Component.translatable(quotaPage
                ? "screen.habitrain_lottery.crate_admin.reset_all"
                : "screen.habitrain_lottery.crate_admin.reset_current"));
        poolPageButton.active = quotaPage;
        quotaPageButton.active = !quotaPage;
        refreshSkinRows();
        updateSaveState();
    }

    private void syncCapBoxes() {
        updatingWidgets = true;
        try {
            for (int i = 0; i < SkinQuality.values().length; i++) {
                weeklyDrafts[i] = String.valueOf(weeklyCaps.getOrDefault(SkinQuality.values()[i], 0));
                monthlyDrafts[i] = String.valueOf(monthlyCaps.getOrDefault(SkinQuality.values()[i], 0));
                weeklyBoxes[i].setValue(weeklyDrafts[i]);
                monthlyBoxes[i].setValue(monthlyDrafts[i]);
            }
        } finally {
            updatingWidgets = false;
        }
    }

    private void updateSaveState() {
        if (saveButton == null) return;
        boolean valid = true;
        for (int i = 0; i < SkinQuality.values().length; i++) {
            valid &= validCap(weeklyBoxes[i]) && validCap(monthlyBoxes[i]);
        }
        for (int i = 0; i < visibleRows; i++) {
            if (rowKeys[i] != null) valid &= parseWeight(rowWeights[i].getValue()) != null;
        }
        for (CrateService.Definition definition : definitions) {
            DraftPool pool = pools.get(definition.id());
            if (pool == null || !pool.customPool) continue;
            long total = 0;
            for (Integer value : pool.weights.values()) {
                if (value == null || value < 0 || value > 1_000_000_000) valid = false;
            }
            for (SkinDefinition skin : HabiSkinApi.registrations()) {
                total += Math.max(0, pool.weights.getOrDefault(key(skin), 0));
            }
            if (total > 4_000_000_000L || (pool.enabled && total == 0)) valid = false;
        }
        saveButton.active = valid && dirty && draftLoaded && !savePending
                && CrateClientNetwork.connected() && LotteryNetwork.ClientLotteryState.op
                && !MenuAccessBridge.isLocked();
    }

    private static boolean validCap(EditBox box) {
        try {
            int value = Integer.parseInt(box.getValue().trim());
            return value >= 0 && value <= 1_000_000_000;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void refreshSkinRows() {
        if (qualityFilterButton == null) return;
        qualityFilterButton.setMessage(qualityFilter < 0
                ? Component.translatable("screen.habitrain_lottery.crate_admin.filter_all")
                : Component.translatable("screen.habitrain_lottery.crate_admin.filter_quality",
                Component.translatable(SkinQuality.values()[qualityFilter].translationKey())));
        visibleSkins.clear();
        String query = skinQuery == null ? "" : skinQuery.trim().toLowerCase(Locale.ROOT);
        for (SkinDefinition skin : HabiSkinApi.registrations()) {
            if (qualityFilter >= 0 && skin.quality() != SkinQuality.values()[qualityFilter]) continue;
            String label = key(skin).toLowerCase(Locale.ROOT);
            if (!query.isBlank() && !label.contains(query)) continue;
            visibleSkins.add(skin);
        }
        skinScroll = Mth.clamp(skinScroll, 0, Math.max(0, visibleSkins.size() - visibleRows));
        DraftPool pool = currentPool();
        updatingWidgets = true;
        for (int slot = 0; slot < ROWS; slot++) {
            int index = skinScroll + slot;
            if (slot < visibleRows && index < visibleSkins.size()) {
                SkinDefinition skin = visibleSkins.get(index);
                String skinKey = key(skin);
                rowKeys[slot] = skinKey;
                int weight = pool.weights.getOrDefault(skinKey, 0);
                rowButtons[slot].visible = !quotaPage;
                rowWeights[slot].visible = !quotaPage;
                rowButtons[slot].active = draftLoaded && !savePending
                        && CrateClientNetwork.connected() && LotteryNetwork.ClientLotteryState.op
                        && !MenuAccessBridge.isLocked();
                rowWeights[slot].active = rowButtons[slot].active && pool.customPool;
                rowWeights[slot].setValue(String.valueOf(weight));
            } else {
                rowKeys[slot] = null;
                rowButtons[slot].visible = false;
                rowWeights[slot].visible = false;
            }
        }
        updatingWidgets = false;
        refreshProbabilityLabels();
        updateSaveState();
    }

    private void refreshProbabilityLabels() {
        if (rowButtons[0] == null) return;
        DraftPool pool = currentPool();
        long total = 0;
        for (SkinDefinition skin : HabiSkinApi.registrations()) {
            total += Math.max(0, pool.weights.getOrDefault(key(skin), 0));
        }
        for (int slot = 0; slot < visibleRows; slot++) {
            String skinKey = rowKeys[slot];
            if (skinKey == null) continue;
            int weight = pool.weights.getOrDefault(skinKey, 0);
            String probability = total <= 0 || weight <= 0 ? "0.00%"
                    : String.format(Locale.ROOT, "%.2f%%", weight * 100.0D / total);
            String name = font.plainSubstrByWidth(skinKey, Math.max(30, editorW - 170));
            rowButtons[slot].setMessage(Component.literal((weight > 0 ? "✓ " : "○ ") + name + "  " + probability));
            rowButtons[slot].setTooltip(Tooltip.create(Component.literal(skinKey + "  " + probability)));
        }
    }

    private boolean persistVisibleWeights() {
        DraftPool pool = currentPool();
        for (int slot = 0; slot < ROWS; slot++) {
            if (rowKeys[slot] == null || rowWeights[slot] == null) continue;
            Integer value = parseWeight(rowWeights[slot].getValue());
            if (value == null) {
                status = "screen.habitrain_lottery.crate_admin.invalid";
                return false;
            }
            pool.weights.put(rowKeys[slot], value);
        }
        return true;
    }

    private void toggleRow(int slot) {
        if (!persistVisibleWeights()) return;
        String key = slot >= 0 && slot < ROWS ? rowKeys[slot] : null;
        if (key == null) return;
        DraftPool pool = currentPool();
        pool.customPool = true;
        int value = pool.weights.getOrDefault(key, 0);
        pool.weights.put(key, value > 0 ? 0 : 100);
        dirty = true;
        refreshWidgetsFromDraft();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= editorX && mouseX < editorX + editorW && mouseY >= 108 && mouseY < footerY) {
            if (quotaPage) {
                capScroll = Mth.clamp(capScroll + (verticalAmount < 0 ? 1 : -1), 0,
                        Math.max(0, SkinQuality.values().length - visibleCapRows));
                refreshWidgetsFromDraft();
            } else if (persistVisibleWeights()) {
                skinScroll += verticalAmount < 0 ? 1 : -1;
                refreshSkinRows();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public void tick() {
        super.tick();
        refreshFromState();
        if (savePending && ++savePendingTicks > 200) {
            savePending = false;
            status = "screen.habitrain_lottery.crate_admin.timeout";
            refreshWidgetsFromDraft();
        }
        updateSaveState();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!suppressNestedBackground) super.renderBackground(g, mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g, mouseX, mouseY, delta);
        g.fill(editorX - 4, 4, editorX + editorW + 4, footerY - 2, 0xE51B242C);
        g.fill(editorX - 4, 4, editorX + editorW + 4, 7, 0xFF57C6D6);
        g.drawCenteredString(font, getTitle(), width / 2, 14, 0xFFFFFFFF);
        String subtitle = Component.translatable("screen.habitrain_lottery.crate_admin.subtitle_full").getString();
        g.drawCenteredString(font, font.plainSubstrByWidth(subtitle, editorW), width / 2, 25, 0xFFAEBBC1);
        CrateService.Definition definition = currentDefinition();
        DraftPool pool = currentPool();
        if (definition != null && !quotaPage) {
            g.drawString(font, Component.translatable("screen.habitrain_lottery.crate_admin.key_label",
                    CrateService.keyItemId(definition.id())), editorX, 128, 0xFF8A979D, false);
            long total = pool.weights.values().stream().filter(v -> v != null && v > 0).mapToLong(Integer::longValue).sum();
            int active = (int) pool.weights.values().stream().filter(v -> v != null && v > 0).count();
            Component summary = pool.customPool && pool.enabled && total == 0
                    ? Component.translatable("screen.habitrain_lottery.crate_admin.empty_pool")
                    : Component.translatable("screen.habitrain_lottery.crate_admin.pool_summary", active, total);
            g.drawString(font, font.plainSubstrByWidth(summary.getString(), editorW), editorX, footerY - 14, 0xFFD4A55A, false);
        } else if (quotaPage) {
            g.drawString(font, Component.translatable("screen.habitrain_lottery.crate_admin.quality"), editorX, 96, 0xFFD4A55A, false);
            g.drawString(font, Component.translatable("screen.habitrain_lottery.crate_admin.weekly"), editorX + editorW - 150, 96, 0xFF8AD0D9, false);
            g.drawString(font, Component.translatable("screen.habitrain_lottery.crate_admin.monthly"), editorX + editorW - 74, 96, 0xFFE0B56D, false);
            for (int i = capScroll; i < Math.min(SkinQuality.values().length, capScroll + visibleCapRows); i++) {
                SkinQuality quality = SkinQuality.values()[i];
                int y = 112 + (i - capScroll) * 26;
                g.drawString(font, Component.translatable(quality.translationKey()), editorX, y + 5, quality.color(), false);
                int weekRemain = Math.max(0, weeklyCaps.getOrDefault(quality, 0) - weeklyUsed.getOrDefault(quality, 0));
                int monthRemain = Math.max(0, monthlyCaps.getOrDefault(quality, 0) - monthlyUsed.getOrDefault(quality, 0));
                g.drawString(font, weekRemain + " / " + monthRemain, editorX + 74, y + 5, 0xFFB7C1C6, false);
            }
        }
        if (!status.isBlank()) {
            Component text = status.startsWith("screen.") || status.startsWith("crates.")
                    ? Component.translatable(status) : Component.literal(status);
            g.drawCenteredString(font, font.plainSubstrByWidth(text.getString(), editorW), width / 2, footerY - 14, 0xFFD4A55A);
        } else if (!LotteryNetwork.ClientLotteryState.op) {
            g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate_admin.readonly"), width / 2, footerY - 14, 0xFFFF6B6B);
        }
        suppressNestedBackground = true;
        try { super.render(g, mouseX, mouseY, delta); }
        finally { suppressNestedBackground = false; }
    }

    private Component crateLabel(int index) {
        if (index < 0 || index >= definitions.size()) return Component.empty();
        CrateService.Definition definition = definitions.get(index);
        return Component.translatable(definition.nameKey());
    }

    private static String key(SkinDefinition skin) { return skin.type() + "/" + skin.id(); }

    private static Integer parseWeight(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed >= 0 && parsed <= 1_000_000_000 ? parsed : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static int parseRequired(EditBox box) { return Integer.parseInt(box.getValue().trim()); }
    private static int defaultWeekly(SkinQuality q) { return switch (q) { case WHITE -> 2000; case BLUE -> 1000; case PURPLE -> 500; case GOLD -> 160; case RED -> 40; }; }
    private static int defaultMonthly(SkinQuality q) { return switch (q) { case WHITE -> 8000; case BLUE -> 4000; case PURPLE -> 2000; case GOLD -> 640; case RED -> 160; }; }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
