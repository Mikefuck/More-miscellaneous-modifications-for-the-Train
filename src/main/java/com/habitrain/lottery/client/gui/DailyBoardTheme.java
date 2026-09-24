package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/** Flat, square-edged dispatch board primitives. Colors use ARGB. */
final class DailyBoardTheme {

    private DailyBoardTheme() {
    }

    // ---------------------------------------------------------------------
    // 纸张与卡片
    // ---------------------------------------------------------------------
    static final int PAPER_TOP = 0xFFE8EDF2;
    static final int PAPER_BOTTOM = 0xFFE8EDF2;
    static final int PAPER_BORDER = 0xFFB6C3D0;
    static final int CARD_TOP = 0xFFFFFFFF;
    static final int CARD_BOTTOM = 0xFFFFFFFF;
    static final int CARD_LINE = 0xFFCED7E0;
    static final int TRACK = 0xFFDCE3EA;

    // ---------------------------------------------------------------------
    // 文字
    // ---------------------------------------------------------------------
    static final int INK = 0xFF20364B;
    static final int INK_SOFT = 0xFF4B6175;
    static final int MUTED = 0xFF5E7183;
    static final int FAINT = 0xFFA0AFBD;

    // ---------------------------------------------------------------------
    // 语义色
    // ---------------------------------------------------------------------
    static final int GOLD = 0xFF9C4B16;
    static final int AMBER = 0xFFF0B44C;
    static final int AMBER_DEEP = 0xFFB85319;
    static final int AMBER_SOFT = 0xFFFFE9D6;
    static final int GREEN = 0xFF377451;
    static final int GREEN_SOFT = 0xFFE7F3EA;
    static final int BLUE = 0xFF315F89;
    static final int BLUE_SOFT = 0xFFEAF0F9;
    static final int VIOLET = 0xFF8E6FD0;
    static final int VIOLET_SOFT = 0xFFF1EBFA;
    static final int TEAL = 0xFFB85319;
    static final int TEAL_SOFT = 0xFFFFE9D6;
    static final int WARN = 0xFFC4553F;

    // ---------------------------------------------------------------------
    // 窗口
    // ---------------------------------------------------------------------

    static final int NAVY = 0xFF20364B;
    static final int NAV_TEXT = 0xFFE1E9F1;

    static void window(GuiGraphics g, DailyBoardLayout l) {
        BoardRect p = l.panel();
        g.fill(p.x()+4, p.y()+4, p.right()+4, p.bottom()+4, 0x66000000);
        box(g, p, PAPER_TOP, PAPER_BORDER);
        BoardRect rail = l.rail();
        g.fill(rail.x(), rail.y(), l.sidebar() ? rail.right() : p.right(), rail.bottom(), NAVY);
        g.fill(p.x(), p.y(), p.right(), p.y()+2, AMBER_DEEP);
    }

    static void box(GuiGraphics g, BoardRect r, int fill, int border) {
        if (r.isEmpty()) return;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), border);
        g.fill(r.x()+1, r.y()+1, r.right()-1, r.bottom()-1, fill);
    }

    static void card(GuiGraphics g, BoardRect r) { box(g, r, CARD_TOP, CARD_LINE); }
    static void card(GuiGraphics g, BoardRect r, int radius) { card(g, r); }
    static void softCard(GuiGraphics g, BoardRect r, int radius, int top, int bottom, int border) {
        box(g, r, top, border);
    }
    static void rightPage(GuiGraphics g, BoardRect r, int radius) { card(g, r); }

static void hairline(GuiGraphics g, int x0, int x1, int y, int color) {
        if (x1 <= x0) {
            return;
        }
        g.fill(x0, y, x1, y + 1, color);
    }

