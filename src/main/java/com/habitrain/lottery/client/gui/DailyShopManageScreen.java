package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.client.DailyShopClient;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.daily.shop.DailyShopConfig;
import com.habitrain.lottery.daily.shop.DailyShopItem;
import com.habitrain.lottery.daily.shop.DailyShopService;
import com.habitrain.lottery.network.DailyShopNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Mod Menu editor for the daily-terminal shop: listings on the left; for the selected listing one
 * scrolling form with price and purchase limit, the goods sold, and its display text.
 *
 * <p>Same draft / save / revision contract as {@link DailyTaskManageScreen}: nothing reaches the
 * server until "保存", and the server re-validates and rejects stale drafts.</p>
 */
public final class DailyShopManageScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.daily_shop_admin.";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int ROW = 24;
    private static final int LIST_ROW = 26;
    private static final int PANEL = 0xC0141D25, NAV = 0xE512171E, LINE = 0x5057C6D6, ACCENT = 0xFF57C6D6;
    private static final int GOLD = 0xFFD4A55A, TEXT = 0xFFE6EDF2, MUTED = 0xFF8A92A0, GOOD = 0xFF65D18A, BAD = 0xFFFF6B6B;

    private final Screen parent;
    private DailyShopConfig draft = DailyShopConfig.defaults();
    private String baseline = "";
    private boolean loaded, serverWritable, noPermission, pending, dirty;
    private int pendingTicks, seenVersion = -1;
    private int selected, listScroll, formScroll;
    private String status = "";
    private int statusColor = MUTED;
    private boolean suppressNestedBackground;

    private int m, bodyTop, bodyBottom, footerY, listX, listW, listTop, listBottom, rx, rw, formTop, formBottom;
    private boolean narrow;
    private int formY, formHeight;
    private final List<FormLabel> labels = new ArrayList<>();
    private Button saveButton;

    /** {@code kind}: 0 field label, 1 section heading, 2 muted note line. */
    private record FormLabel(Component text, int vy, int kind, Component tooltip) { }

    public DailyShopManageScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
        draft.normalize(DailyTaskConfig.Checks.LENIENT);
        baseline = GSON.toJson(draft);
    }

    static MutableComponent tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    static String s(String key, Object... args) {
        return tr(key, args).getString();
    }

    /** Shop-specific validation codes first; shared ones (id, title, rewards …) reuse the task editor text. */
    static String error(String code) {
        if (code == null || code.isEmpty()) return "";
        int colon = code.indexOf(':');
        String head = colon < 0 ? code : code.substring(0, colon);
        String detail = colon < 0 ? "" : code.substring(colon + 1);
        return Language.getInstance().has(KEY + "error." + head) ? s("error." + head, detail)
                : DailyTaskUiText.error(code);
    }

    // ------------------------------------------------------------------ state

    @Override
    protected void init() {
        if (!loaded && DailyShopClient.connected()) {
            if (seenVersion == -1) {
                seenVersion = DailyShopClient.version;
                DailyShopClient.request();
                setStatus(DailyTaskUiText.s("status.loading"), MUTED);
            }
        } else if (!DailyShopClient.connected()) {
            setStatus(DailyTaskUiText.s("status.offline"), GOLD);
        }
        rebuild();
    }

    /** Called by the network receiver on the client thread. */
    public void onServerUpdate() {
        seenVersion = DailyShopClient.version;
        String message = DailyShopClient.message;
        if (DailyShopNetwork.NO_PERMISSION.equals(message)) {
            noPermission = true;
            pending = false;
            setStatus(DailyTaskUiText.s("status.no_permission"), BAD);
            rebuild();
            return;
        }
        DailyShopService.Snapshot snapshot;
        try {
            snapshot = GSON.fromJson(DailyShopClient.json, DailyShopService.Snapshot.class);
        } catch (RuntimeException malformed) {
            snapshot = null;
        }
        if (snapshot == null || snapshot.config() == null) return;
        boolean ours = pending;
        pending = false;
        if (DailyShopNetwork.SAVED.equals(message) || !dirty || !loaded) {
            adopt(snapshot);
            if (DailyShopNetwork.SAVED.equals(message)) setStatus(DailyTaskUiText.s("status.saved"), GOOD);
            else if (!serverWritable) setStatus(DailyTaskUiText.s("status.not_writable"), BAD);
            else if (message.isEmpty() && !ours) setStatus(s("status.loaded", draft.items.size()), MUTED);
        } else if (message.isEmpty()) {
            setStatus(DailyTaskUiText.s("status.remote_changed"), GOLD);
        }
        if (ours && !message.isEmpty() && !DailyShopNetwork.SAVED.equals(message)) {
            setStatus(DailyTaskUiText.s("status.save_failed", error(message)), BAD);
            selectByErrorDetail(message);
        }
        rebuild();
    }

    private void adopt(DailyShopService.Snapshot snapshot) {
        String keep = current() == null ? null : current().id;
        draft = snapshot.config();
        serverWritable = snapshot.writable();
        baseline = GSON.toJson(draft);
        loaded = true;
        dirty = false;
        selected = 0;
        for (int i = 0; keep != null && i < draft.items.size(); i++) if (keep.equals(draft.items.get(i).id)) selected = i;
    }

    private boolean editable() {
        return loaded && serverWritable && !noPermission && !pending && DailyShopClient.connected();
    }

    private DailyShopItem current() {
        if (draft == null || draft.items == null || draft.items.isEmpty()) return null;
        selected = Mth.clamp(selected, 0, draft.items.size() - 1);
        return draft.items.get(selected);
    }

    private void touch() {
        for (DailyShopItem item : draft.items) {
            if (item.autoRewardLabel) item.rewardLabel = DailyTaskUiText.rewardLabel(item.rewards);
        }
        dirty = !GSON.toJson(draft).equals(baseline);
        if (saveButton != null) saveButton.active = editable() && dirty;
    }

    private void setStatus(String text, int color) {
        status = text == null ? "" : text;
        statusColor = color;
    }

    @Override
    public void tick() {
        super.tick();
        if (pending && ++pendingTicks > 200) {
            pending = false;
            setStatus(DailyTaskUiText.s("status.timeout"), BAD);
            rebuild();
        }
        if (!loaded && seenVersion == -1 && DailyShopClient.connected()) {
            seenVersion = DailyShopClient.version;
            DailyShopClient.request();
        }
    }

    // ------------------------------------------------------------------ layout

    private void layout() {
        m = width < 480 ? 8 : 16;
        bodyTop = 34;
        footerY = height - 24;
        bodyBottom = footerY - 16;
        narrow = width < 520;
        if (narrow) {
            listW = 0;
            rx = m;
        } else {
            listX = m;
            listW = Mth.clamp(width * 3 / 10, 150, 220);
            rx = listX + listW + 10;
        }
        rw = width - m - rx;
        listTop = bodyTop + 16;
        listBottom = bodyBottom - 3 * 24;
        int rightTop = narrow ? bodyTop + 24 : bodyTop;
        formTop = rightTop + 46;
        formBottom = bodyBottom;
    }

    private void rebuild() {
        clearWidgets();
        labels.clear();
        layout();
        touch();
        if (!narrow) buildList();
        else buildNarrowSelector();
        buildForm();
        buildFooter();
        applyLocks();
    }

    private void applyLocks() {
        if (editable()) return;
        for (var child : children()) {
            if (child instanceof AbstractWidget w && !(w instanceof ItemRow) && w.getMessage() != null) {
                String msg = w.getMessage().getString();
                if (msg.equals(DailyTaskUiText.s("action.back")) || msg.equals(DailyTaskUiText.s("action.reload"))
                        || msg.equals("‹") || msg.equals("›")) continue;
                w.active = false;
            }
        }
    }

    private void buildList() {
        int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, draft.items.size() - rows));
        for (int i = listScroll; i < Math.min(draft.items.size(), listScroll + rows); i++) {
            addRenderableWidget(new ItemRow(i, listX, listTop + (i - listScroll) * LIST_ROW, listW - 6));
        }
        int y = listBottom + 4;
        int third = (listW - 8) / 3;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.new"), b -> addItem(null))
                .bounds(listX, y, third, 20).tooltip(Tooltip.create(tr("action.new_hint"))).build())
                .active = draft.items.size() < DailyShopConfig.MAX_ITEMS;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.copy"), b -> addItem(current()))
                .bounds(listX + third + 4, y, third, 20).tooltip(Tooltip.create(tr("action.copy_hint"))).build())
                .active = current() != null && draft.items.size() < DailyShopConfig.MAX_ITEMS;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.delete"), b -> confirmDelete())
                .bounds(listX + (third + 4) * 2, y, listW - (third + 4) * 2, 20)
                .tooltip(Tooltip.create(tr("action.delete_hint"))).build()).active = current() != null;
        y += 24;
        int half = (listW - 4) / 2;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.up"), b -> move(-1)).bounds(listX, y, half, 20)
                .tooltip(Tooltip.create(tr("action.order_hint"))).build()).active = selected > 0;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.down"), b -> move(1)).bounds(listX + half + 4, y, listW - half - 4, 20)
                .tooltip(Tooltip.create(tr("action.order_hint"))).build()).active = selected < draft.items.size() - 1;
        y += 24;
        addRenderableWidget(shopSwitch(listX, y, listW));
    }

    private Button shopSwitch(int x, int y, int w) {
        return Button.builder(tr(draft.enabled ? "shop.on" : "shop.off"), b -> {
            draft.enabled = !draft.enabled;
            rebuild();
        }).bounds(x, y, w, 20).tooltip(Tooltip.create(tr("shop.toggle_hint"))).build();
    }

    private void buildNarrowSelector() {
        int y = bodyTop;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { selected = Math.floorMod(selected - 1, Math.max(1, draft.items.size())); formScroll = 0; rebuild(); })
                .bounds(rx, y, 20, 20).build()).active = draft.items.size() > 1;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { selected = (selected + 1) % Math.max(1, draft.items.size()); formScroll = 0; rebuild(); })
                .bounds(rx + 24, y, 20, 20).build()).active = draft.items.size() > 1;
        int bw = Math.max(40, (rw - 52) / 3);
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.new"), b -> addItem(null)).bounds(rx + 52, y, bw - 2, 20).build())
                .active = draft.items.size() < DailyShopConfig.MAX_ITEMS;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.copy"), b -> addItem(current())).bounds(rx + 52 + bw, y, bw - 2, 20).build())
                .active = current() != null && draft.items.size() < DailyShopConfig.MAX_ITEMS;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.delete"), b -> confirmDelete()).bounds(rx + 52 + bw * 2, y, rw - 52 - bw * 2, 20).build())
                .active = current() != null;
    }

    private void buildFooter() {
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.back"), b -> onClose()).bounds(m, footerY, 60, 20).build());
        int saveW = 90;
        saveButton = addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.save"), b -> save())
                .bounds(width - m - saveW, footerY, saveW, 20)
                .tooltip(Tooltip.create(tr("action.save_hint"))).build());
        saveButton.active = editable() && dirty;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.reload"), b -> reload())
                .bounds(width - m - saveW - 4 - 80, footerY, 80, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.reload_hint"))).build()).active = DailyShopClient.connected() && !pending;
    }

    // ------------------------------------------------------------------ form

    private int labelW() {
        return Math.min(118, rw * 36 / 100);
    }

    private int cx() {
        return rx + labelW();
    }

    private int cw() {
        return rw - labelW() - 8;
    }

    private int fy() {
        return formTop + formY - formScroll;
    }

    private void heading(String key) {
        if (formY > 0) formY += 4;
        labels.add(new FormLabel(tr(key), formY, 1, null));
        formY += 14;
    }

    private void note(Component text) {
        for (var line : font.split(text, rw - 8)) {
            StringBuilder b = new StringBuilder();
            line.accept((i, style, cp) -> { b.appendCodePoint(cp); return true; });
            labels.add(new FormLabel(Component.literal(b.toString()), formY, 2, null));
            formY += 11;
        }
        formY += 4;
    }

    private void label(String key, String hintKey) {
        labels.add(new FormLabel(tr(key), formY, 0, hintKey == null ? null : tr(hintKey)));
    }

    private <T extends AbstractWidget> T place(T widget) {
        int y = widget.getY();
        widget.visible = y >= formTop && y + widget.getHeight() <= formBottom;
        return addRenderableWidget(widget);
    }

    private void toggleRow(String key, String hint, boolean value, Consumer<Boolean> set) {
        label(key, hint);
        place(Button.builder(DailyTaskUiText.tr(value ? "on" : "off"), b -> { set.accept(!value); rebuild(); })
                .bounds(cx(), fy(), Math.min(120, cw()), 20).tooltip(Tooltip.create(tr(hint))).build());
        formY += ROW;
    }

    private EditBox textRow(String key, String hint, String value, int max, Consumer<String> set) {
        label(key, hint);
        EditBox box = place(new EditBox(font, cx(), fy(), cw(), 20, tr(key)));
        box.setMaxLength(max);
        box.setValue(value == null ? "" : value);
        box.setTooltip(Tooltip.create(tr(hint)));
        box.setResponder(v -> { set.accept(v); touch(); });
        formY += ROW;
        return box;
    }

    /** Integer field; {@code unit} is drawn to its right. Out-of-range input turns red and is caught on save. */
    private EditBox numberRow(String key, String hint, int value, int min, int max, String unit, IntConsumer set) {
        label(key, hint);
        EditBox box = place(new EditBox(font, cx(), fy(), Math.min(80, cw()), 20, tr(key)));
        box.setMaxLength(String.valueOf(max).length());
        box.setFilter(v -> v.matches("\\d*"));
        box.setValue(String.valueOf(value));
        box.setTooltip(Tooltip.create(tr(hint)));
        box.setResponder(v -> {
            int parsed;
            try {
                parsed = Integer.parseInt(v.trim());
            } catch (NumberFormatException invalid) {
                parsed = -1;
            }
            box.setTextColor(parsed >= min && parsed <= max ? 0xFFE0E0E0 : BAD);
            set.accept(parsed);
            touch();
        });
        if (unit != null) labels.add(new FormLabel(Component.literal(unit), formY, 3, null));
        formY += ROW;
        return box;
    }

    private void buildForm() {
        DailyShopItem item = current();
        formY = 0;
        if (item != null) {
            heading("section.price");
            numberRow("field.price", "field.price_hint", item.price, 0, DailyShopConfig.MAX_PRICE,
                    s("unit.apples"), v -> item.price = v);
            toggleRow("field.limit", "field.limit_hint", item.limitEnabled, v -> item.limitEnabled = v);
            EditBox count = numberRow("field.limit_count", "field.limit_count_hint", item.limitCount, 1,
                    DailyShopConfig.MAX_LIMIT_COUNT, s("unit.times"), v -> item.limitCount = v);
            EditBox days = numberRow("field.limit_days", "field.limit_days_hint", item.limitDays, 0,
                    DailyShopConfig.MAX_LIMIT_DAYS, s("unit.days"), v -> item.limitDays = v);
            count.active = days.active = item.limitEnabled;
            count.setEditable(item.limitEnabled);
            days.setEditable(item.limitEnabled);
            note(limitSummary(item));

            heading("section.goods");
            buildRewards(item);

            heading("section.basics");
            textRow("field.title", "field.title_hint", item.title, 32, v -> item.title = v);
            EditBox id = textRow("field.id", "field.id_hint", item.id, 40, v -> item.id = v.trim().toLowerCase(java.util.Locale.ROOT));
            id.setFilter(v -> v.matches("[a-zA-Z0-9_]*"));
            textRow("field.description", "field.description_hint", item.description, 120, v -> item.description = v);
            toggleRow("field.enabled", "field.enabled_hint", item.enabled, v -> item.enabled = v);
        }
        if (narrow) {
            heading("section.shop");
            place(shopSwitch(rx, fy(), Math.min(200, rw)));
            formY += ROW;
        }
        formHeight = formY;
        int max = Math.max(0, formHeight - (formBottom - formTop));
        if (formScroll > max) {
            formScroll = max;
            rebuild();
        }
    }

    private Component limitSummary(DailyShopItem item) {
        if (!item.limitEnabled) return tr("limit.summary_off");
        if (item.limitDays <= 0) return tr("limit.summary_forever", item.limitCount);
        if (item.limitDays == 1) return tr("limit.summary_daily", item.limitCount);
        if (item.limitDays % 7 == 0) return tr("limit.summary_weeks", item.limitDays, item.limitCount);
        return tr("limit.summary_days", item.limitDays, item.limitCount);
    }

    private void buildRewards(DailyShopItem item) {
        note(tr("goods_note"));
        int kindW = Math.min(96, rw / 4);
        int amountW = 52;
        int delW = 20;
        int idW = rw - kindW - amountW - delW - 12;
        for (int i = 0; i < item.rewards.size(); i++) {
            DailyRewardEntry reward = item.rewards.get(i);
            int index = i;
            place(Button.builder(Component.literal(DailyTaskUiText.s("reward.kind." + reward.kind) + " ⟳"), b -> {
                cycleKind(reward);
                rebuild();
            }).bounds(rx, fy(), kindW, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("reward.kind_hint"))).build());
            buildRewardId(reward, rx + kindW + 4, idW);
            EditBox amount = place(new EditBox(font, rx + kindW + 4 + idW + 4, fy(), amountW, 20, DailyTaskUiText.tr("reward.amount")));
            amount.setMaxLength(6);
            amount.setValue(String.valueOf(reward.amount));
            amount.setTooltip(Tooltip.create(DailyTaskUiText.tr(DailyRewardEntry.isUnique(reward.kind)
                    ? "reward.amount_unique" : "reward.amount_hint", DailyTaskConfig.MAX_REWARD_AMOUNT)));
            amount.active = !DailyRewardEntry.isUnique(reward.kind);
            amount.setResponder(v -> {
                try {
                    reward.amount = Integer.parseInt(v.trim());
                } catch (NumberFormatException invalid) {
                    reward.amount = 0;
                }
                amount.setTextColor(reward.amount >= 1 && reward.amount <= DailyTaskConfig.MAX_REWARD_AMOUNT ? 0xFFE0E0E0 : BAD);
                touch();
            });
            place(Button.builder(Component.literal("✕"), b -> { item.rewards.remove(index); rebuild(); })
                    .bounds(rx + rw - delW, fy(), delW, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("reward.remove")))
                    .createNarration(n -> DailyTaskUiText.tr("reward.remove").copy()).build());
            formY += ROW;
        }
        if (item.rewards.isEmpty()) note(tr("goods_empty"));
        place(Button.builder(tr("goods_add"), b -> {
            item.rewards.add(new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
            rebuild();
        }).bounds(rx, fy(), Math.min(160, rw), 20)
                .tooltip(Tooltip.create(tr("goods_add_hint", DailyTaskConfig.MAX_REWARDS))).build())
                .active = item.rewards.size() < DailyTaskConfig.MAX_REWARDS;
        formY += ROW + 4;
        toggleRow("field.auto_label", "field.auto_label_hint", item.autoRewardLabel, v -> item.autoRewardLabel = v);
        EditBox label = textRow("field.label", "field.label_hint", item.rewardLabel, 64, v -> item.rewardLabel = v);
        label.active = !item.autoRewardLabel;
    }

    private void buildRewardId(DailyRewardEntry reward, int x, int w) {
        switch (reward.kind) {
            case DailyRewardEntry.CARD -> {
                String next = nextOf(DailyRewardEntry.CARD_TYPES, reward.id);
                place(Button.builder(Component.literal(DailyTaskUiText.cardName(reward.id.isEmpty() ? "civilian" : reward.id) + " ⟳"),
                        b -> { reward.id = next; rebuild(); }).bounds(x, fy(), w, 20)
                        .tooltip(Tooltip.create(DailyTaskUiText.tr("reward.card_hint"))).build());
            }
            case DailyRewardEntry.SKIN -> place(Button.builder(Component.literal(font.plainSubstrByWidth(
                    reward.id.isEmpty() ? DailyTaskUiText.s("reward.choose_skin") : DailyTaskUiText.skinName(reward.id), w - 16) + " …"), b -> {
                if (minecraft != null) minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr("reward.kind.skin"),
                        DailyTaskUiText.skinOptions(), reward.id.isEmpty() ? List.of() : List.of(reward.id), false, false,
                        chosen -> { if (!chosen.isEmpty()) reward.id = chosen.getFirst(); touch(); }));
            }).bounds(x, fy(), w, 20).tooltip(Tooltip.create(skinTooltip(reward.id))).build());
            case DailyRewardEntry.CRATE, DailyRewardEntry.KEY -> place(Button.builder(Component.literal(font.plainSubstrByWidth(
                    reward.id.isEmpty() ? DailyTaskUiText.s("reward.choose_crate") : DailyTaskUiText.crateName(reward.id), w - 16) + " …"), b -> {
                if (minecraft != null) minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr("reward.kind." + reward.kind),
                        DailyTaskUiText.crateOptions(true), reward.id.isEmpty() ? List.of() : List.of(reward.id), false, false,
                        chosen -> { if (!chosen.isEmpty()) reward.id = chosen.getFirst(); touch(); }));
            }).bounds(x, fy(), w, 20).tooltip(Tooltip.create(tr("goods_crate_hint"))).build());
            case DailyRewardEntry.TITLE -> {
                EditBox box = place(new EditBox(font, x, fy(), w, 20, DailyTaskUiText.tr("reward.kind.title")));
                box.setMaxLength(32);
                box.setValue(reward.id);
                box.setHint(DailyTaskUiText.tr("reward.title_hint"));
                box.setTooltip(Tooltip.create(DailyTaskUiText.tr("reward.title_tooltip")));
                box.setResponder(v -> { reward.id = v; touch(); });
            }
            default -> place(Button.builder(DailyTaskUiText.tr("reward.no_id"), b -> { }).bounds(x, fy(), w, 20).build()).active = false;
        }
    }

    private Component skinTooltip(String entry) {
        if (entry == null || entry.isEmpty()) return DailyTaskUiText.tr("reward.skin_hint");
        return HabiSkinApi.fromEntry(entry).isPresent() ? Component.literal(entry)
                : DailyTaskUiText.tr("reward.skin_missing", entry);
    }

    private static String nextOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length];
        return values[0];
    }

    private void cycleKind(DailyRewardEntry reward) {
        reward.kind = nextOf(DailyRewardEntry.KINDS, reward.kind);
        reward.id = switch (reward.kind) {
            case DailyRewardEntry.CARD -> "civilian";
            case DailyRewardEntry.CRATE, DailyRewardEntry.KEY -> DailyRewardEntry.RANDOM;
            default -> "";
        };
        reward.amount = DailyRewardEntry.GREEN_APPLES.equals(reward.kind) ? 50 : 1;
        touch();
    }

    // ------------------------------------------------------------------ actions

    private void addItem(DailyShopItem template) {
        if (draft.items.size() >= DailyShopConfig.MAX_ITEMS) return;
        DailyShopItem item;
        if (template != null) {
            item = template.copy();
        } else {
            item = new DailyShopItem();
            item.title = s("new_title");
            item.rewards.add(new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
        }
        item.id = uniqueId(template == null ? "item" : template.id);
        draft.items.add(Math.min(draft.items.size(), selected + 1), item);
        selected = Math.min(draft.items.size() - 1, selected + 1);
        formScroll = 0;
        ensureVisible();
        setStatus(DailyTaskUiText.s("status.added", item.id), MUTED);
        rebuild();
    }

    private String uniqueId(String base) {
        String stem = base.replaceAll("_\\d+$", "");
        if (stem.length() > 34) stem = stem.substring(0, 34);
        for (int n = 1; ; n++) {
            String candidate = stem + "_" + n;
            if (draft.find(candidate) == null) return candidate;
        }
    }

    private void confirmDelete() {
        DailyShopItem item = current();
        if (item == null || minecraft == null) return;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                draft.items.remove(item);
                selected = Math.max(0, selected - 1);
                setStatus(DailyTaskUiText.s("status.deleted", item.title), GOLD);
            }
            minecraft.setScreen(this);
        }, tr("delete_title"), tr("delete_body", item.title, item.id)));
    }

    private void move(int delta) {
        int target = selected + delta;
        if (target < 0 || target >= draft.items.size()) return;
        draft.items.add(target, draft.items.remove(selected));
        selected = target;
        ensureVisible();
        rebuild();
    }

    private void ensureVisible() {
        int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
        if (selected < listScroll) listScroll = selected;
        if (selected >= listScroll + rows) listScroll = selected - rows + 1;
    }

    private void reload() {
        if (dirty && minecraft != null) {
            minecraft.setScreen(new ConfirmScreen(yes -> {
                if (yes) requestFresh();
                minecraft.setScreen(this);
            }, DailyTaskUiText.tr("reload_title"), DailyTaskUiText.tr("reload_body")));
            return;
        }
        requestFresh();
    }

    private void requestFresh() {
        dirty = false;
        loaded = false;
        seenVersion = DailyShopClient.version;
        if (DailyShopClient.request()) setStatus(DailyTaskUiText.s("status.loading"), MUTED);
    }

    private void save() {
        if (!editable()) return;
        DailyShopConfig copy = draft.copy();
        try {
            copy.normalize(new DailyTaskConfig.Checks() {
                public boolean skinExists(String entry) { return HabiSkinApi.fromEntry(entry).isPresent(); }
                public boolean crateExists(String crateId) {
                    CrateCatalog.Entry entry = CrateCatalog.find(crateId);
                    return entry != null && !entry.archived();
                }
            });
        } catch (IllegalArgumentException invalid) {
            setStatus(DailyTaskUiText.s("status.invalid", error(invalid.getMessage())), BAD);
            selectByErrorDetail(invalid.getMessage());
            rebuild();
            return;
        }
        if (DailyShopClient.save(GSON.toJson(copy))) {
            pending = true;
            pendingTicks = 0;
            setStatus(DailyTaskUiText.s("status.saving"), ACCENT);
        } else {
            setStatus(DailyTaskUiText.s("status.offline"), BAD);
        }
        rebuild();
    }

    private void selectByErrorDetail(String code) {
        int colon = code == null ? -1 : code.indexOf(':');
        if (colon < 0) return;
        String detail = code.substring(colon + 1);
        for (int i = 0; i < draft.items.size(); i++) {
            DailyShopItem item = draft.items.get(i);
            if (detail.equals(item.id) || item.rewards.stream().anyMatch(r -> detail.equals(r.id))) {
                selected = i;
                formScroll = 0;
                ensureVisible();
                return;
            }
        }
    }

    @Override
    public void onClose() {
        if (minecraft == null) return;
        if (dirty && editable()) {
            minecraft.setScreen(new ConfirmScreen(yes -> minecraft.setScreen(yes ? parent : this),
                    DailyTaskUiText.tr("discard_title"), DailyTaskUiText.tr("discard_body")));
            return;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        int step = v > 0 ? -1 : v < 0 ? 1 : 0;
        if (!narrow && mx >= listX && mx < listX + listW && my >= listTop && my < listBottom) {
            int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
            int before = listScroll;
            listScroll = Mth.clamp(listScroll + step, 0, Math.max(0, draft.items.size() - rows));
            if (before != listScroll) rebuild();
            return true;
        }
        if (mx >= rx && my >= formTop && my < formBottom) {
            int before = formScroll;
            formScroll = Mth.clamp(formScroll + step * ROW, 0, Math.max(0, formHeight - (formBottom - formTop)));
            if (before != formScroll) rebuild();
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (suppressNestedBackground) return;
        super.renderBackground(g, mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        if (MenuAccessBridge.isLocked()) {
            renderBackground(g, mx, my, delta);
            g.drawCenteredString(font, Component.literal("当前为未授权的访问"), width / 2, height / 2, BAD);
            return;
        }
        renderBackground(g, mx, my, delta);
        g.fill(0, 0, width, height, 0xD010141A);
        g.fill(0, 0, width, 30, 0xF0111A22);
        g.fill(0, 30, width, 31, LINE);
        g.drawString(font, title, m, 11, 0xFFFFFFFF, false);
        String badge = !DailyShopClient.connected() ? DailyTaskUiText.s("badge.offline")
                : noPermission ? DailyTaskUiText.s("badge.readonly")
                : !loaded ? DailyTaskUiText.s("badge.loading")
                : dirty ? DailyTaskUiText.s("badge.dirty") : DailyTaskUiText.s("badge.synced");
        int badgeColor = !DailyShopClient.connected() || noPermission ? BAD : dirty ? GOLD : GOOD;
        int bw = font.width(badge) + 14;
        g.fill(width - m - bw, 7, width - m, 24, 0x80212C35);
        g.fill(width - m - bw, 7, width - m - bw + 2, 24, badgeColor);
        g.drawString(font, badge, width - m - bw + 8, 12, badgeColor, false);
        String summary = s("summary", draft.items.stream().filter(t -> t.enabled).count(), draft.items.size(),
                s(draft.enabled ? "summary_open" : "summary_closed"));
        int summaryX = m + font.width(title) + 12;
        g.drawString(font, font.plainSubstrByWidth(summary, Math.max(0, width - m - bw - 8 - summaryX)), summaryX, 11,
                draft.enabled ? MUTED : GOLD, false);

        if (!narrow) {
            g.fill(listX - 4, bodyTop, listX + listW, bodyBottom, NAV);
            g.drawString(font, tr("list_title", draft.items.size(), DailyShopConfig.MAX_ITEMS), listX, bodyTop + 4, ACCENT, false);
            if (draft.items.isEmpty()) g.drawString(font, tr("list_empty"), listX + 4, listTop + 6, MUTED, false);
        }
        int rightTop = narrow ? bodyTop + 24 : bodyTop;
        g.fill(rx - 4, rightTop, rx + rw + 4, bodyBottom, PANEL);
        g.fill(rx - 4, rightTop, rx + rw + 4, rightTop + 1, LINE);
        drawItemHeader(g, rightTop);
        g.enableScissor(rx - 4, formTop, rx + rw + 4, formBottom);
        for (FormLabel label : labels) {
            int y = formTop + label.vy() - formScroll;
            if (y + 8 < formTop || y > formBottom) continue;
            String text = label.text().getString();
            switch (label.kind()) {
                case 1 -> {
                    g.drawString(font, text, rx, y + 2, ACCENT, false);
                    g.fill(rx + font.width(text) + 6, y + 6, rx + rw, y + 7, LINE);
                }
                case 2 -> g.drawString(font, text, rx, y, MUTED, false);
                case 3 -> g.drawString(font, text, cx() + Math.min(80, cw()) + 6, y + 6, MUTED, false);
                default -> g.drawString(font, font.plainSubstrByWidth(text, labelW() - 6), rx, y + 6, TEXT, false);
            }
        }
        g.disableScissor();
        suppressNestedBackground = true;
        try {
            super.render(g, mx, my, delta);
        } finally {
            suppressNestedBackground = false;
        }
        int max = Math.max(0, formHeight - (formBottom - formTop));
        if (max > 0) {
            int trackH = formBottom - formTop;
            int thumbH = Math.max(14, trackH * trackH / Math.max(trackH, formHeight));
            int thumbY = formTop + (trackH - thumbH) * formScroll / max;
            g.fill(rx + rw + 1, formTop, rx + rw + 3, formBottom, 0x40FFFFFF);
            g.fill(rx + rw + 1, thumbY, rx + rw + 3, thumbY + thumbH, ACCENT);
        }
        for (FormLabel label : labels) {
            int y = formTop + label.vy() - formScroll;
            if (label.tooltip() == null || y < formTop || y + 20 > formBottom) continue;
            if (mx >= rx && mx < rx + labelW() && my >= y && my < y + 20) {
                g.renderTooltip(font, font.split(label.tooltip(), 220), mx, my);
            }
        }
        g.drawString(font, font.plainSubstrByWidth(status, width - m * 2), m, footerY - 12, statusColor, false);
    }

    private void drawItemHeader(GuiGraphics g, int top) {
        DailyShopItem item = current();
        if (item == null) {
            g.drawString(font, tr("no_item"), rx, top + 8, MUTED, false);
            return;
        }
        String state = s(item.enabled ? "state.on_sale" : "state.off_sale");
        int stateColor = item.enabled ? GOOD : MUTED;
        int sw = font.width(state) + 10;
        g.fill(rx + rw - sw, top + 5, rx + rw, top + 17, 0x60000000);
        g.drawString(font, state, rx + rw - sw + 5, top + 7, stateColor, false);
        g.drawString(font, font.plainSubstrByWidth(item.title.isEmpty() ? "—" : item.title, rw - sw - 8), rx, top + 7, 0xFFFFFFFF, false);
        String meta = item.id + " · " + priceText(item) + " · " + limitShort(item);
        g.drawString(font, font.plainSubstrByWidth(meta, rw), rx, top + 19, MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(s("preview_goods", item.rewardLabel), rw), rx, top + 30, GOLD, false);
    }

    private String priceText(DailyShopItem item) {
        return item.price == 0 ? s("price_free") : s("price", item.price);
    }

    private String limitShort(DailyShopItem item) {
        if (!item.limitEnabled) return s("limit.short_off");
        return item.limitDays <= 0 ? s("limit.short_forever", item.limitCount)
                : s("limit.short_days", item.limitDays, item.limitCount);
    }

    /** One entry in the listing column: on-sale marker, title, price and limit. */
    private final class ItemRow extends AbstractWidget {
        private final int index;

        ItemRow(int index, int x, int y, int w) {
            super(x, y, w, LIST_ROW - 2, Component.literal(draft.items.get(index).title));
            this.index = index;
        }

        @Override
        public void onClick(double mx, double my) {
            selected = index;
            formScroll = 0;
            rebuild();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
            DailyShopItem item = draft.items.get(index);
            boolean on = index == selected;
            g.fill(getX(), getY(), getX() + width, getY() + height, on ? 0xFF24313A : isHoveredOrFocused() ? 0xFF1C252E : 0x40141820);
            g.fill(getX(), getY(), getX() + 2, getY() + height, on ? GOLD : 0x204D5965);
            if (isFocused()) g.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
            g.drawString(font, item.enabled ? "●" : "○", getX() + 6, getY() + 4, item.enabled ? GOOD : MUTED, false);
            g.drawString(font, font.plainSubstrByWidth(item.title, width - 22), getX() + 16, getY() + 3,
                    item.enabled ? 0xFFFFFFFF : MUTED, false);
            String sub = priceText(item) + " · " + limitShort(item);
            g.drawString(font, font.plainSubstrByWidth(sub, width - 22), getX() + 16, getY() + 13, MUTED, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            DailyShopItem item = draft.items.get(index);
            output.add(NarratedElementType.TITLE, Component.literal(item.title));
            output.add(NarratedElementType.HINT, tr(item.enabled ? "state.on_sale" : "state.off_sale"));
        }
    }
}
