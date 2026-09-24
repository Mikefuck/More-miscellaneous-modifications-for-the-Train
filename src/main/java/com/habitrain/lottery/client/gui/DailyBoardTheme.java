package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 「今日任务」页面的浅色纸张主题：色板与基础绘制件。
 *
 * <p>本类只服务 {@link DailyTaskScreen}：模组其他页面走 {@link CardUiStyle} 的深色科技风，
 * 这里刻意另起一套「纸 + 卡片」的浅色规范，避免把两套配色互相污染。所有方法都是无状态
 * 静态方法，颜色一律 {@code 0xAARRGGBB}。</p>
 *
 * <p>视觉约定与「不要在边框上堆装饰」一致：窗口只有 1px 描边 + 一层柔和投影，卡片只有
 * 1px 描边，没有金色引线、扫光或角饰；层次全部靠留白、圆角与一点点底色差表达。</p>
 */
final class DailyBoardTheme {

    private DailyBoardTheme() {
    }

    // ---------------------------------------------------------------------
    // 纸张与卡片
    // ---------------------------------------------------------------------
    static final int PAPER_TOP = 0xFFF5F9F8;
    static final int PAPER_BOTTOM = 0xFFF5F9F8;
    static final int PAPER_BORDER = 0xFFD5E4E0;
    static final int CARD_TOP = 0xFFFFFFFF;
    static final int CARD_BOTTOM = 0xFFFFFFFF;
    static final int CARD_LINE = 0xFFDFE9E6;
    static final int TRACK = 0xFFE3EEEA;

    // ---------------------------------------------------------------------
    // 文字
    // ---------------------------------------------------------------------
    static final int INK = 0xFF203D38;
    static final int INK_SOFT = 0xFF536F69;
    static final int MUTED = 0xFF6C817C;
    static final int FAINT = 0xFFBBCDC7;

    // ---------------------------------------------------------------------
    // 语义色
    // ---------------------------------------------------------------------
    static final int GOLD = 0xFFC08A2E;
    static final int AMBER = 0xFFF0B44C;
    static final int AMBER_DEEP = 0xFFD2921F;
    static final int AMBER_SOFT = 0xFFE4F3ED;
    static final int GREEN = 0xFF4C9A63;
    static final int GREEN_SOFT = 0xFFE7F3EA;
    static final int BLUE = 0xFF5D86C4;
    static final int BLUE_SOFT = 0xFFEAF0F9;
    static final int VIOLET = 0xFF8E6FD0;
    static final int VIOLET_SOFT = 0xFFF1EBFA;
    static final int TEAL = 0xFF197D69;
    static final int TEAL_SOFT = 0xFFE4F3ED;
    static final int WARN = 0xFFC4553F;

    // ---------------------------------------------------------------------
    // 窗口
    // ---------------------------------------------------------------------

    /** 窗口：一层柔和投影 + 纸面渐变 + 1px 描边。投影只是把窗口从世界背景里托起来。 */
    static void window(GuiGraphics g, DailyBoardLayout l) {
        BoardRect p = l.panel();
        for (int i = 5; i >= 1; i--) {
            GuiFx.roundRect(g, p.x() - i, p.y() + i + 1, p.right() + i, p.bottom() + i + 2, 10 + i,
                    0x0C2A2418);
        }
        GuiFx.roundGradient(g, p.x(), p.y(), p.right(), p.bottom(), 10, PAPER_TOP, PAPER_BOTTOM);
        GuiFx.roundOutline(g, p.x(), p.y(), p.right(), p.bottom(), 10, PAPER_BORDER);
    }

    /** 普通卡片：白底 + 1px 描边。页面里所有「一块内容」都用它，不再叠加额外装饰。 */
    static void card(GuiGraphics g, BoardRect r) {
        card(g, r, 8);
    }

    static void card(GuiGraphics g, BoardRect r, int radius) {
        if (r.isEmpty()) {
            return;
        }
        GuiFx.roundGradient(g, r.x(), r.y(), r.right(), r.bottom(), radius, CARD_TOP, CARD_BOTTOM);
        GuiFx.roundOutline(g, r.x(), r.y(), r.right(), r.bottom(), radius, CARD_LINE);
    }

    /** 带底色的卡片：用于奖励块、选中态等需要一点点色相区分的地方。 */
    static void softCard(GuiGraphics g, BoardRect r, int radius, int top, int bottom, int border) {
        if (r.isEmpty()) {
            return;
        }
        GuiFx.roundGradient(g, r.x(), r.y(), r.right(), r.bottom(), radius, top, bottom);
        GuiFx.roundOutline(g, r.x(), r.y(), r.right(), r.bottom(), radius, border);
    }

