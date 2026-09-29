package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.client.DailyTaskAdminClient;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.daily.config.DailyTaskConfigService;
import com.habitrain.lottery.daily.config.DailyTaskDefinition;
import com.habitrain.lottery.daily.config.DailyTaskType;
import com.habitrain.lottery.network.DailyTaskAdminNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mod Menu editor for the world's daily tasks: a task list on the left, and for the selected task
 * three pages — conditions (what to do), rewards (what to get) and basics (id, text, visibility).
 *
 * <p>Everything edits a local draft; nothing reaches the server until "保存". The server validates
 * again and rejects a draft based on an outdated revision, so two admins cannot overwrite each other.</p>
 */
public final class DailyTaskManageScreen extends Screen {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int ROW = 24;
    private static final int LIST_ROW = 26;
    private static final int PANEL = 0xC0141D25, NAV = 0xE512171E, LINE = 0x5057C6D6, ACCENT = 0xFF57C6D6;
    private static final int GOLD = 0xFFD4A55A, TEXT = 0xFFE6EDF2, MUTED = 0xFF8A92A0, GOOD = 0xFF65D18A, BAD = 0xFFFF6B6B;
    private static final int PAGE_CONDITIONS = 0, PAGE_REWARDS = 1, PAGE_BASICS = 2;
    private static final String[] PAGES = {"page.conditions", "page.rewards", "page.basics"};

    private final Screen parent;
    private DailyTaskConfig draft = DailyTaskConfig.defaults();
    private String baseline = "";
    private List<DailyTaskConfigService.RoleOption> roster = List.of();
    private boolean loaded, serverWritable, noPermission, pending, dirty;
    private int pendingTicks, seenVersion = -1;
    private int selected, page, listScroll, formScroll;
    private String status = "";
    private int statusColor = MUTED;
    private boolean suppressNestedBackground;

    // layout
    private int m, bodyTop, bodyBottom, footerY, listX, listW, listTop, listBottom, rx, rw, formTop, formBottom;
    private boolean narrow;
    private int formY, formHeight;
    private final List<FormLabel> labels = new ArrayList<>();
    private Button saveButton;

    private record FormLabel(Component text, int vy, Component tooltip) { }

    public DailyTaskManageScreen(Screen parent) {
        super(DailyTaskUiText.tr("title"));
        this.parent = parent;
        draft.normalize(DailyTaskConfig.Checks.LENIENT);
        baseline = GSON.toJson(draft);
    }

    // ------------------------------------------------------------------ state

    @Override
    protected void init() {
        if (!loaded && DailyTaskAdminClient.connected()) {
            if (seenVersion == -1) {
                seenVersion = DailyTaskAdminClient.version;
                DailyTaskAdminClient.request();
                setStatus(DailyTaskUiText.s("status.loading"), MUTED);
            }
        } else if (!DailyTaskAdminClient.connected()) {
            setStatus(DailyTaskUiText.s("status.offline"), GOLD);
        }
        rebuild();
    }

    /** Called by the network receiver on the client thread. */
    public void onServerUpdate() {
        seenVersion = DailyTaskAdminClient.version;
        String message = DailyTaskAdminClient.message;
        if (DailyTaskAdminNetwork.NO_PERMISSION.equals(message)) {
            noPermission = true;
            pending = false;
            setStatus(DailyTaskUiText.s("status.no_permission"), BAD);
            rebuild();
            return;
        }
        DailyTaskConfigService.Snapshot snapshot;
        try {
            snapshot = GSON.fromJson(DailyTaskAdminClient.json, DailyTaskConfigService.Snapshot.class);
        } catch (RuntimeException malformed) {
            snapshot = null;
        }
        if (snapshot == null || snapshot.config() == null) return;
        boolean ours = pending;
        pending = false;
        if (DailyTaskAdminNetwork.SAVED.equals(message) || !dirty || !loaded) {
            adopt(snapshot);
            if (DailyTaskAdminNetwork.SAVED.equals(message)) setStatus(DailyTaskUiText.s("status.saved"), GOOD);
            else if (!serverWritable) setStatus(DailyTaskUiText.s("status.not_writable"), BAD);
            else if (message.isEmpty() && !ours) setStatus(DailyTaskUiText.s("status.loaded", draft.tasks.size()), MUTED);
        } else if (message.isEmpty()) {
            setStatus(DailyTaskUiText.s("status.remote_changed"), GOLD);
        }
        if (ours && !message.isEmpty() && !DailyTaskAdminNetwork.SAVED.equals(message)) {
            setStatus(DailyTaskUiText.s("status.save_failed", DailyTaskUiText.error(message)), BAD);
            selectByErrorDetail(message);
        }
        rebuild();
    }

