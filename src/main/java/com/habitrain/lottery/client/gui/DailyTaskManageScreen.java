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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Locale;

/**
 * Mod Menu editor for the world's daily tasks: a task list on the left, and for the selected task
 * three pages — conditions (what to do), rewards (what to get) and basics (id, text, visibility).
 *
 * <p>Everything edits a local draft; nothing reaches the server until save. The server validates
 * again and rejects a draft based on an outdated revision, so two admins cannot overwrite each other
 * ({@code N-01}, {@code N-06}, {@code B-22}, {@code N-02}).</p>
 */
public final class DailyTaskManageScreen extends AbstractSplitFormManageScreen<DailyTaskDefinition> {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int PAGE_CONDITIONS = 0, PAGE_REWARDS = 1, PAGE_BASICS = 2;
    private static final String[] PAGES = {"page.conditions", "page.rewards", "page.basics"};

    private DailyTaskConfig draft = DailyTaskConfig.defaults();
    private String baseline = "";
    private List<DailyTaskConfigService.RoleOption> roster = List.of();
    private boolean loaded, serverWritable, noPermission, pending, dirty;
    private int pendingTicks, seenVersion = -1;
    private int selected, page;
    private String status = "";
    private int statusColor = MUTED;

    public DailyTaskManageScreen(Screen parent) {
        super(DailyTaskUiText.tr("title"), parent);
        draft.normalize(DailyTaskConfig.Checks.LENIENT);
        baseline = GSON.toJson(draft);
    }

    // ------------------------------------------------------------------ state