    /** 跨版左页：暖一点的纸色，和右页的冷白形成「两页」的差别。 */
    static void leftPage(GuiGraphics g, BoardRect r, int radius) {
        softCard(g, r, radius, 0xFFF6F0E2, 0xFFEFE7D6, 0xFFDFD4BE);
    }

    /** 跨版右页：更接近白纸，任务卡片压在上面才有层次。 */
    static void rightPage(GuiGraphics g, BoardRect r, int radius) {
        softCard(g, r, radius, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDFE9E6);
    }

    /** 1px 分隔线；两端不需要收边，浅色底上一条线就够。 */
    static void hairline(GuiGraphics g, int x0, int x1, int y, int color) {
        if (x1 <= x0) {
            return;
        }
        g.fill(x0, y, x1, y + 1, color);
    }

    /**
     * 书脊：两侧页边向中间压暗的横向渐变 + 中间一条 1px 折线。
     *
     * <p>这是「跨版」版式唯一的结构性装饰，用来把左右两页分开；不使用任何角饰或描边框。</p>
     */
    static void gutter(GuiGraphics g, int spineX, int y0, int y1, int spread) {
        if (y1 <= y0 || spread <= 0) {
            return;
        }
        GuiFx.gradientFadeX(g, spineX - spread, y0, spineX, y1, 0x331F1A12, 0.0F, 1.0F, spread);
        GuiFx.gradientFadeX(g, spineX, y0, spineX + spread, y1, 0x331F1A12, 1.0F, 0.0F, spread);
        g.fill(spineX, y0, spineX + 1, y1, 0x1F1F1A12);
    }

    /**
     * 任务行底部的进度条：贴在卡片下沿的一条细带。
     *
     * <p>把进度从文字区挪到卡片边缘，行内就能腾出两整行文字，任务是「做完了没」一眼可见，
     * 又不和标题、奖励胶囊抢横向空间。</p>
     */
    static void rowStrip(GuiGraphics g, BoardRect r, float ratio, int inset, int height,
                         int track, int fill) {
        if (r.isEmpty()) {
            return;
        }
        int x0 = r.x() + inset;
        int x1 = r.right() - inset;
        int y = r.bottom() - height - 1;
        if (x1 - x0 < 6) {
            return;
        }
        progressBar(g, x0, x1, y, height, ratio, track, fill);
    }

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

    /** 放大文字：用于标题与关键数值。{@code scale} 同时参与宽度计算，保证居中不偏。 */
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

    static void textScaledCentered(GuiGraphics g, Font font, String value, int cx, int y,
                                   float scale, int color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        textScaled(g, font, value, cx - (int) (font.width(value) * scale / 2.0F), y, scale, color);
    }

    /** 超宽文本截断成「…」结尾；语言文件与接入模组都可能给出很长的说明。 */
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

    /**
     * 圆环进度：从 12 点方向顺时针点亮 {@code span} 比例。
     *
     * <p>原版 {@link GuiGraphics} 没有画弧的能力，这里按角度步进铺小方块；步数按半径取，
     * 保证相邻两块互相重叠、看上去是连续的圆环。</p>
     */
    static void ring(GuiGraphics g, int cx, int cy, int diameter, int thickness,
                     double from, double span, int track, int fill) {
        int r = Math.max(3, diameter / 2);
        int t = Mth.clamp(thickness, 1, Math.max(1, r - 1));
        int steps = Mth.clamp(r * 6, 32, 240);
        int mid = r - t / 2;
        double twoPi = Math.PI * 2.0;
        double start = from - Math.floor(from);
        double lit = Mth.clamp((float) span, 0.0F, 1.0F);
        for (int i = 0; i < steps; i++) {
            double f = (i + 0.5) / steps;
            double delta = f - start;
            delta -= Math.floor(delta);
            int color = delta < lit ? fill : track;
            double a = f * twoPi;
            int x = cx + (int) Math.round(mid * Math.sin(a)) - t / 2;
            int y = cy - (int) Math.round(mid * Math.cos(a)) - t / 2;
            g.fill(x, y, x + t, y + t, color);
        }
    }

    static void ring(GuiGraphics g, BoardRect box, int thickness, double span, int track, int fill) {
        ring(g, box.cx(), box.cy(), Math.min(box.w(), box.h()), thickness, 0.0D, span, track, fill);
    }