/**
     * 任务行底部的进度条：贴在卡片下沿的一条细带。
     *
     * <p>把进度从文字区挪到卡片边缘，行内就能腾出两整行文字，任务是「做完了没」一眼可见，
     * 又不和标题、奖励胶囊抢横向空间。</p>
     */

    // ---------------------------------------------------------------------
    // 文字
    // ---------------------------------------------------------------------

    static void text(GuiGraphics g, Font font, String value, int x, int y, int color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        g.drawString(font, value, x, y, color, false);
    }

    static void textCentered(GuiGraphics g, Font font, Component value, int cx, int y, int color) {
        g.drawString(font, value, cx - font.width(value) / 2, y, color, false);
    }

static void textScaled(GuiGraphics g, Font font, String value, int x, int y, float scale, int color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, value, 0, 0, color, false);
        g.pose().popPose();
    }

static String fit(Font font, String value, int maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String dots = "…";
        int room = Math.max(0, maxWidth - font.width(dots));
        return font.plainSubstrByWidth(value, room) + dots;
    }

    // ---------------------------------------------------------------------
    // 进度件
    // ---------------------------------------------------------------------

/** 任务点：每项任务一枚菱形，已领取实心、未领取空心。与示例里的四枚菱形位置一致。 */

    static void progressBar(GuiGraphics g, int x0, int x1, int y, int h, float ratio,
                            int track, int fill) {
        if (x1 <= x0) {
            return;
        }
        g.fill(x0, y, x1, y + h, track);
        int w = (int) ((x1 - x0) * Mth.clamp(ratio, 0.0F, 1.0F));
        if (w > 0) {
            g.fill(x0, y, x0 + w, y + h, fill);
        }
    }

    // ---------------------------------------------------------------------
    // 奖励块 / 标签 / 按钮
    // ---------------------------------------------------------------------

/** 标签胶囊：奖励说明、来源分类都用它。 */
    static void chip(GuiGraphics g, Font font, BoardRect r, String label, int fill, int border,
                     int textColor) {
        if (r.isEmpty()) {
            return;
        }
        softCard(g, r, 4, fill, GuiFx.shade(fill, -0.05F), border);
        text(g, font, fit(font, label, r.w() - 8), r.x() + 4, r.y() + (r.h() - 8) / 2, textColor);
    }

static void button(GuiGraphics g, Font font, BoardRect r, Component label, int fill, int border,
                       int textColor, float hover) {
        if (r.isEmpty()) {
            return;
        }
        int top = GuiFx.mix(fill, 0xFFFFFFFF, Mth.clamp(hover, 0.0F, 1.0F) * 0.24F);
        softCard(g, r, 5, top, GuiFx.shade(fill, -0.12F), border);
        textCentered(g, font, label, r.cx(), r.y() + (r.h() - 8) / 2, textColor);
    }

static void scrollbar(GuiGraphics g, int x, BoardRect area, double scroll, int maxScroll) {
        if (maxScroll <= 0 || area.isEmpty()) {
            return;
        }
        int trackH = area.h();
        int thumbH = Math.max(14, (int) (trackH * (trackH / (double) (trackH + maxScroll))));
        int thumbY = area.y() + (int) ((trackH - thumbH) * Mth.clamp(scroll / maxScroll, 0.0D, 1.0D));
        g.fill(x, area.y(), x + 3, area.bottom(), TRACK);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, INK_SOFT);
    }

static void tooltip(GuiGraphics g, Font font, List<FormattedCharSequence> lines, int mouseX,
                        int mouseY, BoardRect bounds) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int w = 0;
        for (FormattedCharSequence line : lines) {
            w = Math.max(w, font.width(line));
        }
        int h = lines.size() * 10 + 6;
        int x = Mth.clamp(mouseX + 10, bounds.x() + 2,
                Math.max(bounds.x() + 2, bounds.right() - w - 14));
        int y = Mth.clamp(mouseY + 8, bounds.y() + 2, Math.max(bounds.y() + 2, bounds.bottom() - h - 4));
        box(g, new BoardRect(x,y,w+12,h), CARD_TOP, INK_SOFT);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 6, y + 4 + i * 10, INK, false);
        }
    }
}