    @Override
    protected void init() {
        if (!MenuAccessBridge.isLocked()) {
            if (!loaded && DailyTaskAdminClient.connected()) {
                if (seenVersion == -1) {
                    seenVersion = DailyTaskAdminClient.version;
                    DailyTaskAdminClient.request();
                    setStatus(DailyTaskUiText.s("status.loading"), MUTED);
                }
            } else if (!DailyTaskAdminClient.connected()) {
                setStatus(DailyTaskUiText.s("status.offline"), GOLD);
            }
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
        DailyTaskDefinition cur = currentEntry();
        String keep = cur == null ? null : cur.id;
        draft = snapshot.config();
        roster = snapshot.roles() == null ? List.of() : snapshot.roles();
        serverWritable = snapshot.writable();
        baseline = GSON.toJson(draft);
        loaded = true;
        dirty = false;
        selected = 0;
        for (int i = 0; keep != null && i < draft.tasks.size(); i++) {
            if (keep.equals(draft.tasks.get(i).id)) {
                selected = i;
            }
        }
    }

    @Override
    protected List<DailyTaskDefinition> entries() {
        return draft.tasks;
    }

    @Override
    protected int maxEntries() {
        return DailyTaskConfig.MAX_TASKS;
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
        return DailyTaskAdminClient.connected();
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
        return loaded && serverWritable && !noPermission && !pending && DailyTaskAdminClient.connected();
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
        return 82;
    }

    @Override
    protected MutableComponent fieldTr(String key, Object... args) {
        return DailyTaskUiText.tr(key, args);
    }

    @Override
    protected void touchState() {
        touch();
    }

    /** Every edit goes through here: refresh generated text, then the dirty flag and save button. */
    private void touch() {
        for (DailyTaskDefinition task : draft.tasks) {
            if (task.autoDescription) task.description = DailyTaskUiText.describe(task);
            if (task.autoRewardLabel) task.rewardLabel = DailyTaskUiText.rewardLabel(task.rewards);
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
        if (!loaded && seenVersion == -1 && DailyTaskAdminClient.connected()) {
            seenVersion = DailyTaskAdminClient.version;
            DailyTaskAdminClient.request();
        }
    }

    @Override
    protected void rebuildScreen() {
        rebuild();
    }

    private void rebuild() {
        rebuildSplitScreen();
    }

    // ------------------------------------------------------------------ left & pages

    @Override
    protected AbstractWidget createListRowWidget(int index, int x, int y, int width) {
        return new TaskRow(index, x, y, width);
    }

    @Override
    protected void buildLeftExtraControls(int x, int y, int width) {
        randomControls(x, y, width, false);
    }

    /** Global draw settings: the on/off switch and how many random-pool tasks each player gets. */
    private void randomControls(int x, int y, int w, boolean inForm) {
        int boxW = 40;
        Button toggle = Button.builder(DailyTaskUiText.tr(draft.randomOn() ? "random.on" : "random.off"), b -> {
            draft.randomEnabled = !draft.randomOn();
            rebuild();
        }).bounds(x, y, w - boxW - 4, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("random.toggle_hint"))).build();
        EditBox pick = new EditBox(font, x + w - boxW, y, boxW, 20, DailyTaskUiText.tr("random_pick"));
        if (inForm) {
            place(toggle);
            place(pick);
        } else {
            addRenderableWidget(toggle);
            addRenderableWidget(pick);
        }
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

    @Override
    protected Component newActionTooltip() {
        return DailyTaskUiText.tr("action.new_hint");
    }

    @Override
    protected Component copyActionTooltip() {
        return DailyTaskUiText.tr("action.copy_hint");
    }

    @Override
    protected Component deleteActionTooltip() {
        return DailyTaskUiText.tr("action.delete_hint");
    }

    @Override
    protected Component orderActionTooltip() {
        return DailyTaskUiText.tr("action.order_hint");
    }

    @Override
    protected Component saveActionTooltip() {
        return DailyTaskUiText.tr("action.save_hint");
    }

    @Override
    protected void buildRightContent() {
        DailyTaskDefinition task = currentEntry();
        if (task == null) {
            formY = 0;
            finishFormLayout();
            return;
        }
        int tabsY = formTop - 24;
        int tw = (rw - 8) / 3;
        for (int i = 0; i < PAGES.length; i++) {
            int index = i;
            Button tab = addNavigationWidget(Button.builder(DailyTaskUiText.tr(PAGES[i]), b -> {
                page = index;
                formScroll = 0;
                rebuild();
            }).bounds(rx + i * (tw + 4), tabsY, i == 2 ? rw - (tw + 4) * 2 : tw, 20).build());
            tab.active = page != i;
        }
        formY = 0;
        switch (page) {
            case PAGE_REWARDS -> buildRewards(task);
            case PAGE_BASICS -> buildBasics(task);
            default -> buildConditions(task);
        }
        finishFormLayout();
    }

    private void buildConditions(DailyTaskDefinition task) {
        DailyTaskType type = task.taskType() == null ? DailyTaskType.LOGIN : task.taskType();
        label("field.type", "field.type_hint");
        button(Component.literal(DailyTaskUiText.typeName(type) + "  ▾"), b -> {
            if (minecraft != null) {
                minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr("field.type"),
                        DailyTaskUiText.typeOptions(), List.of(type.id()), false, false, chosen -> {
                    if (!chosen.isEmpty()) changeType(task, DailyTaskType.fromId(chosen.getFirst()));
                }));
            }
        }, cx(), cw(), Component.literal(DailyTaskUiText.s("type_hint." + type.id())));
        formY += ROW;
        note(DailyTaskUiText.tr("scope_note." + type.scope().name().toLowerCase(Locale.ROOT)));

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
        unitSuffix(DailyTaskUiText.unit(type));
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
            default -> {
            }
        }
    }

    private void changeType(DailyTaskDefinition task, DailyTaskType type) {
        if (type == null || type.id().equals(task.type)) return;
        task.type = type.id();
        task.target = Mth.clamp(task.target, 1, type.maxTarget());
        if (type == DailyTaskType.LOGIN) task.target = 1;
        if (!type.supportsRoleFilter()) {
            task.roleMode = "any";
            task.factions.clear();
            task.roles.clear();
        }
        if (!type.supportsSingleMatch()) task.singleMatch = false;
        task.matchResult = "any";
        task.victimMode = "any";
        task.deathReasons.clear();
        task.victimFactions.clear();
        task.quests.clear();
        task.items.clear();
        task.actions.clear();
        task.crates.clear();
        task.cards.clear();
        touch();
    }

    private void buildRewards(DailyTaskDefinition task) {
        buildRewardEntryRows(
                task.rewards,
                DailyTaskUiText.tr("rewards_note"),
                DailyTaskUiText.tr("reward.empty"),
                DailyTaskUiText.tr("reward.add"),
                DailyTaskUiText.tr("reward.add_hint", DailyTaskConfig.MAX_REWARDS),
                DailyTaskUiText.tr("reward.crate_hint"),
                () -> new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 50));
        formY += ROW + 6;
        cycleRow("field.delivery", "field.delivery_hint", task.delivery, new String[]{"direct", "mail"}, "delivery.",
                v -> task.delivery = v);
        toggleRow("field.auto_reward_label", "field.auto_reward_label_hint", task.autoRewardLabel,
                v -> task.autoRewardLabel = v);
        EditBox label = textRow("field.reward_label", "field.reward_label_hint", task.rewardLabel, 64,
                v -> task.rewardLabel = v);
        label.active = !task.autoRewardLabel;
    }

    private void buildBasics(DailyTaskDefinition task) {
        EditBox id = textRow("field.id", "field.id_hint", task.id, 40,
                v -> task.id = v.trim().toLowerCase(Locale.ROOT));
        id.setFilter(v -> v.matches("[a-zA-Z0-9_]*"));
        textRow("field.title", "field.title_hint", task.title, 32, v -> task.title = v);
        toggleRow("field.auto_description", "field.auto_description_hint", task.autoDescription,
                v -> task.autoDescription = v);
        EditBox description = textRow("field.description", "field.description_hint", task.description, 120,
                v -> task.description = v);
        description.active = !task.autoDescription;
        toggleRow("field.enabled", "field.enabled_hint", task.enabled, v -> task.enabled = v);
        cycleRow("field.pinned", "field.pinned_hint", task.pinned ? "pinned" : "random",
                new String[]{"pinned", "random"}, "pinned.", v -> task.pinned = "pinned".equals(v));
        if (narrow) {
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

    @Override
    protected void onAddEntry(DailyTaskDefinition template) {
        if (draft.tasks.size() >= DailyTaskConfig.MAX_TASKS) return;
        DailyTaskDefinition task;
        if (template != null) {
            task = template.copy();
            task.title = truncateChars(template.title, 32);
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
        ensureSelectedVisible();
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

    @Override
    protected void onConfirmDelete() {
        DailyTaskDefinition task = currentEntry();
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
        seenVersion = DailyTaskAdminClient.version;
        if (DailyTaskAdminClient.request()) setStatus(DailyTaskUiText.s("status.loading"), MUTED);
    }

    @Override
    protected void onSaveRequested() {
        save();
    }

    private void save() {
        if (!editable()) return;
        DailyTaskConfig copy = draft.copy();
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
                ensureSelectedVisible();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ header & rows

    @Override
    protected String headerSummaryText() {
        return DailyTaskUiText.s("summary",
                draft.tasks.stream().filter(t -> t.enabled).count(),
                draft.tasks.size(),
                draft.randomOn() ? String.valueOf(draft.randomPick) : DailyTaskUiText.s("summary_all"));
    }

    @Override
    protected int headerSummaryColor() {
        return MUTED;
    }

    @Override
    protected Component listTitleText() {
        return DailyTaskUiText.tr("list_title", draft.tasks.size(), DailyTaskConfig.MAX_TASKS);
    }

    @Override
    protected Component listEmptyText() {
        return DailyTaskUiText.tr("list_empty");
    }

    @Override
    protected void drawEntryHeader(GuiGraphics g, int top) {
        DailyTaskDefinition task = currentEntry();
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
        g.drawString(font, font.plainSubstrByWidth(task.title.isEmpty() ? "—" : task.title, rw - sw - 8),
                rx, top + 7, 0xFFFFFFFF, false);
        String meta = task.id + " · " + (type == null ? task.type : DailyTaskUiText.typeName(type))
                + " · " + DailyTaskUiText.s("target_short", task.target, type == null ? "" : DailyTaskUiText.unit(type));
        g.drawString(font, font.plainSubstrByWidth(meta, rw), rx, top + 19, MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(DailyTaskUiText.s("preview_desc", task.description), rw),
                rx, top + 30, TEXT, false);
        g.drawString(font, font.plainSubstrByWidth(DailyTaskUiText.s("preview_reward", task.rewardLabel), rw),
                rx, top + 41, GOLD, false);
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
            g.fill(getX(), getY(), getX() + width, getY() + height,
                    on ? 0xFF24313A : isHoveredOrFocused() ? 0xFF1C252E : 0x40141820);
            g.fill(getX(), getY(), getX() + 2, getY() + height, on ? GOLD : 0x204D5965);
            if (isFocused()) g.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
            boolean drawn = draft.isRandomPool(task);
            String mark = task.enabled ? (drawn ? "◆" : "●") : "○";
            g.drawString(font, mark, getX() + 6, getY() + 4,
                    task.enabled ? (drawn ? ACCENT : GOOD) : MUTED, false);
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
