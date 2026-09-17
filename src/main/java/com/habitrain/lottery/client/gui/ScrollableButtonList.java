package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Windowed side-list of selection buttons with wheel scroll + thin scrollbar.
 * Does not own +/- controls — pin those under {@link #viewportBottom()}.
 */
public class ScrollableButtonList {
    private static final int DEFAULT_ROW_H = 22;
    private static final int SCROLLBAR_W = 3;

    private int x;
    private int y;
    private int width;
    private int viewportH;
    private int rowH = DEFAULT_ROW_H;

    private final List<String> labels = new ArrayList<>();
    private int selectedIndex;
    private int scrollOffset;
    private IntConsumer onSelect = idx -> {};

    public void setBounds(int x, int y, int w, int viewportH) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, w);
        this.viewportH = Math.max(rowH, viewportH);
    }

    public void setRowHeight(int rowH) {
        this.rowH = Math.max(12, rowH);
    }

    public void setItems(List<String> labels, int selectedIndex) {
        this.labels.clear();
        if (labels != null) {
            this.labels.addAll(labels);
        }
        this.selectedIndex = this.labels.isEmpty() ? 0 : Mth.clamp(selectedIndex, 0, this.labels.size() - 1);
        clampScroll();
    }

    public void setOnSelect(IntConsumer onSelect) {
        this.onSelect = onSelect == null ? idx -> {} : onSelect;
    }

    public int getScroll() {
        return scrollOffset;
    }

    public void setScroll(int scroll) {
        this.scrollOffset = scroll;
        clampScroll();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public int visibleRows() {
        return Math.max(1, viewportH / rowH);
    }

    public int maxScroll() {
        return Math.max(0, labels.size() - visibleRows());
    }

    public void clampScroll() {
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll());
    }

    /** Keep selected row inside the visible window (e.g. after +). */
    public void ensureSelectedVisible() {
        if (labels.isEmpty()) {
            scrollOffset = 0;
            return;
        }
        selectedIndex = Mth.clamp(selectedIndex, 0, labels.size() - 1);
        int visible = visibleRows();
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + visible) {
            scrollOffset = selectedIndex - visible + 1;
        }
        clampScroll();
    }

    public int viewportBottom() {
        return y + visibleRows() * rowH;
    }

    public boolean isMouseOver(double mx, double my) {
        int h = visibleRows() * rowH;
        return mx >= x && mx < x + width && my >= y && my < y + h;
    }

    /**
     * Creates one Button per visible row and registers via {@code addWidget}.
     * {@code onBeforeSelect} runs before index change (e.g. flush edit fields).
     */
    public void rebuildWidgets(Consumer<AbstractWidget> addWidget, Runnable onBeforeSelect) {
        clampScroll();
        int visible = visibleRows();
        int btnW = width - (labels.size() > visible ? SCROLLBAR_W + 1 : 0);
        btnW = Math.max(20, btnW);
        for (int i = 0; i < visible; i++) {
            int idx = scrollOffset + i;
            if (idx >= labels.size()) {
                break;
            }
            final int fi = idx;
            String label = labels.get(idx);
            int by = y + i * rowH;
            addWidget.accept(Button.builder(Component.literal(label), b -> {
                if (onBeforeSelect != null) {
                    onBeforeSelect.run();
                }
                selectedIndex = fi;
                onSelect.accept(fi);
            }).bounds(x, by, btnW, 20).build());
        }
    }

    public boolean mouseScrolled(double mx, double my, double verticalAmount) {
        if (!isMouseOver(mx, my) || labels.size() <= visibleRows()) {
            return false;
        }
        int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
        if (delta == 0) {
            return true;
        }
        scrollOffset = Mth.clamp(scrollOffset + delta, 0, maxScroll());
        return true;
    }

    public void renderScrollbar(GuiGraphics g) {
        int visible = visibleRows();
        if (labels.size() <= visible) {
            return;
        }
        int h = visible * rowH;
        int trackX = x + width - SCROLLBAR_W;
        g.fill(trackX, y, trackX + SCROLLBAR_W, y + h, 0x30101820);
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int thumbH = Math.max(12, h * visible / labels.size());
        int thumbY = y + (h - thumbH) * scrollOffset / max;
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xA057C6D6);
    }
}
