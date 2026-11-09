package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Shared split/narrow master-detail configuration screen base for {@link DailyShopManageScreen}
 * and {@link DailyTaskManageScreen} ({@code N-01}, {@code N-06}, {@code B-22}).
 *
 * <p>Encapsulates responsive split/narrow layout, consistent scissor clipping of BOTH form labels
 * and right-hand form widgets at scroll boundaries ({@code N-06}), narrow-mode max-entry guards,
 * explicit read-only widget locking without hardcoded text matching, and the {@code B-22}
 * {@link MenuAccessBridge#isLocked()} input/overlay guard.</p>
 */
public abstract class AbstractSplitFormManageScreen<D> extends Screen {
    protected static final int ROW = 24;
    protected static final int LIST_ROW = 26;
    protected static final int PANEL = 0xC0141D25;
    protected static final int NAV = 0xE512171E;
    protected static final int LINE = 0x5057C6D6;
    protected static final int ACCENT = 0xFF57C6D6;
    protected static final int GOLD = 0xFFD4A55A;
    protected static final int TEXT = 0xFFE6EDF2;
    protected static final int MUTED = 0xFF8A92A0;
    protected static final int GOOD = 0xFF65D18A;
    protected static final int BAD = 0xFFFF6B6B;

    /** {@code kind}: 0 field label, 1 section heading, 2 muted note line, 3 unit suffix. */
    protected record FormLabel(Component text, int vy, int kind, Component tooltip) {
    }

    protected final Screen parent;
    protected int m, bodyTop, bodyBottom, footerY, listX, listW, listTop, listBottom, rx, rw, formTop, formBottom;
    protected boolean narrow;
    protected int listScroll, formScroll;
    protected int formY, formHeight;

    protected final List<FormLabel> labels = new ArrayList<>();
    private final List<AbstractWidget> formWidgets = new ArrayList<>();
    private final Set<AbstractWidget> navigationWidgets =
            Collections.newSetFromMap(new IdentityHashMap<>());

    protected Button backButton;
    protected Button reloadButton;
    protected Button saveButton;

    private boolean suppressNestedBackground;
    private boolean renderedLocked;
    private int buildGeneration;

    protected AbstractSplitFormManageScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    // ------------------------------------------------------------------ subclass state hooks

    protected abstract List<D> entries();

    protected abstract int maxEntries();

    protected abstract int selectedIndex();

    protected abstract void setSelectedIndex(int index);

    protected abstract boolean isConnected();

    protected abstract boolean isLoaded();

    protected abstract boolean isDirty();

    protected abstract boolean isPending();

    protected abstract boolean isNoPermission();

    protected abstract boolean isEditableState();

    protected abstract String statusText();

    protected abstract int statusColor();

    protected abstract int rightFormTopOffset();

    protected abstract void touchState();

    protected abstract void rebuildScreen();

    protected abstract AbstractWidget createListRowWidget(int index, int x, int y, int width);

    protected abstract void buildLeftExtraControls(int x, int y, int width);

    protected abstract void onAddEntry(D template);

    protected abstract void onConfirmDelete();

    protected abstract void onReloadRequested();

    protected abstract void onSaveRequested();

    protected abstract void buildRightContent();

    protected abstract String headerSummaryText();

    protected abstract int headerSummaryColor();

    protected abstract Component listTitleText();

    protected abstract Component listEmptyText();

    protected abstract void drawEntryHeader(GuiGraphics g, int rightTop);

    protected abstract Component newActionTooltip();

    protected abstract Component copyActionTooltip();

    protected abstract Component deleteActionTooltip();

    protected abstract Component orderActionTooltip();

    protected abstract Component saveActionTooltip();

    protected abstract MutableComponent fieldTr(String key, Object... args);

    public final boolean editable() {
        return !MenuAccessBridge.isLocked() && isEditableState();
    }

    protected final D currentEntry() {
        List<D> list = entries();
        if (list == null || list.isEmpty()) {
            return null;
        }
        int clamped = Mth.clamp(selectedIndex(), 0, list.size() - 1);
        setSelectedIndex(clamped);
        return list.get(clamped);
    }

    protected final void syncSaveButtonActive() {
        if (saveButton != null) {
            saveButton.active = editable() && isDirty();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (renderedLocked != MenuAccessBridge.isLocked()) {
            rebuildScreen();
        }
    }

    // ------------------------------------------------------------------ layout & rebuild

    protected final void computeLayout() {
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
        formTop = rightTop + rightFormTopOffset();
        formBottom = bodyBottom;
    }

    protected final void rebuildSplitScreen() {
        buildGeneration++;
        clearWidgets();
        labels.clear();
        formWidgets.clear();
        navigationWidgets.clear();
        backButton = null;
        reloadButton = null;
        saveButton = null;

        renderedLocked = MenuAccessBridge.isLocked();
        if (renderedLocked) {
            backButton = addRenderableWidget(AdminLockGuard.createLockedBackButton(
                    width, height, DailyTaskUiText.tr("action.back"), b -> onClose()));
            navigationWidgets.add(backButton);
            return;
        }

        computeLayout();
        touchState();
        if (!narrow) {
            buildLeftList();
        } else {
            buildNarrowSelector();
        }
        buildRightContent();
        buildFooter();
        applyReadOnlyLocks();
    }

    protected final <T extends AbstractWidget> T addNavigationWidget(T widget) {
        navigationWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void applyReadOnlyLocks() {
        if (editable()) {
            return;
        }
        for (var child : children()) {
            if (child instanceof AbstractWidget w) {
                if (navigationWidgets.contains(w)) {
                    continue;
                }
                w.active = false;
            }
        }
    }

    private void buildLeftList() {
        List<D> list = entries();
        int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, list.size() - rows));
        for (int i = listScroll; i < Math.min(list.size(), listScroll + rows); i++) {
            addNavigationWidget(createListRowWidget(i, listX, listTop + (i - listScroll) * LIST_ROW, listW - 6));
        }
        int y = listBottom + 4;
        int third = (listW - 8) / 3;
        boolean canAddMore = list.size() < maxEntries();
        D cur = currentEntry();
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.new"), b -> onAddEntry(null))
                .bounds(listX, y, third, 20).tooltip(Tooltip.create(newActionTooltip())).build())
                .active = canAddMore;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.copy"), b -> onAddEntry(currentEntry()))
                .bounds(listX + third + 4, y, third, 20).tooltip(Tooltip.create(copyActionTooltip())).build())
                .active = cur != null && canAddMore;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.delete"), b -> onConfirmDelete())
                .bounds(listX + (third + 4) * 2, y, listW - (third + 4) * 2, 20)
                .tooltip(Tooltip.create(deleteActionTooltip())).build())
                .active = cur != null;
        y += 24;
        int half = (listW - 4) / 2;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.up"), b -> moveSelected(-1))
                .bounds(listX, y, half, 20).tooltip(Tooltip.create(orderActionTooltip())).build())
                .active = selectedIndex() > 0;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.down"), b -> moveSelected(1))
                .bounds(listX + half + 4, y, listW - half - 4, 20)
                .tooltip(Tooltip.create(orderActionTooltip())).build())
                .active = selectedIndex() < list.size() - 1;
        y += 24;
        buildLeftExtraControls(listX, y, listW);
    }

    private void buildNarrowSelector() {
        List<D> list = entries();
        int y = bodyTop;
        addNavigationWidget(Button.builder(Component.literal("‹"), b -> {
            setSelectedIndex(Math.floorMod(selectedIndex() - 1, Math.max(1, list.size())));
            formScroll = 0;
            rebuildScreen();
        }).bounds(rx, y, 20, 20).build()).active = list.size() > 1;
        addNavigationWidget(Button.builder(Component.literal("›"), b -> {
            setSelectedIndex((selectedIndex() + 1) % Math.max(1, list.size()));
            formScroll = 0;
            rebuildScreen();
        }).bounds(rx + 24, y, 20, 20).build()).active = list.size() > 1;

        int bw = Math.max(40, (rw - 52) / 3);
        boolean canAddMore = list.size() < maxEntries();
        D cur = currentEntry();
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.new"), b -> onAddEntry(null))
                .bounds(rx + 52, y, bw - 2, 20).tooltip(Tooltip.create(newActionTooltip())).build())
                .active = canAddMore;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.copy"), b -> onAddEntry(currentEntry()))
                .bounds(rx + 52 + bw, y, bw - 2, 20).tooltip(Tooltip.create(copyActionTooltip())).build())
                .active = cur != null && canAddMore;
        addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.delete"), b -> onConfirmDelete())
                .bounds(rx + 52 + bw * 2, y, rw - 52 - bw * 2, 20)
                .tooltip(Tooltip.create(deleteActionTooltip())).build())
                .active = cur != null;
    }

    private void buildFooter() {
        backButton = addNavigationWidget(Button.builder(DailyTaskUiText.tr("action.back"), b -> onClose())
                .bounds(m, footerY, 60, 20).build());
        int saveW = 90;
        saveButton = addRenderableWidget(Button.builder(DailyTaskUiText.tr("action.save"), b -> onSaveRequested())
                .bounds(width - m - saveW, footerY, saveW, 20)
                .tooltip(Tooltip.create(saveActionTooltip())).build());
        saveButton.active = editable() && isDirty();
        reloadButton = addNavigationWidget(Button.builder(DailyTaskUiText.tr("action.reload"), b -> onReloadRequested())
                .bounds(width - m - saveW - 4 - 80, footerY, 80, 20)
                .tooltip(Tooltip.create(DailyTaskUiText.tr("action.reload_hint"))).build());
        reloadButton.active = isConnected() && !isPending();
    }

    protected final void moveSelected(int delta) {
        List<D> list = entries();
        int sel = selectedIndex();
        int target = sel + delta;
        if (target < 0 || target >= list.size()) {
            return;
        }
        list.add(target, list.remove(sel));
        setSelectedIndex(target);
        ensureSelectedVisible();
        rebuildScreen();
    }

    protected final void ensureSelectedVisible() {
        int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
        int sel = selectedIndex();
        if (sel < listScroll) {
            listScroll = sel;
        }
        if (sel >= listScroll + rows) {
            listScroll = sel - rows + 1;
        }
    }

    protected final void finishFormLayout() {
        formHeight = formY;
        int max = Math.max(0, formHeight - (formBottom - formTop));
        if (formScroll > max) {
            formScroll = max;
            rebuildScreen();
        }
    }

    // ------------------------------------------------------------------ form row builders

    protected final int labelW() {
        return Math.min(118, rw * 36 / 100);
    }

    protected final int cx() {
        return rx + labelW();
    }

    protected final int cw() {
        return rw - labelW() - 8;
    }

    protected final int fy() {
        return formTop + formY - formScroll;
    }

    protected final void heading(String key) {
        if (formY > 0) {
            formY += 4;
        }
        labels.add(new FormLabel(fieldTr(key), formY, 1, null));
        formY += 14;
    }

    protected final void note(Component text) {
        for (FormattedCharSequence line : font.split(text, Math.max(24, rw - 8))) {
            labels.add(new FormLabel(Component.literal(toPlain(line)), formY, 2, null));
            formY += 11;
        }
        formY += 4;
    }

    protected static String toPlain(FormattedCharSequence seq) {
        StringBuilder b = new StringBuilder();
        seq.accept((i, style, cp) -> {
            b.appendCodePoint(cp);
            return true;
        });
        return b.toString();
    }

    protected final void label(String key, String hintKey) {
        labels.add(new FormLabel(fieldTr(key), formY, 0, hintKey == null ? null : fieldTr(hintKey)));
    }

    protected final void unitSuffix(String unit) {
        if (unit != null && !unit.isEmpty()) {
            labels.add(new FormLabel(Component.literal(unit), formY, 3, null));
        }
    }

    /**
     * Registers a scrollable form widget. Rendered inside the form scissor rectangle alongside its
     * label so partially-scrolled rows never draw half-labels with missing right-hand controls ({@code N-06}).
     */
    protected final <T extends AbstractWidget> T place(T widget) {
        int y = widget.getY();
        widget.visible = y + widget.getHeight() > formTop && y < formBottom;
        formWidgets.add(widget);
        return addWidget(widget);
    }

    protected final Button button(Component text, Button.OnPress press, int x, int w, Component tooltip) {
        Button b = Button.builder(text, press).bounds(x, fy(), w, 20).build();
        if (tooltip != null) {
            b.setTooltip(Tooltip.create(tooltip));
        }
        return place(b);
    }

    protected final void toggleRow(String key, String hint, boolean value, Consumer<Boolean> set) {
        label(key, hint);
        button(DailyTaskUiText.tr(value ? "on" : "off"), b -> {
            set.accept(!value);
            rebuildScreen();
        }, cx(), Math.min(120, cw()), fieldTr(hint));
        formY += ROW;
    }

    protected final void cycleRow(
            String key,
            String hint,
            String value,
            String[] values,
            String prefix,
            Consumer<String> set
    ) {
        label(key, hint);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                index = i;
            }
        }
        String next = values[(index + 1) % values.length];
        button(fieldTr(prefix + value).copy().append(" ⟳"), b -> {
            set.accept(next);
            rebuildScreen();
        }, cx(), cw(), fieldTr(hint));
        formY += ROW;
    }

    protected final void pickerRow(
            String key,
            String hint,
            List<String> values,
            Function<String, String> names,
            Supplier<List<DailyPickerScreen.Option>> options,
            boolean custom
    ) {
        label(key, hint);
        String summary = values.isEmpty() ? DailyTaskUiText.s("any_value")
                : DailyTaskUiText.joinNames(values, names, 4);
        StringBuilder all = new StringBuilder(fieldTr(hint).getString());
        for (String v : values) {
            all.append("\n· ").append(names.apply(v));
        }
        button(Component.literal(font.plainSubstrByWidth(summary, cw() - 16) + " …"), b -> {
            if (minecraft != null) {
                minecraft.setScreen(new DailyPickerScreen(this, fieldTr(key), options.get(),
                        values, true, custom, chosen -> {
                    values.clear();
                    values.addAll(chosen);
                    touchState();
                }));
            }
        }, cx(), cw(), Component.literal(all.toString()));
        formY += ROW;
    }

    protected final EditBox textRow(String key, String hint, String value, int max, Consumer<String> set) {
        label(key, hint);
        EditBox box = place(new EditBox(font, cx(), fy(), cw(), 20, fieldTr(key)));
        box.setMaxLength(max);
        box.setValue(value == null ? "" : value);
        box.setTooltip(Tooltip.create(fieldTr(hint)));
        box.setResponder(v -> {
            set.accept(v);
            touchState();
        });
        formY += ROW;
        return box;
    }

    protected final EditBox numberRow(
            String key,
            String hint,
            int value,
            int min,
            int max,
            String unit,
            IntConsumer set
    ) {
        label(key, hint);
        EditBox box = place(new EditBox(font, cx(), fy(), Math.min(80, cw()), 20, fieldTr(key)));
        box.setMaxLength(String.valueOf(max).length());
        box.setFilter(v -> v.matches("\\d*"));
        box.setValue(String.valueOf(value));
        box.setTooltip(Tooltip.create(fieldTr(hint)));
        box.setResponder(v -> {
            int parsed;
            try {
                parsed = Integer.parseInt(v.trim());
            } catch (NumberFormatException invalid) {
                parsed = -1;
            }
            box.setTextColor(parsed >= min && parsed <= max ? 0xFFE0E0E0 : BAD);
            set.accept(parsed);
            touchState();
        });
        unitSuffix(unit);
        formY += ROW;
        return box;
    }

    protected final void buildRewardEntryRows(
            List<DailyRewardEntry> rewards,
            Component introNote,
            Component emptyNote,
            Component addButtonText,
            Component addButtonTooltip,
            Component crateTooltip,
            Supplier<DailyRewardEntry> newRewardFactory
    ) {
        note(introNote);
        int kindW = Math.min(96, rw / 4);
        int amountW = 52;
        int delW = 20;
        int idW = rw - kindW - amountW - delW - 12;
        for (int i = 0; i < rewards.size(); i++) {
            DailyRewardEntry reward = rewards.get(i);
            int index = i;
            place(Button.builder(Component.literal(DailyTaskUiText.s("reward.kind." + reward.kind) + " ⟳"), b -> {
                cycleKind(reward);
                rebuildScreen();
            }).bounds(rx, fy(), kindW, 20).tooltip(Tooltip.create(DailyTaskUiText.tr("reward.kind_hint"))).build());
            buildRewardId(reward, rx + kindW + 4, idW, crateTooltip);
            EditBox amount = place(new EditBox(font, rx + kindW + 4 + idW + 4, fy(), amountW, 20,
                    DailyTaskUiText.tr("reward.amount")));
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
                amount.setTextColor(reward.amount >= 1 && reward.amount <= DailyTaskConfig.MAX_REWARD_AMOUNT
                        ? 0xFFE0E0E0 : BAD);
                touchState();
            });
            place(Button.builder(Component.literal("✕"), b -> {
                rewards.remove(index);
                rebuildScreen();
            }).bounds(rx + rw - delW, fy(), delW, 20)
                    .tooltip(Tooltip.create(DailyTaskUiText.tr("reward.remove")))
                    .createNarration(n -> DailyTaskUiText.tr("reward.remove").copy()).build());
            formY += ROW;
        }
        if (rewards.isEmpty()) {
            note(emptyNote);
        }
        button(addButtonText, b -> {
            rewards.add(newRewardFactory.get());
            rebuildScreen();
        }, rx, Math.min(160, rw), addButtonTooltip)
                .active = rewards.size() < DailyTaskConfig.MAX_REWARDS;
    }

    private void buildRewardId(DailyRewardEntry reward, int x, int w, Component crateTooltip) {
        switch (reward.kind) {
            case DailyRewardEntry.CARD -> {
                String next = nextOf(DailyRewardEntry.CARD_TYPES, reward.id);
                place(Button.builder(Component.literal(
                                DailyTaskUiText.cardName(reward.id.isEmpty() ? "civilian" : reward.id) + " ⟳"),
                        b -> {
                            reward.id = next;
                            rebuildScreen();
                        }).bounds(x, fy(), w, 20)
                        .tooltip(Tooltip.create(DailyTaskUiText.tr("reward.card_hint"))).build());
            }
            case DailyRewardEntry.SKIN -> place(Button.builder(Component.literal(font.plainSubstrByWidth(
                            reward.id.isEmpty() ? DailyTaskUiText.s("reward.choose_skin")
                                    : DailyTaskUiText.skinName(reward.id), w - 16) + " …"), b -> {
                if (minecraft != null) {
                    minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr("reward.kind.skin"),
                            DailyTaskUiText.skinOptions(), reward.id.isEmpty() ? List.of() : List.of(reward.id),
                            false, false, chosen -> {
                        if (!chosen.isEmpty()) {
                            reward.id = chosen.getFirst();
                        }
                        touchState();
                    }));
                }
            }).bounds(x, fy(), w, 20).tooltip(Tooltip.create(skinTooltip(reward.id))).build());
            case DailyRewardEntry.CRATE, DailyRewardEntry.KEY -> place(Button.builder(Component.literal(
                            font.plainSubstrByWidth(reward.id.isEmpty() ? DailyTaskUiText.s("reward.choose_crate")
                                    : DailyTaskUiText.crateName(reward.id), w - 16) + " …"), b -> {
                if (minecraft != null) {
                    minecraft.setScreen(new DailyPickerScreen(this, DailyTaskUiText.tr("reward.kind." + reward.kind),
                            DailyTaskUiText.crateOptions(true), reward.id.isEmpty() ? List.of() : List.of(reward.id),
                            false, false, chosen -> {
                        if (!chosen.isEmpty()) {
                            reward.id = chosen.getFirst();
                        }
                        touchState();
                    }));
                }
            }).bounds(x, fy(), w, 20).tooltip(Tooltip.create(crateTooltip)).build());
            case DailyRewardEntry.TITLE -> {
                EditBox box = place(new EditBox(font, x, fy(), w, 20, DailyTaskUiText.tr("reward.kind.title")));
                box.setMaxLength(32);
                box.setValue(reward.id);
                box.setHint(DailyTaskUiText.tr("reward.title_hint"));
                box.setTooltip(Tooltip.create(DailyTaskUiText.tr("reward.title_tooltip")));
                box.setResponder(v -> {
                    reward.id = v;
                    touchState();
                });
            }
            default -> place(Button.builder(DailyTaskUiText.tr("reward.no_id"), b -> { })
                    .bounds(x, fy(), w, 20).build()).active = false;
        }
    }

    protected static Component skinTooltip(String entry) {
        if (entry == null || entry.isEmpty()) {
            return DailyTaskUiText.tr("reward.skin_hint");
        }
        return HabiSkinApi.fromEntry(entry).isPresent() ? Component.literal(entry)
                : DailyTaskUiText.tr("reward.skin_missing", entry);
    }

    protected static String nextOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                return values[(i + 1) % values.length];
            }
        }
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
        touchState();
    }

    protected static String truncateChars(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = maxChars;
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut);
    }

    // ------------------------------------------------------------------ input & close

    @Override
    public void onClose() {
        if (minecraft == null) {
            return;
        }
        if (!MenuAccessBridge.isLocked() && isDirty() && editable()) {
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
    public boolean mouseClicked(double mx, double my, int button) {
        if (MenuAccessBridge.isLocked()) {
            return AdminLockGuard.handleLockedMouseClicked(backButton, mx, my, button);
        }
        boolean outsideFormViewport = my < formTop || my >= formBottom;
        if (!outsideFormViewport || formWidgets.isEmpty()) {
            return super.mouseClicked(mx, my, button);
        }
        int genBefore = buildGeneration;
        boolean[] savedVisibility = new boolean[formWidgets.size()];
        for (int i = 0; i < formWidgets.size(); i++) {
            AbstractWidget w = formWidgets.get(i);
            savedVisibility[i] = w.visible;
            w.visible = false;
        }
        try {
            return super.mouseClicked(mx, my, button);
        } finally {
            if (buildGeneration == genBefore) {
                for (int i = 0; i < formWidgets.size(); i++) {
                    formWidgets.get(i).visible = savedVisibility[i];
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (MenuAccessBridge.isLocked()) {
            return false;
        }
        int step = v > 0 ? -1 : v < 0 ? 1 : 0;
        List<D> list = entries();
        if (!narrow && mx >= listX && mx < listX + listW && my >= listTop && my < listBottom) {
            int rows = Math.max(1, (listBottom - listTop) / LIST_ROW);
            int before = listScroll;
            listScroll = Mth.clamp(listScroll + step, 0, Math.max(0, list.size() - rows));
            if (before != listScroll) {
                rebuildScreen();
            }
            return true;
        }
        if (mx >= rx && my >= formTop && my < formBottom) {
            int before = formScroll;
            formScroll = Mth.clamp(formScroll + step * ROW, 0, Math.max(0, formHeight - (formBottom - formTop)));
            if (before != formScroll) {
                rebuildScreen();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (MenuAccessBridge.isLocked()) {
            return AdminLockGuard.handleLockedKeyPressed(this, backButton, keyCode);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (MenuAccessBridge.isLocked()) {
            return false;
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (suppressNestedBackground) {
            return;
        }
        super.renderBackground(g, mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        if (MenuAccessBridge.isLocked()) {
            renderBackground(g, mx, my, delta);
            AdminLockGuard.renderLockedScreen(g, font, width, height, backButton, mx, my, delta);
            return;
        }
        renderBackground(g, mx, my, delta);
        g.fill(0, 0, width, height, 0xD010141A);
        g.fill(0, 0, width, 30, 0xF0111A22);
        g.fill(0, 30, width, 31, LINE);
        g.drawString(font, title, m, 11, 0xFFFFFFFF, false);

        String badge = !isConnected() ? DailyTaskUiText.s("badge.offline")
                : isNoPermission() ? DailyTaskUiText.s("badge.readonly")
                : !isLoaded() ? DailyTaskUiText.s("badge.loading")
                : isDirty() ? DailyTaskUiText.s("badge.dirty") : DailyTaskUiText.s("badge.synced");
        int badgeColor = !isConnected() || isNoPermission() ? BAD : isDirty() ? GOLD : GOOD;
        int bw = font.width(badge) + 14;
        g.fill(width - m - bw, 7, width - m, 24, 0x80212C35);
        g.fill(width - m - bw, 7, width - m - bw + 2, 24, badgeColor);
        g.drawString(font, badge, width - m - bw + 8, 12, badgeColor, false);

        String summary = headerSummaryText();
        int summaryX = m + font.width(title) + 12;
        g.drawString(font, font.plainSubstrByWidth(summary, Math.max(0, width - m - bw - 8 - summaryX)),
                summaryX, 11, headerSummaryColor(), false);

        List<D> list = entries();
        if (!narrow) {
            g.fill(listX - 4, bodyTop, listX + listW, bodyBottom, NAV);
            g.drawString(font, listTitleText(), listX, bodyTop + 4, ACCENT, false);
            if (list.isEmpty()) {
                g.drawString(font, listEmptyText(), listX + 4, listTop + 6, MUTED, false);
            }
        }
        int rightTop = narrow ? bodyTop + 24 : bodyTop;
        g.fill(rx - 4, rightTop, rx + rw + 4, bodyBottom, PANEL);
        g.fill(rx - 4, rightTop, rx + rw + 4, rightTop + 1, LINE);
        drawEntryHeader(g, rightTop);

        suppressNestedBackground = true;
        try {
            super.render(g, mx, my, delta);
        } finally {
            suppressNestedBackground = false;
        }

        boolean mouseInsideForm = my >= formTop && my < formBottom;
        int formMx = mouseInsideForm ? mx : -10000;
        int formMy = mouseInsideForm ? my : -10000;
        g.enableScissor(rx - 4, formTop, rx + rw + 4, formBottom);
        try {
            for (FormLabel label : labels) {
                int y = formTop + label.vy() - formScroll;
                if (y + 16 < formTop || y > formBottom) {
                    continue;
                }
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
            for (AbstractWidget w : formWidgets) {
                if (w.visible) {
                    w.render(g, formMx, formMy, delta);
                }
            }
        } finally {
            g.disableScissor();
        }

        int max = Math.max(0, formHeight - (formBottom - formTop));
        if (max > 0) {
            int trackH = formBottom - formTop;
            int thumbH = Math.max(14, trackH * trackH / Math.max(trackH, formHeight));
            int thumbY = formTop + (trackH - thumbH) * formScroll / max;
            g.fill(rx + rw + 1, formTop, rx + rw + 3, formBottom, 0x40FFFFFF);
            g.fill(rx + rw + 1, thumbY, rx + rw + 3, thumbY + thumbH, ACCENT);
        }
        if (mouseInsideForm) {
            for (FormLabel label : labels) {
                int y = formTop + label.vy() - formScroll;
                if (label.tooltip() == null || y + 20 <= formTop || y >= formBottom) {
                    continue;
                }
                if (mx >= rx && mx < rx + labelW() && my >= Math.max(formTop, y) && my < Math.min(formBottom, y + 20)) {
                    g.renderTooltip(font, font.split(label.tooltip(), 220), mx, my);
                }
            }
        }
        String status = statusText();
        g.drawString(font, font.plainSubstrByWidth(status == null ? "" : status, width - m * 2),
                m, footerY - 12, statusColor(), false);
    }
}
