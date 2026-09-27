package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.crate.CrateCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Searchable crate picker shared by profile, appearance and reward settings. */
public final class CrateListScreen extends Screen {
    private final Screen parent;
    private final List<String> ids;
    private final Map<String, String> draftNames;
    private final Consumer<String> selected;
    private final List<String> filtered = new ArrayList<>();
    private String query = "";
    private int scroll, x, w, rowsTop, visibleRows;
    private boolean suppressNestedBackground;
    private EditBox search;

    public CrateListScreen(Screen parent, List<String> ids, Map<String, String> draftNames, Consumer<String> selected) {
        super(Component.translatable("screen.habitrain_lottery.crate_manage.select"));
        this.parent = parent; this.ids = List.copyOf(ids); this.draftNames = Map.copyOf(draftNames);
        this.selected = selected;
    }

    private String name(String id) {
        String draftName = draftNames.get(id);
        if (draftName != null && !draftName.isBlank()) return Component.translatable(draftName).getString();
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        return entry == null ? id : Component.translatable(entry.nameKey()).getString();
    }

    @Override protected void init() { rebuild(); setInitialFocus(search); }

    private void rebuild() {
        clearWidgets();
        x = width < 420 ? 8 : 16; w = width - x * 2;
        rowsTop = 64; visibleRows = Math.max(1, (height - rowsTop - 32) / 24);
        filtered.clear();
        String searchText = query.trim().toLowerCase(Locale.ROOT);
        for (String id : ids) {
            if (searchText.isBlank() || id.contains(searchText) || name(id).toLowerCase(Locale.ROOT).contains(searchText))
                filtered.add(id);
        }
        scroll = Mth.clamp(scroll, 0, Math.max(0, filtered.size() - visibleRows));
        search = new EditBox(font, x, 35, w, 20,
                Component.translatable("screen.habitrain_lottery.crate_manage.search"));
        search.setMaxLength(64); search.setValue(query);
        search.setHint(Component.translatable("screen.habitrain_lottery.crate_manage.search"));
        search.setResponder(value -> {
            query = value; scroll = 0; rebuild();
            setFocused(search);
            search.setCursorPosition(query.length());
        });
        addRenderableWidget(search);
        for (int i = scroll; i < filtered.size() && i < scroll + visibleRows; i++) {
            String id = filtered.get(i);
            CrateCatalog.Entry entry = CrateCatalog.find(id);
            Component title = Component.literal(name(id) + "  ·  " + id + (entry != null && entry.archived() ? " ⌁" : ""));
            String fullTitle = title.getString();
            String shown = font.width(fullTitle) <= w - 38 ? fullTitle
                    : font.plainSubstrByWidth(fullTitle, Math.max(1, w - 38 - font.width("…"))) + "…";
            Button button = Button.builder(Component.literal(shown), b -> {
                selected.accept(id);
                if (minecraft != null) minecraft.setScreen(parent);
            }).bounds(x + 22, rowsTop + (i - scroll) * 24, w - 22, 20).build();
            button.setTooltip(Tooltip.create(title.copy().append("\n").append(
                    entry == null ? Component.literal(id) : Component.translatable(entry.description()))));
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x, height - 24, w, 20).build());
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (my >= rowsTop && my < height - 26) {
            scroll = Mth.clamp(scroll + (vertical < 0 ? 1 : -1), 0, Math.max(0, filtered.size() - visibleRows));
            rebuild(); return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.fill(0, 0, width, height, 0xD010141A);
        g.fill(0, 0, width, 30, 0xF0111A22);
        g.fill(0, 29, width, 31, 0xFF57C6D6);
        g.fill(x - 4, 31, x + w + 4, height - 28, 0xE51B242C);
        g.fill(0, height - 28, width, height, 0xE011181F);
        g.drawCenteredString(font, getTitle(), width / 2, 11, 0xFFFFFFFF);
        if (filtered.isEmpty()) g.drawCenteredString(font,
                Component.translatable("screen.habitrain_lottery.crate_manage.no_matches"), width / 2, rowsTop + 12, 0xFFB1C4D0);
        if (filtered.size() > visibleRows) {
            int track = visibleRows * 24 - 4;
            int thumb = Math.max(12, track * visibleRows / filtered.size());
            int top = rowsTop + (track - thumb) * scroll / (filtered.size() - visibleRows);
            g.fill(x + w + 1, rowsTop, x + w + 3, rowsTop + track, 0xFF30404B);
            g.fill(x + w + 1, top, x + w + 3, top + thumb, 0xFF72CFD7);
        }
        for (int i = scroll; i < filtered.size() && i < scroll + visibleRows; i++) {
            CrateCatalog.Entry entry = CrateCatalog.find(filtered.get(i));
            g.fill(x + 2, rowsTop + (i - scroll) * 24 + 3,
                    x + 18, rowsTop + (i - scroll) * 24 + 19,
                    entry == null ? 0xFF777777 : entry.color());
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
