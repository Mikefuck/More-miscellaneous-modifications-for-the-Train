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

import java.util.List;
import java.util.Locale;

/**
 * Mod Menu editor for the daily-terminal shop: listings on the left; for the selected listing one
 * scrolling form with price and purchase limit, the goods sold, and its display text.
 *
 * <p>Same draft / save / revision contract as {@link DailyTaskManageScreen}: nothing reaches the
 * server until save, and the server re-validates and rejects stale drafts ({@code N-01},
 * {@code N-06}, {@code B-22}, {@code N-02}).</p>
 */
public final class DailyShopManageScreen extends AbstractSplitFormManageScreen<DailyShopItem> {
    private static final String KEY = "screen.habitrain_lottery.daily_shop_admin.";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private DailyShopConfig draft = DailyShopConfig.defaults();
    private String baseline = "";
    private boolean loaded, serverWritable, noPermission, pending, dirty;
    private int pendingTicks, seenVersion = -1;
    private int selected;
    private String status = "";
    private int statusColor = MUTED;

    public DailyShopManageScreen(Screen parent) {
        super(tr("title"), parent);
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
        if (!MenuAccessBridge.isLocked()) {
            if (!loaded && DailyShopClient.connected()) {
                if (seenVersion == -1) {
                    seenVersion = DailyShopClient.version;
                    DailyShopClient.request();
                    setStatus(DailyTaskUiText.s("status.loading"), MUTED);
                }
            } else if (!DailyShopClient.connected()) {
                setStatus(DailyTaskUiText.s("status.offline"), GOLD);
            }
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
        DailyShopItem cur = currentEntry();
        String keep = cur == null ? null : cur.id;
        draft = snapshot.config();
        serverWritable = snapshot.writable();
        baseline = GSON.toJson(draft);
        loaded = true;
        dirty = false;
        selected = 0;
        for (int i = 0; keep != null && i < draft.items.size(); i++) {
            if (keep.equals(draft.items.get(i).id)) {
                selected = i;
            }
        }
    }

    @Override
    protected List<DailyShopItem> entries() {
        return draft.items;
    }

    @Override
    protected int maxEntries() {
        return DailyShopConfig.MAX_ITEMS;
    }

    @Override
    protected int selectedIndex() {
        return selected;
    }

    @Override
    protected void setSelectedIndex(int index) {
        this.selected = index;
    }

    @Override
    protected boolean isConnected() {
        return DailyShopClient.connected();
    }

    @Override
    protected boolean isLoaded() {
        return loaded;
    }

    @Override
    protected boolean isDirty() {
        return dirty;
    }

    @Override
    protected boolean isPending() {
        return pending;
    }

    @Override
    protected boolean isNoPermission() {
        return noPermission;
    }

    @Override
    protected boolean isEditableState() {
        return loaded && serverWritable && !noPermission && !pending && DailyShopClient.connected();
    }

    @Override
    protected String statusText() {
        return status;
    }

    @Override
    protected int statusColor() {
        return statusColor;
    }

    @Override
    protected int rightFormTopOffset() {
        return 46;
    }

    @Override
    protected MutableComponent fieldTr(String key, Object... args) {
        return tr(key, args);
    }

    @Override
    protected void touchState() {
        touch();
    }

    private void touch() {
        for (DailyShopItem item : draft.items) {
            if (item.autoRewardLabel) {
                item.rewardLabel = DailyTaskUiText.rewardLabel(item.rewards);
            }
        }
        dirty = !GSON.toJson(draft).equals(baseline);
        syncSaveButtonActive();
    }

    private void setStatus(String text, int color) {
        status = text == null ? "" : text;
        statusColor = color;
    }

    @Override
    public void tick() {
        super.tick();
        if (MenuAccessBridge.isLocked()) {
            return;
        }
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

    @Override
    protected void rebuildScreen() {
        rebuild();
    }

    private void rebuild() {
        rebuildSplitScreen();
    }

    // ------------------------------------------------------------------ left & form builders

    @Override
    protected AbstractWidget createListRowWidget(int index, int x, int y, int width) {
        return new ItemRow(index, x, y, width);
    }

    @Override
    protected void buildLeftExtraControls(int x, int y, int width) {
        addRenderableWidget(shopSwitch(x, y, width));
    }

    private Button shopSwitch(int x, int y, int w) {
        return Button.builder(tr(draft.enabled ? "shop.on" : "shop.off"), b -> {
            draft.enabled = !draft.enabled;
            rebuild();
        }).bounds(x, y, w, 20).tooltip(Tooltip.create(tr("shop.toggle_hint"))).build();
    }

    @Override
    protected Component newActionTooltip() {
        return tr("action.new_hint");
    }

    @Override
    protected Component copyActionTooltip() {
        return tr("action.copy_hint");
    }

    @Override
    protected Component deleteActionTooltip() {
        return tr("action.delete_hint");
    }

    @Override
    protected Component orderActionTooltip() {
        return tr("action.order_hint");
    }

    @Override
    protected Component saveActionTooltip() {
        return tr("action.save_hint");
    }

    @Override
    protected void buildRightContent() {
        DailyShopItem item = currentEntry();
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
            buildRewardEntryRows(
                    item.rewards,
                    tr("goods_note"),
                    tr("goods_empty"),
                    tr("goods_add"),
                    tr("goods_add_hint", DailyTaskConfig.MAX_REWARDS),
                    tr("goods_crate_hint"),
                    () -> new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
            formY += ROW + 4;
            toggleRow("field.auto_label", "field.auto_label_hint", item.autoRewardLabel, v -> item.autoRewardLabel = v);
            EditBox label = textRow("field.label", "field.label_hint", item.rewardLabel, 64, v -> item.rewardLabel = v);
            label.active = !item.autoRewardLabel;

            heading("section.basics");
            textRow("field.title", "field.title_hint", item.title, 32, v -> item.title = v);
            EditBox id = textRow("field.id", "field.id_hint", item.id, 40,
                    v -> item.id = v.trim().toLowerCase(Locale.ROOT));
            id.setFilter(v -> v.matches("[a-zA-Z0-9_]*"));
            textRow("field.description", "field.description_hint", item.description, 120, v -> item.description = v);
            toggleRow("field.enabled", "field.enabled_hint", item.enabled, v -> item.enabled = v);
        }
        if (narrow) {
            heading("section.shop");
            place(shopSwitch(rx, fy(), Math.min(200, rw)));
            formY += ROW;
        }
        finishFormLayout();
    }

    private Component limitSummary(DailyShopItem item) {
        if (!item.limitEnabled) return tr("limit.summary_off");
        if (item.limitDays <= 0) return tr("limit.summary_forever", item.limitCount);
        if (item.limitDays == 1) return tr("limit.summary_daily", item.limitCount);
        if (item.limitDays % 7 == 0) return tr("limit.summary_weeks", item.limitDays, item.limitCount);
        return tr("limit.summary_days", item.limitDays, item.limitCount);
    }

    // ------------------------------------------------------------------ actions

    @Override
    protected void onAddEntry(DailyShopItem template) {
        if (draft.items.size() >= DailyShopConfig.MAX_ITEMS) return;
        DailyShopItem item;
        if (template != null) {
            item = template.copy();
            item.title = truncateChars(template.title, 32);
        } else {
            item = new DailyShopItem();
            item.title = s("new_title");
            item.rewards.add(new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
        }
        item.id = uniqueId(template == null ? "item" : template.id);
        draft.items.add(Math.min(draft.items.size(), selected + 1), item);
        selected = Math.min(draft.items.size() - 1, selected + 1);
        formScroll = 0;
        ensureSelectedVisible();
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

    @Override
    protected void onConfirmDelete() {
        DailyShopItem item = currentEntry();
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

    @Override
    protected void onReloadRequested() {
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

    @Override
    protected void onSaveRequested() {
        save();
    }

    private void save() {
        if (!editable()) return;
        DailyShopConfig copy = draft.copy();
        try {
            copy.normalize(new DailyTaskConfig.Checks() {
                public boolean skinExists(String entry) {
                    return HabiSkinApi.fromEntry(entry).isPresent();
                }

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
                ensureSelectedVisible();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ header & rows

    @Override
    protected String headerSummaryText() {
        return s("summary", draft.items.stream().filter(t -> t.enabled).count(), draft.items.size(),
                s(draft.enabled ? "summary_open" : "summary_closed"));
    }

    @Override
    protected int headerSummaryColor() {
        return draft.enabled ? MUTED : GOLD;
    }

    @Override
    protected Component listTitleText() {
        return tr("list_title", draft.items.size(), DailyShopConfig.MAX_ITEMS);
    }

    @Override
    protected Component listEmptyText() {
        return tr("list_empty");
    }

    @Override
    protected void drawEntryHeader(GuiGraphics g, int top) {
        DailyShopItem item = currentEntry();
        if (item == null) {
            g.drawString(font, tr("no_item"), rx, top + 8, MUTED, false);
            return;
        }
        String state = s(item.enabled ? "state.on_sale" : "state.off_sale");
        int stateColor = item.enabled ? GOOD : MUTED;
        int sw = font.width(state) + 10;
        g.fill(rx + rw - sw, top + 5, rx + rw, top + 17, 0x60000000);
        g.drawString(font, state, rx + rw - sw + 5, top + 7, stateColor, false);
        g.drawString(font, font.plainSubstrByWidth(item.title.isEmpty() ? "—" : item.title, rw - sw - 8),
                rx, top + 7, 0xFFFFFFFF, false);
        String meta = item.id + " · " + priceText(item) + " · " + limitShort(item);
        g.drawString(font, font.plainSubstrByWidth(meta, rw), rx, top + 19, MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(s("preview_goods", item.rewardLabel), rw),
                rx, top + 30, GOLD, false);
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
            g.fill(getX(), getY(), getX() + width, getY() + height,
                    on ? 0xFF24313A : isHoveredOrFocused() ? 0xFF1C252E : 0x40141820);
            g.fill(getX(), getY(), getX() + 2, getY() + height, on ? GOLD : 0x204D5965);
            if (isFocused()) g.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
            g.drawString(font, item.enabled ? "●" : "○", getX() + 6, getY() + 4,
                    item.enabled ? GOOD : MUTED, false);
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
