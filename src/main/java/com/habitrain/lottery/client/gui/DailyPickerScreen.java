package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Searchable single / multi choice list used by the daily-task editor (roles, items, death
 * reasons, quests, skins, crates, ...). Nothing is applied until "确定"; Esc or "取消" returns
 * to the editor untouched.
 */
public final class DailyPickerScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.daily_picker.";
    private static final int ROW_H = 22;
    private static final int PANEL = 0xF0121A22, BORDER = 0xFF3A5566, ACCENT = 0xFF57C6D6, GOLD = 0xFFD4A55A;
    private static final int TEXT = 0xFFE6EDF2, MUTED = 0xFF8FA0AC;

    /** One choice. {@code group} splits the list into labelled sections (may be empty). */
    public record Option(String id, String label, String detail, int color, String group) {
        public Option(String id, String label, String detail, int color) { this(id, label, detail, color, ""); }
    }

    private final Screen parent;
    private final List<Option> options;
    private final Set<String> selected;
    private final boolean multi;
    private final boolean allowCustom;
    private final Consumer<List<String>> onDone;
    private final List<Row> rows = new ArrayList<>();
    private EditBox search;
    private EditBox custom;
    private String query = "";
    private String customDraft = "";
    private String error = "";
    private int scroll;
    private int px, py, pw, ph, listTop, listBottom;

    public DailyPickerScreen(Screen parent, Component title, List<Option> options, List<String> selected,
                             boolean multi, boolean allowCustom, Consumer<List<String>> onDone) {
        super(title);
        this.parent = parent;
        this.options = new ArrayList<>(options);
        this.selected = new LinkedHashSet<>(selected == null ? List.of() : selected);
        this.multi = multi;
        this.allowCustom = allowCustom;
        this.onDone = onDone;
        // Ids already chosen but no longer offered (removed addon, custom id) stay visible and removable.
        for (String id : this.selected) {
            if (this.options.stream().noneMatch(o -> o.id().equals(id))) {
                this.options.add(0, new Option(id, id, Component.translatable(KEY + "custom_entry").getString(), 0xFF8899AA,
                        Component.translatable(KEY + "group_custom").getString()));
            }
        }
    }

    @Override
    protected void init() {
        pw = Math.min(460, width - 16);
        ph = Math.min(360, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int x = px + 10, w = pw - 20;
        search = addRenderableWidget(new EditBox(font, x, py + 28, w, 18, Component.translatable(KEY + "search")));
        search.setHint(Component.translatable(KEY + "search_hint"));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(v -> { query = v == null ? "" : v; scroll = 0; rebuildRows(); });
        int top = py + 52;
        if (allowCustom) {
            int addW = 56;
            custom = addRenderableWidget(new EditBox(font, x, top, w - addW - 4, 18, Component.translatable(KEY + "custom")));
            custom.setHint(Component.translatable(KEY + "custom_hint"));
            custom.setMaxLength(128);
            custom.setValue(customDraft);
            custom.setResponder(v -> customDraft = v == null ? "" : v);
            addRenderableWidget(Button.builder(Component.translatable(KEY + "add_custom"), b -> addCustom())
                    .bounds(x + w - addW, top - 1, addW, 20)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "custom_tooltip"))).build());
            top += 24;
        }
        listTop = top;
        int footerY = py + ph - 28;
        listBottom = footerY - 6;
        int bw = Math.max(48, Math.min(76, (w - 12) / 4));
        if (multi) {
            addRenderableWidget(Button.builder(Component.translatable(KEY + "select_visible"), b -> {
                for (Option o : filtered()) selected.add(o.id());
                rebuildRows();
            }).bounds(x, footerY, bw, 20).build());
            addRenderableWidget(Button.builder(Component.translatable(KEY + "clear"), b -> {
                selected.clear();
                rebuildRows();
            }).bounds(x + bw + 4, footerY, bw, 20)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "clear_tooltip"))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x + w - bw * 2 - 4, footerY, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            onDone.accept(new ArrayList<>(selected));
            onClose();
        }).bounds(x + w - bw, footerY, bw, 20).build());
        rebuildRows();
        setInitialFocus(search);
    }

    private void addCustom() {
        String id = customDraft.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")) {
            error = Component.translatable(KEY + "custom_invalid").getString();
            return;
        }
        error = "";
        if (options.stream().noneMatch(o -> o.id().equals(id))) {
            options.add(0, new Option(id, id, Component.translatable(KEY + "custom_entry").getString(), 0xFF8899AA,
                    Component.translatable(KEY + "group_custom").getString()));
        }
        if (!multi) selected.clear();
        selected.add(id);
        customDraft = "";
        if (custom != null) custom.setValue("");
        rebuildRows();
    }

    private List<Option> filtered() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return options;
        List<Option> out = new ArrayList<>();
        for (Option o : options) {
            if (o.label().toLowerCase(Locale.ROOT).contains(q) || o.id().toLowerCase(Locale.ROOT).contains(q)
                    || o.detail() != null && o.detail().toLowerCase(Locale.ROOT).contains(q)) out.add(o);
        }
        return out;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    private void rebuildRows() {
        for (Row row : rows) removeWidget(row);
        rows.clear();
        List<Option> list = filtered();
        scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() - visibleRows()));
        int w = pw - 20 - 6;
        for (int i = scroll; i < Math.min(list.size(), scroll + visibleRows()); i++) {
            Row row = new Row(list.get(i), px + 10, listTop + (i - scroll) * ROW_H, w);
            rows.add(row);
            addRenderableWidget(row);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (my >= listTop && my < listBottom) {
            int before = scroll;
            scroll -= (int) Math.signum(v) * 3;
            scroll = Mth.clamp(scroll, 0, Math.max(0, filtered().size() - visibleRows()));
            if (scroll != before) rebuildRows();
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        super.renderBackground(g, mx, my, delta);
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, BORDER);
        g.fill(px, py, px + pw, py + ph, PANEL);
        g.fill(px, py, px + pw, py + 2, ACCENT);
        g.drawString(font, title, px + 10, py + 10, TEXT, false);
        String count = Component.translatable(multi ? KEY + "count_multi" : KEY + "count_single",
                selected.size(), filtered().size()).getString();
        g.drawString(font, count, px + pw - 10 - font.width(count), py + 10, multi ? GOLD : MUTED, false);
        g.fill(px + 10, listTop - 2, px + pw - 10, listBottom + 1, 0x60000000);
        List<Option> list = filtered();
        if (list.isEmpty()) {
            g.drawCenteredString(font, Component.translatable(KEY + "empty"), px + pw / 2, listTop + 12, MUTED);
        }
        int max = Math.max(0, list.size() - visibleRows());
        if (max > 0) {
            int trackH = listBottom - listTop;
            int thumbH = Math.max(12, trackH * visibleRows() / list.size());
            int thumbY = listTop + (trackH - thumbH) * scroll / max;
            g.fill(px + pw - 14, listTop, px + pw - 11, listBottom, 0x40FFFFFF);
            g.fill(px + pw - 14, thumbY, px + pw - 11, thumbY + thumbH, ACCENT);
        }
        if (!error.isEmpty()) g.drawString(font, error, px + 10, py + ph - 40, 0xFFFF7070, false);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** One selectable line: check mark, colour swatch, label and a muted detail on the right. */
    private final class Row extends AbstractWidget {
        private final Option option;

        Row(Option option, int x, int y, int w) {
            super(x, y, w, ROW_H - 2, Component.literal(option.label()));
            this.option = option;
            if (option.detail() != null && !option.detail().isEmpty()) {
                setTooltip(Tooltip.create(Component.literal(option.label() + "\n" + option.id()
                        + "\n" + option.detail())));
            }
        }

        private boolean chosen() {
            return selected.contains(option.id());
        }

        @Override
        public void onClick(double mx, double my) {
            if (multi) {
                if (!selected.remove(option.id())) selected.add(option.id());
            } else {
                selected.clear();
                selected.add(option.id());
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
            boolean on = chosen();
            int bg = on ? 0xC0243A48 : (isHoveredOrFocused() ? 0x80303C46 : 0x401A232B);
            g.fill(getX(), getY(), getX() + width, getY() + height, bg);
            if (isFocused()) g.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
            int box = getX() + 5, by = getY() + (height - 10) / 2;
            g.renderOutline(box, by, 10, 10, on ? ACCENT : 0xFF6B7C88);
            if (on) g.fill(box + 2, by + 2, box + 8, by + 8, multi ? ACCENT : GOLD);
            g.fill(getX() + 20, getY() + 4, getX() + 23, getY() + height - 4, option.color() | 0xFF000000);
            int textX = getX() + 28;
            String detail = option.group() != null && !option.group().isEmpty() ? option.group() : option.detail();
            int detailW = detail == null ? 0 : Math.min(font.width(detail), width * 2 / 5);
            String label = font.plainSubstrByWidth(option.label(), width - 34 - detailW - 8);
            g.drawString(font, label, textX, getY() + (height - 8) / 2, on ? 0xFFFFFFFF : TEXT, false);
            if (detail != null && !detail.isEmpty()) {
                String d = font.plainSubstrByWidth(detail, detailW);
                g.drawString(font, d, getX() + width - 6 - font.width(d), getY() + (height - 8) / 2, MUTED, false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(option.label()));
            output.add(NarratedElementType.HINT, Component.translatable(chosen() ? KEY + "selected" : KEY + "not_selected"));
        }
    }
}