    private void adopt(DailyTaskConfigService.Snapshot snapshot) {
        String keep = current() == null ? null : current().id;
        draft = snapshot.config();
        roster = snapshot.roles() == null ? List.of() : snapshot.roles();
        serverWritable = snapshot.writable();
        baseline = GSON.toJson(draft);
        loaded = true;
        dirty = false;
        selected = 0;
        for (int i = 0; keep != null && i < draft.tasks.size(); i++) if (keep.equals(draft.tasks.get(i).id)) selected = i;
    }

    private boolean editable() {
        return loaded && serverWritable && !noPermission && !pending && DailyTaskAdminClient.connected();
    }

    private DailyTaskDefinition current() {
        if (draft == null || draft.tasks == null || draft.tasks.isEmpty()) return null;
        selected = Mth.clamp(selected, 0, draft.tasks.size() - 1);
        return draft.tasks.get(selected);
    }

    /** Every edit goes through here: refresh generated text, then the dirty flag and save button. */
    private void touch() {
        for (DailyTaskDefinition task : draft.tasks) {
            if (task.autoDescription) task.description = DailyTaskUiText.describe(task);
            if (task.autoRewardLabel) task.rewardLabel = DailyTaskUiText.rewardLabel(task.rewards);
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
        if (!loaded && seenVersion == -1 && DailyTaskAdminClient.connected()) {
            seenVersion = DailyTaskAdminClient.version;
            DailyTaskAdminClient.request();
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
        formTop = rightTop + 82;
        formBottom = bodyBottom;
    }

    private void rebuild() {
        clearWidgets();
        labels.clear();
        layout();
        touch();
        if (!narrow) buildList();
        else buildNarrowSelector();
        buildPages();
        buildFooter();
        applyLocks();
    }

    private void applyLocks() {
        if (editable()) return;
        for (var child : children()) {
            if (child instanceof AbstractWidget w && !(w instanceof TaskRow) && w.getMessage() != null) {
                String msg = w.getMessage().getString();
                if (msg.equals(DailyTaskUiText.s("action.back")) || msg.equals(DailyTaskUiText.s("action.reload"))
                        || pageLabels().contains(msg) || msg.equals("‹") || msg.equals("›")) continue;
                w.active = false;
            }
        }
    }

    private List<String> pageLabels() {
        List<String> out = new ArrayList<>();
        for (String p : PAGES) out.add(DailyTaskUiText.s(p));
        return out;
    }

    private void buildList() {
        int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, draft.tasks.size() - rows));
        for (int i = listScroll; i < Math.min(draft.tasks.size(), listScroll + rows); i++) {
            addRenderableWidget(new TaskRow(i, listX, listTop + (i - listScroll) * LIST_ROW, listW - 6));
        }
        int y = listBottom + 4;
        int third = (listW - 8) / 3;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.new"), b -> addTask(null))
                .bounds(listX, y, third, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("action.new_hint"))).build())
                .active = draft.tasks.size() < DailyTaskConfig.MAX_TASKS;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.copy"), b -> addTask(current()))
                .bounds(listX + third + 4, y, third, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("action.copy_hint"))).build())
                .active = current() != null && draft.tasks.size() < DailyTaskConfig.MAX_TASKS;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.delete"), b -> confirmDelete())
                .bounds(listX + (third + 4) * 2, y, listW - (third + 4) * 2, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.delete_hint"))).build()).active = current() != null;
        y += 24;
        int half = (listW - 4) / 2;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.up"), b -> move(-1)).bounds(listX, y, half, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.order_hint"))).build()).active = selected > 0;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.down"), b -> move(1)).bounds(listX + half + 4, y, listW - half - 4, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.order_hint"))).build()).active = selected < draft.tasks.size() - 1;
        y += 24;
        randomControls(listX, y, listW, false);
    }

    /** Global draw settings: the on/off switch and how many random-pool tasks each player gets. */
    private void randomControls(int x, int y, int w, boolean inForm) {
        int boxW = 40;
        Button toggle = Button.builder(DailyTaskUiText.tr(draft.randomOn() ? "random.on" : "random.off"), b -> {
            draft.randomEnabled = !draft.randomOn();
            rebuild();
        }).bounds(x, y, w - boxW - 4, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("random.toggle_hint"))).build();
        EditBox pick = new EditBox(font, x + w - boxW, y, boxW, 20, DailyTaskUiText.tr("random_pick"));
        if (inForm) { place(toggle); place(pick); }
        else { addRenderableWidget(toggle); addRenderableWidget(pick); }
        pick.setMaxLength(2);
        pick.setFilter(v -> v.matches("\\d*"));
        pick.setValue(String.valueOf(draft.randomPick));
        pick.setTooltip(Tooltip.create(DailyTaskUiText.tr("random_pick_hint")));
        pick.setResponder(v -> {
            try {
                draft.randomPick = Mth.clamp(Integer.parseInt(v.trim()), 0, DailyTaskConfig.MAX_TASKS);
            } catch (NumberFormatException ignored) {
                draft.randomPick = 0;
            }
            touch();
        });
        pick.active = draft.randomOn();
        pick.setEditable(draft.randomOn());
    }

    private void buildNarrowSelector() {
        int y = bodyTop;
        addRenderableWidget(Button.builder(Component.literal("‹"), b -> { selected = Math.floorMod(selected - 1, Math.max(1, draft.tasks.size())); formScroll = 0; rebuild(); })
                .bounds(rx, y, 20, 20).build()).active = draft.tasks.size() > 1;
        addRenderableWidget(Button.builder(Component.literal("›"), b -> { selected = (selected + 1) % Math.max(1, draft.tasks.size()); formScroll = 0; rebuild(); })
                .bounds(rx + 24, y, 20, 20).build()).active = draft.tasks.size() > 1;
        int bw = Math.max(40, (rw - 52) / 3);
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.new"), b -> addTask(null)).bounds(rx + 52, y, bw - 2, 20).build());
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.copy"), b -> addTask(current())).bounds(rx + 52 + bw, y, bw - 2, 20).build())
                .active = current() != null;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.delete"), b -> confirmDelete()).bounds(rx + 52 + bw * 2, y, rw - 52 - bw * 2, 20).build())
                .active = current() != null;
    }

    private void buildFooter() {
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.back"), b -> onClose()).bounds(m, footerY, 60, 20).build());
        int saveW = 90;
        saveButton = addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.save"), b -> save())
                .bounds(width - m - saveW, footerY, saveW, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.save_hint"))).build());
        saveButton.active = editable() && dirty;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.reload"), b -> reload())
                .bounds(width - m - saveW - 4 - 80, footerY, 80, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.reload_hint"))).build()).active = DailyTaskAdminClient.connected() && !pending;
    }

    // ------------------------------------------------------------------ pages

    private void buildPages() {
        DailyTaskDefinition task = current();
        if (task == null) return;
        int tabsY = formTop - 24;
        int tw = (rw - 8) / 3;
        for (int i = 0; i < PAGES.length; i++) {
            int index = i;
            Button tab = addRenderableWidget(Button.builder(DailyTaskUiText.tr(PAGES[i]), b -> { page = index; formScroll = 0; rebuild(); })
                    .bounds(rx + i * (tw + 4), tabsY, i == 2 ? rw - (tw + 4) * 2 : tw, 20).build());
            tab.active = page != i;
        }
        formY = 0;
        switch (page) {
            case PAGE_REWARDS -> buildRewards(task);
            case PAGE_BASICS -> buildBasics(task);
            default -> buildConditions(task);
        }
        formHeight = formY;
        int max = Math.max(0, formHeight - (formBottom - formTop));
        if (formScroll > max) {
            formScroll = max;
            rebuild();
        }
    }

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

    private void label(String key, String hintKey) {
        labels.add(new FormLabel(DailyTaskUiText.tr(key), formY,
                hintKey == null ? null : DailyTaskUiText.tr(hintKey)));
    }

    private void note(Component text) {
        for (var line : font.split(text, rw - 8)) {
            labels.add(new FormLabel(Component.literal("\u0000" + toPlain(line)), formY, null));
            formY += 11;
        }
        formY += 4;
    }

    private static String toPlain(net.minecraft.util.FormattedCharSequence seq) {
        StringBuilder b = new StringBuilder();
        seq.accept((i, style, cp) -> { b.appendCodePoint(cp); return true; });
        return b.toString();
    }

    private <T extends AbstractWidget> T place(T widget) {
        int y = widget.getY();
        widget.visible = y >= formTop && y + widget.getHeight() <= formBottom;
        return addRenderableWidget(widget);
    }

    private Button button(Component text, Button.OnPress press, int x, int w, Component tooltip) {
        Button b = Button.builder(text, press).bounds(x, fy(), w, 20).build();
        if (tooltip != null) b.setTooltip(Tooltip.create(tooltip));
        return place(b);
    }

    private void toggleRow(String key, String hint, boolean value, Consumer<Boolean> set) {
        label(key, hint);
        button(DailyTaskUiText.tr(value ? "on" : "off"), b -> { set.accept(!value); rebuild(); }, cx(), Math.min(120, cw()),
                DailyTaskUiText.tr(hint));
        formY += ROW;
    }

    private void cycleRow(String key, String hint, String value, String[] values, String prefix, Consumer<String> set) {
        label(key, hint);
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) index = i;
        String next = values[(index + 1) % values.length];
        button(DailyTaskUiText.tr(prefix + value).copy().append(" ⟳"), b -> { set.accept(next); rebuild(); },
                cx(), cw(), DailyTaskUiText.tr(hint));
        formY += ROW;
    }

    private void pickerRow(String key, String hint, List<String> values, Function<String, String> names,
                           java.util.function.Supplier<List<DailyPickerScreen.Option>> options, boolean custom) {
        label(key, hint);
        String summary = values.isEmpty() ? DailyTaskUiText.s("any_value")
                : DailyTaskUiText.joinNames(values, names, 4);
        StringBuilder all = new StringBuilder(DailyTaskUiText.s(hint));
        for (String v : values) all.append("\n· ").append(names.apply(v));
        button(Component.literal(font.plainSubstrByWidth(summary, cw() - 16) + " …"), b -> {
            if (minecraft != null) minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr(key), options.get(),
                    values, true, custom, chosen -> { values.clear(); values.addAll(chosen); touch(); }));
        }, cx(), cw(), Component.literal(all.toString()));
        formY += ROW;
    }

    private EditBox textRow(String key, String hint, String value, int max, Consumer<String> set) {
        label(key, hint);
        EditBox box = place(new EditBox(font, cx(), fy(), cw(), 20, DailyTaskUiText.tr(key)));
        box.setMaxLength(max);
        box.setValue(value == null ? "" : value);
        box.setTooltip(Tooltip.create(DailyTaskUiText.tr(hint)));
        box.setResponder(v -> { set.accept(v); touch(); });
        formY += ROW;
        return box;
    }

    private void buildConditions(DailyTaskDefinition task) {
        DailyTaskType type = task.taskType() == null ? DailyTaskType.LOGIN : task.taskType();
        label("field.type", "field.type_hint");
        button(Component.literal(DailyTaskUiText.typeName(type) + "  ▾"), b -> {
            if (minecraft != null) minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr("field.type"),
                    DailyTaskUiText.typeOptions(), List.of(type.id()), false, false, chosen -> {
                if (!chosen.isEmpty()) changeType(task, DailyTaskType.fromId(chosen.getFirst()));
            }));
        }, cx(), cw(), Component.literal(DailyTaskUiText.s("type_hint." + type.id())));
        formY += ROW;
        note(DailyTaskUiText.tr("scope_note." + type.scope().name().toLowerCase(java.util.Locale.ROOT)));

        label("field.target", "field.target_hint");
        EditBox target = place(new EditBox(font, cx(), fy(), Math.min(80, cw()), 20, DailyTaskUiText.tr("field.target")));
        target.setMaxLength(7);
        target.setValue(String.valueOf(task.target));
        target.setTooltip(Tooltip.create(DailyTaskUiText.tr("field.target_range", 1, type.maxTarget(), DailyTaskUiText.unit(type))));
        target.setResponder(v -> {
            try {
                task.target = Integer.parseInt(v.trim());
                target.setTextColor(task.target >= 1 && task.target <= type.maxTarget() ? 0xFFE0E0E0 : BAD);
            } catch (NumberFormatException invalid) {
                task.target = 0;
                target.setTextColor(BAD);
            }
            touch();
        });
        target.active = type != DailyTaskType.LOGIN;
        labels.add(new FormLabel(Component.literal("\u0001" + DailyTaskUiText.unit(type)), formY, null));
        formY += ROW;

        if (type.supportsSingleMatch()) {
            toggleRow("field.single_match", "field.single_match_hint", task.singleMatch, v -> task.singleMatch = v);
        }
        if (type.supportsRoleFilter()) {
            cycleRow("field.role_mode", "field.role_mode_hint", task.roleMode, new String[]{"any", "faction", "role"},
                    "role_mode.", v -> task.roleMode = v);
            if ("faction".equals(task.roleMode)) {
                pickerRow("field.factions", "field.factions_hint", task.factions, DailyTaskUiText::factionName,
                        DailyTaskUiText::factionOptions, false);
            } else if ("role".equals(task.roleMode)) {
                pickerRow("field.roles", "field.roles_hint", task.roles, DailyTaskUiText::roleName,
                        () -> DailyTaskUiText.roleOptions(roster), true);
            }
        }
        switch (type) {
            case PLAY_MATCH -> cycleRow("field.match_result", "field.match_result_hint", task.matchResult,
                    new String[]{"any", "win", "lose", "survived", "eliminated"}, "match_result.", v -> task.matchResult = v);
            case KILL -> {
                pickerRow("field.death_reasons", "field.death_reasons_hint", task.deathReasons,
                        DailyTaskUiText::deathReasonName, DailyTaskUiText::deathReasonOptions, true);
                cycleRow("field.victim_mode", "field.victim_mode_hint", task.victimMode, new String[]{"any", "enemy", "faction"},
                        "victim_mode.", v -> task.victimMode = v);
                if ("faction".equals(task.victimMode)) {
                    pickerRow("field.victim_factions", "field.victim_factions_hint", task.victimFactions,
                            DailyTaskUiText::factionName, DailyTaskUiText::factionOptions, false);
                }
            }
            case FINISH_TASK -> pickerRow("field.quests", "field.quests_hint", task.quests, DailyTaskUiText::questName,
                    DailyTaskUiText::questOptions, true);
            case USE_ITEM, SHOP_BUY -> pickerRow("field.items", "field.items_hint", task.items, DailyTaskUiText::itemName,
                    DailyTaskUiText::itemOptions, true);
            case SPECIAL_ACTION -> pickerRow("field.actions", "field.actions_hint", task.actions,
                    a -> DailyTaskUiText.s("action." + a), DailyTaskUiText::actionOptions, false);
            case OPEN_CRATE -> pickerRow("field.crates", "field.crates_hint", task.crates, DailyTaskUiText::crateName,
                    () -> DailyTaskUiText.crateOptions(false), false);
            case USE_CARD -> pickerRow("field.cards", "field.cards_hint", task.cards, DailyTaskUiText::cardName,
                    DailyTaskUiText::cardUseOptions, false);
            default -> { }
        }
    }

    private void changeType(DailyTaskDefinition task, DailyTaskType type) {
        if (type == null || type.id().equals(task.type)) return;
        task.type = type.id();
        task.target = Mth.clamp(task.target, 1, type.maxTarget());
        if (type == DailyTaskType.LOGIN) task.target = 1;
        if (!type.supportsRoleFilter()) { task.roleMode = "any"; task.factions.clear(); task.roles.clear(); }
        if (!type.supportsSingleMatch()) task.singleMatch = false;
        task.matchResult = "any";
        task.victimMode = "any";
        task.deathReasons.clear(); task.victimFactions.clear(); task.quests.clear(); task.items.clear();
        task.actions.clear(); task.crates.clear(); task.cards.clear();
        touch();
    }

    private void buildRewards(DailyTaskDefinition task) {
        note(DailyTaskUiText.tr("rewards_note"));
        int kindW = Math.min(96, rw / 4);
        int amountW = 52;
        int delW = 20;
        int idW = rw - kindW - amountW - delW - 12;
        for (int i = 0; i < task.rewards.size(); i++) {
            DailyRewardEntry reward = task.rewards.get(i);
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
            place(Button.builder(Component.literal("✕"), b -> { task.rewards.remove(index); rebuild(); })
                    .bounds(rx + rw - delW, fy(), delW, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("reward.remove")))
                    .createNarration(n -> DailyTaskUiText.tr("reward.remove").copy()).build());
            formY += ROW;
        }
        if (task.rewards.isEmpty()) note(DailyTaskUiText.tr("reward.empty"));
        button(DailyTaskUiText.tr("reward.add"), b -> {
            task.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 50));
            rebuild();
        }, rx, Math.min(160, rw), DailyTaskUiText.tr("reward.add_hint", DailyTaskConfig.MAX_REWARDS))
                .active = task.rewards.size() < DailyTaskConfig.MAX_REWARDS;
        formY += ROW + 6;
        cycleRow("field.delivery", "field.delivery_hint", task.delivery, new String[]{"direct", "mail"}, "delivery.",
                v -> task.delivery = v);
        toggleRow("field.auto_reward_label", "field.auto_reward_label_hint", task.autoRewardLabel, v -> task.autoRewardLabel = v);
        EditBox label = textRow("field.reward_label", "field.reward_label_hint", task.rewardLabel, 64, v -> task.rewardLabel = v);
        label.active = !task.autoRewardLabel;
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
            }).bounds(x, fy(), w, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("reward.crate_hint"))).build());
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
        // Each kind starts from a sensible amount: 50 cards would be a surprising default.
        reward.amount = DailyRewardEntry.GREEN_APPLES.equals(reward.kind) ? 50 : 1;
        touch();
    }

    private void buildBasics(DailyTaskDefinition task) {
        EditBox id = textRow("field.id", "field.id_hint", task.id, 40, v -> task.id = v.trim().toLowerCase(java.util.Locale.ROOT));
        id.setFilter(v -> v.matches("[a-zA-Z0-9_]*"));
        textRow("field.title", "field.title_hint", task.title, 32, v -> task.title = v);
        toggleRow("field.auto_description", "field.auto_description_hint", task.autoDescription, v -> task.autoDescription = v);
        EditBox description = textRow("field.description", "field.description_hint", task.description, 120, v -> task.description = v);
        description.active = !task.autoDescription;
        toggleRow("field.enabled", "field.enabled_hint", task.enabled, v -> task.enabled = v);
        cycleRow("field.pinned", "field.pinned_hint", task.pinned ? "pinned" : "random", new String[]{"pinned", "random"},
                "pinned.", v -> task.pinned = "pinned".equals(v));
        if (narrow) {
            // The list panel (and its draw settings) is hidden on narrow screens.
            label("random_pick", "random.toggle_hint");
            randomControls(cx(), fy(), Math.min(180, cw()), true);
            formY += ROW;
        }
        note(randomSummary());
        note(DailyTaskUiText.tr("basics_note", "habitrain_lottery:" + task.id));
    }

    /** One-line explanation of what players will see with the current draw settings. */
    private Component randomSummary() {
        if (!draft.randomOn()) return DailyTaskUiText.tr("random.summary_off");
        long pinned = draft.tasks.stream().filter(t -> t.enabled && t.pinned).count();
        long pool = draft.tasks.stream().filter(t -> t.enabled && !t.pinned).count();
        return DailyTaskUiText.tr("random.summary_on", pinned, pool, Math.min(pool, draft.randomPick));
    }

    // ------------------------------------------------------------------ actions

    private void addTask(DailyTaskDefinition template) {
        if (draft.tasks.size() >= DailyTaskConfig.MAX_TASKS) return;
        DailyTaskDefinition task;
        if (template != null) {
            task = template.copy();
            task.title = font.plainSubstrByWidth(template.title, 200);
        } else {
            task = new DailyTaskDefinition();
            task.type = DailyTaskType.PLAY_MATCH.id();
            task.target = 1;
            task.title = DailyTaskUiText.s("new_title");
            task.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 50));
        }
        task.id = uniqueId(template == null ? "task" : template.id);
        draft.tasks.add(Math.min(draft.tasks.size(), selected + 1), task);
        selected = Math.min(draft.tasks.size() - 1, selected + 1);
        page = template == null ? PAGE_CONDITIONS : page;
        formScroll = 0;
        ensureVisible();
        setStatus(DailyTaskUiText.s("status.added", task.id), MUTED);
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
        DailyTaskDefinition task = current();
        if (task == null || minecraft == null) return;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                draft.tasks.remove(task);
                selected = Math.max(0, selected - 1);
                setStatus(DailyTaskUiText.s("status.deleted", task.title), GOLD);
            }
            minecraft.setScreen(this);
        }, DailyTaskUiText.tr("delete_title"), DailyTaskUiText.tr("delete_body", task.title, task.id)));
    }

    private void move(int delta) {
        int target = selected + delta;
        if (target < 0 || target >= draft.tasks.size()) return;
        draft.tasks.add(target, draft.tasks.remove(selected));
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
        seenVersion = DailyTaskAdminClient.version;
        if (DailyTaskAdminClient.request()) setStatus(DailyTaskUiText.s("status.loading"), MUTED);
    }

    private void save() {
        if (!editable()) return;
        DailyTaskConfig copy = draft.copy();
        try {
            copy.normalize(new DailyTaskConfig.Checks() {
                public boolean skinExists(String entry) { return HabiSkinApi.fromEntry(entry).isPresent(); }
                public boolean crateExists(String crateId) {
                    CrateCatalog.Entry entry = CrateCatalog.find(crateId);
                    return entry != null && !entry.archived();
                }
            });
        } catch (IllegalArgumentException invalid) {
            setStatus(DailyTaskUiText.s("status.invalid", DailyTaskUiText.error(invalid.getMessage())), BAD);
            selectByErrorDetail(invalid.getMessage());
            rebuild();
            return;
        }
        if (DailyTaskAdminClient.save(GSON.toJson(copy))) {
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
        for (int i = 0; i < draft.tasks.size(); i++) {
            DailyTaskDefinition task = draft.tasks.get(i);
            boolean hit = detail.equals(task.id) || task.rewards.stream().anyMatch(r -> detail.equals(r.id));
            if (hit) {
                selected = i;
                if (code.startsWith("unknown_") || code.contains("reward")) page = PAGE_REWARDS;
                else if (code.startsWith("bad_id") || code.startsWith("bad_title") || code.startsWith("duplicate")
                        || code.startsWith("description")) page = PAGE_BASICS;
                else page = PAGE_CONDITIONS;
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
            listScroll = Mth.clamp(listScroll + step, 0, Math.max(0, draft.tasks.size() - rows));
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
        String badge = !DailyTaskAdminClient.connected() ? DailyTaskUiText.s("badge.offline")
                : noPermission ? DailyTaskUiText.s("badge.readonly")
                : !loaded ? DailyTaskUiText.s("badge.loading")
                : dirty ? DailyTaskUiText.s("badge.dirty") : DailyTaskUiText.s("badge.synced");
        int badgeColor = !DailyTaskAdminClient.connected() || noPermission ? BAD : dirty ? GOLD : GOOD;
        int bw = font.width(badge) + 14;
        g.fill(width - m - bw, 7, width - m, 24, 0x80212C35);
        g.fill(width - m - bw, 7, width - m - bw + 2, 24, badgeColor);
        g.drawString(font, badge, width - m - bw + 8, 12, badgeColor, false);
        String summary = DailyTaskUiText.s("summary", draft.tasks.stream().filter(t -> t.enabled).count(), draft.tasks.size(),
                draft.randomOn() ? String.valueOf(draft.randomPick) : DailyTaskUiText.s("summary_all"));
        int summaryX = m + font.width(title) + 12;
        g.drawString(font, font.plainSubstrByWidth(summary, Math.max(0, width - m - bw - 8 - summaryX)), summaryX, 11, MUTED, false);

        if (!narrow) {
            g.fill(listX - 4, bodyTop, listX + listW, bodyBottom, NAV);
            g.drawString(font, DailyTaskUiText.tr("list_title", draft.tasks.size(), DailyTaskConfig.MAX_TASKS), listX, bodyTop + 4, ACCENT, false);
            if (draft.tasks.isEmpty()) g.drawString(font, DailyTaskUiText.tr("list_empty"), listX + 4, listTop + 6, MUTED, false);
        }
        int rightTop = narrow ? bodyTop + 24 : bodyTop;
        g.fill(rx - 4, rightTop, rx + rw + 4, bodyBottom, PANEL);
        g.fill(rx - 4, rightTop, rx + rw + 4, rightTop + 1, LINE);
        drawTaskHeader(g, rightTop);
        g.enableScissor(rx - 4, formTop, rx + rw + 4, formBottom);
        for (FormLabel label : labels) {
            int y = formTop + label.vy() - formScroll;
            if (y + 8 < formTop || y > formBottom) continue;
            String text = label.text().getString();
            if (text.startsWith("\u0000")) {
                g.drawString(font, text.substring(1), rx, y, MUTED, false);
            } else if (text.startsWith("\u0001")) {
                g.drawString(font, text.substring(1), cx() + Math.min(80, cw()) + 6, y + 6, MUTED, false);
            } else {
                g.drawString(font, font.plainSubstrByWidth(text, labelW() - 6), rx, y + 6, TEXT, false);
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

    private void drawTaskHeader(GuiGraphics g, int top) {
        DailyTaskDefinition task = current();
        if (task == null) {
            g.drawString(font, DailyTaskUiText.tr("no_task"), rx, top + 8, MUTED, false);
            return;
        }
        DailyTaskType type = task.taskType();
        boolean drawn = draft.isRandomPool(task);
        String state = DailyTaskUiText.s(task.enabled ? (drawn ? "state.random" : "state.pinned") : "state.disabled");
        int stateColor = task.enabled ? (drawn ? ACCENT : GOOD) : MUTED;
        int sw = font.width(state) + 10;
        g.fill(rx + rw - sw, top + 5, rx + rw, top + 17, 0x60000000);
        g.drawString(font, state, rx + rw - sw + 5, top + 7, stateColor, false);
        g.drawString(font, font.plainSubstrByWidth(task.title.isEmpty() ? "—" : task.title, rw - sw - 8), rx, top + 7, 0xFFFFFFFF, false);
        String meta = task.id + " · " + (type == null ? task.type : DailyTaskUiText.typeName(type))
                + " · " + DailyTaskUiText.s("target_short", task.target, type == null ? "" : DailyTaskUiText.unit(type));
        g.drawString(font, font.plainSubstrByWidth(meta, rw), rx, top + 19, MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(DailyTaskUiText.s("preview_desc", task.description), rw), rx, top + 30, TEXT, false);
        g.drawString(font, font.plainSubstrByWidth(DailyTaskUiText.s("preview_reward", task.rewardLabel), rw), rx, top + 41, GOLD, false);
    }

    /** One entry in the task list: enabled marker, title and type. */
    private final class TaskRow extends AbstractWidget {
        private final int index;

        TaskRow(int index, int x, int y, int w) {
            super(x, y, w, LIST_ROW - 2, Component.literal(draft.tasks.get(index).title));
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
            DailyTaskDefinition task = draft.tasks.get(index);
            boolean on = index == selected;
            g.fill(getX(), getY(), getX() + width, getY() + height, on ? 0xFF24313A : isHoveredOrFocused() ? 0xFF1C252E : 0x40141820);
            g.fill(getX(), getY(), getX() + 2, getY() + height, on ? GOLD : 0x204D5965);
            if (isFocused()) g.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
            boolean drawn = draft.isRandomPool(task);
            String mark = task.enabled ? (drawn ? "◆" : "●") : "○";
            g.drawString(font, mark, getX() + 6, getY() + 4, task.enabled ? (drawn ? ACCENT : GOOD) : MUTED, false);
            g.drawString(font, font.plainSubstrByWidth(task.title, width - 22), getX() + 16, getY() + 3,
                    task.enabled ? 0xFFFFFFFF : MUTED, false);
            DailyTaskType type = task.taskType();
            String sub = (type == null ? task.type : DailyTaskUiText.typeName(type)) + " ×" + task.target;
            g.drawString(font, font.plainSubstrByWidth(sub, width - 22), getX() + 16, getY() + 13, MUTED, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            DailyTaskDefinition task = draft.tasks.get(index);
            output.add(NarratedElementType.TITLE, Component.literal(task.title));
            output.add(NarratedElementType.HINT, DailyTaskUiText.tr(task.enabled ? "state.enabled" : "state.disabled"));
        }
    }
}