    /** 任务点：每项任务一枚菱形，已领取实心、未领取空心。与示例里的四枚菱形位置一致。 */
    static void pips(GuiGraphics g, BoardRect row, int count, int filled, int pipR, int pipGap,
                     int on, int off) {
        if (row.isEmpty() || count <= 0) {
            return;
        }
        int x = row.x() + pipR;
        int cy = row.cy();
        for (int i = 0; i < count; i++) {
            if (i < filled) {
                GuiFx.diamond(g, x, cy, pipR, pipR, on);
            } else {
                GuiFx.diamondOutline(g, x, cy, pipR, pipR, off);
            }
            x += pipR * 2 + pipGap;
        }
    }

    static void progressBar(GuiGraphics g, int x0, int x1, int y, int h, float ratio,
                            int track, int fill) {
        if (x1 <= x0) {
            return;
        }
        GuiFx.roundedBar(g, x0, y, x1, y + h, h / 2, track);
        int w = (int) ((x1 - x0) * Mth.clamp(ratio, 0.0F, 1.0F));
        if (w > 0) {
            GuiFx.roundedBar(g, x0, y, x0 + Math.max(h, w), y + h, h / 2, fill);
        }
    }

    // ---------------------------------------------------------------------
    // 奖励块 / 标签 / 按钮
    // ---------------------------------------------------------------------

    /**
     * 奖励块：上方物品图标、下方数量，用于摘要区的「额外奖励」。
     *
     * <p>数量可能很长（金币五位数），按可用宽度等比缩小而不是截断，避免「12325」变成「12…」。</p>
     */
    static void itemTile(GuiGraphics g, Font font, BoardRect r, ItemStack icon, String count,
                         int top, int bottom, int border, int countColor) {
        if (r.isEmpty()) {
            return;
        }
        softCard(g, r, 6, top, bottom, border);
        int iconSize = Math.max(8, Math.min(Math.min(r.w() - 8, r.h() - 12), 32));
        if (icon != null && !icon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(r.x() + (r.w() - iconSize) / 2.0F, r.y() + 2.0F, 0.0F);
            g.pose().scale(iconSize / 16.0F, iconSize / 16.0F, 1.0F);
            g.renderFakeItem(icon, 0, 0);
            g.pose().popPose();
        }
        if (count == null || count.isEmpty()) {
            return;
        }
        float scale = Math.min(1.0F, (r.w() - 4) / (float) Math.max(1, font.width(count)));
        textScaledCentered(g, font, count, r.cx(), r.bottom() - 10, scale, countColor);
    }

    /** 标签胶囊：奖励说明、来源分类都用它。 */
    static void chip(GuiGraphics g, Font font, BoardRect r, String label, int fill, int border,
                     int textColor) {
        if (r.isEmpty()) {
            return;
        }
        softCard(g, r, 4, fill, GuiFx.shade(fill, -0.05F), border);
        text(g, font, fit(font, label, r.w() - 8), r.x() + 4, r.y() + (r.h() - 8) / 2, textColor);
    }

    /** 实心按钮：用于「领取」。悬停只提亮填充，不加外发光，保持平面感。 */
    static void button(GuiGraphics g, Font font, BoardRect r, Component label, int fill, int border,
                       int textColor, float hover) {
        if (r.isEmpty()) {
            return;
        }
        int top = GuiFx.mix(fill, 0xFFFFFFFF, Mth.clamp(hover, 0.0F, 1.0F) * 0.24F);
        softCard(g, r, 5, top, GuiFx.shade(fill, -0.12F), border);
        textCentered(g, font, label, r.cx(), r.y() + (r.h() - 8) / 2, textColor);
    }

    /** 极简滚动条：2px 轨道 + 2px 滑块，只在内容真的超出时出现。 */
    static void scrollbar(GuiGraphics g, int x, BoardRect area, double scroll, int maxScroll) {
        if (maxScroll <= 0 || area.isEmpty()) {
            return;
        }
        int trackH = area.h();
        int thumbH = Math.max(14, (int) (trackH * (trackH / (double) (trackH + maxScroll))));
        int thumbY = area.y() + (int) ((trackH - thumbH) * Mth.clamp(scroll / maxScroll, 0.0D, 1.0D));
        GuiFx.roundedBar(g, x, area.y(), x + 2, area.bottom(), 1, 0x141F2430);
        GuiFx.roundedBar(g, x, thumbY, x + 2, thumbY + thumbH, 1, 0x552F3A55);
    }

    /** 自绘气泡提示：摘要区奖励块这类非控件区域没有原生 tooltip 通道。 */
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
        GuiFx.roundRect(g, x, y, x + w + 12, y + h, 5, 0xF7FFFFFF);
        GuiFx.roundOutline(g, x, y, x + w + 12, y + h, 5, CARD_LINE);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 6, y + 4 + i * 10, INK, false);
        }
    }
}
