package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Vertical scroll offset helper for a rectangular viewport of form fields. */
public class ScrollablePanel {
    private static final int SCROLLBAR_W = 3;

    private int x, y, width, viewportH;
    private int contentH;
    private int scroll;

    public void setBounds(int x, int y, int width, int viewportH) {
        this.x = x;
        this.y = y;
        this.width = Math.max(1, width);
        this.viewportH = Math.max(1, viewportH);
        clamp();
    }

    public void setContentHeight(int contentH) {
        this.contentH = Math.max(0, contentH);
        clamp();
    }

    public int getScroll() {
        return scroll;
    }

    public void setScroll(int scroll) {
        this.scroll = scroll;
        clamp();
    }

    public int maxScroll() {
        return Math.max(0, contentH - viewportH);
    }

    public void clamp() {
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /** Map content-local Y to screen Y. */
    public int applyY(int contentY) {
        return y + contentY - scroll;
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + viewportH;
    }

    public boolean mouseScrolled(double mx, double my, double verticalAmount) {
        if (!isMouseOver(mx, my) || maxScroll() <= 0) {
            return false;
        }
        int delta = verticalAmount > 0 ? -12 : (verticalAmount < 0 ? 12 : 0);
        if (delta == 0) {
            return true;
        }
        scroll = Mth.clamp(scroll + delta, 0, maxScroll());
        return true;
    }

    public void renderScrollbar(GuiGraphics g) {
        if (maxScroll() <= 0) {
            return;
        }
        int trackX = x + width - SCROLLBAR_W;
        g.fill(trackX, y, trackX + SCROLLBAR_W, y + viewportH, 0x30101820);
        int thumbH = Math.max(12, viewportH * viewportH / Math.max(1, contentH));
        int thumbY = y + (viewportH - thumbH) * scroll / maxScroll();
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xA057C6D6);
    }
}
