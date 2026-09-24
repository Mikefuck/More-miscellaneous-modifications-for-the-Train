package com.habitrain.lottery.client.gui;

/** Task dispatch layout with adaptive navigation and four explicit status filters. */
record DailyBoardLayout(boolean compact, int pad, int gap,
                        BoardRect panel, BoardRect rail, BoardRect content, BoardRect close,
                        BoardRect summary, BoardRect body, int pagePad,
                        BoardRect title, BoardRect subtitle, BoardRect clock,
                        BoardRect toolbar, BoardRect list, BoardRect footer, BoardRect footerAction,
                        BoardRect filterAll, BoardRect filterOpen, BoardRect filterDone,
                        boolean showFooter, int rowH, int rowStride,
                        int cardCols, int cardH, int cardGap, int sourceRowH, int chipH, int chipGap) {
    static final int TABS = 3;
    static final int FILTERS = 4;

    static DailyBoardLayout of(int width, int height, int taskCount) {
        int pw = Math.min(width - 12, 820), ph = Math.min(height - 12, 520);
        boolean side = pw >= 548 && ph >= 288;
        boolean compact = pw < 620 || ph < 360;
        int pad = 10, gap = 8;
        BoardRect panel = new BoardRect((width-pw)/2, (height-ph)/2, pw, ph);
        BoardRect rail = side
                ? new BoardRect(panel.x(), panel.y(), 108, ph)
                : new BoardRect(panel.x(), panel.y(), pw-36, 32);
        BoardRect close = new BoardRect(panel.right()-28, panel.y()+6, 22, 22);
        int cx = side ? rail.right()+16 : panel.x()+pad;
        int cy = side ? panel.y()+38 : rail.bottom()+gap;
        BoardRect content = new BoardRect(cx, cy, panel.right()-pad-cx, panel.bottom()-pad-cy);
        int summaryH = compact ? 44 : 66;
        BoardRect summary = new BoardRect(content.x(), content.y(), content.w(), summaryH);
        BoardRect body = new BoardRect(content.x(), summary.bottom()+gap, content.w(),
                content.bottom()-summary.bottom()-gap);
        BoardRect title = new BoardRect(summary.x(), summary.y()+2, summary.w()-116, 16);
        BoardRect subtitle = new BoardRect(title.x(), title.bottom()+6, summary.w(), 10);
        BoardRect clock = new BoardRect(summary.right()-108, summary.y()+5, 108, 10);
        BoardRect toolbar = new BoardRect(body.x(), body.y(), body.w(), 24);
        int fw = (toolbar.w()-9)/4;
        BoardRect all = new BoardRect(toolbar.x(), toolbar.y(), fw, 24);
        BoardRect open = new BoardRect(all.right()+3, all.y(), fw, 24);
        BoardRect done = new BoardRect(open.right()+3, all.y(), fw, 24);
        boolean footerVisible = ph >= 330;
        BoardRect footer = new BoardRect(body.x(), body.bottom()-22, body.w(), 22);
        BoardRect action = new BoardRect(footer.right()-70, footer.y(), 70, 22);
        BoardRect list = new BoardRect(body.x(), toolbar.bottom()+8, body.w(),
                (footerVisible ? footer.y()-8 : body.bottom())-toolbar.bottom()-8);
        int rh = compact ? 68 : 82;
        return new DailyBoardLayout(compact, pad, gap, panel, rail, content, close,
                summary, body, 8, title, subtitle, clock,
                toolbar, list, footer, action, all, open, done, footerVisible, rh, rh+6,
                compact ? 2 : 3, compact ? 64 : 84, 8, compact ? 30 : 36, 22, 6);
    }

    boolean sidebar() { return rail.h() == panel.h(); }

    BoardRect tab(int index) {
        if (sidebar()) return new BoardRect(rail.x()+8, rail.y()+62+index*38, rail.w()-16, 30);
        int w = (rail.w()-12)/3;
        return new BoardRect(rail.x()+6+index*(w+3), rail.y()+4, w-3, 24);
    }

    boolean tabFits() {
        return tab(TABS-1).right() <= rail.right() && tab(TABS-1).bottom() <= rail.bottom();
    }

    BoardRect filter(int index) {
        int w = (toolbar.w()-9)/4;
        return new BoardRect(toolbar.x()+index*(w+3), toolbar.y(), w, toolbar.h());
    }

    /** 任务列表第 {@code index} 行的 y（已应用滚动偏移）。 */
    int rowY(int index, double scroll) {
        return list.y() - (int) Math.round(scroll) + index * rowStride;
    }

    /** 任务列表滚到底时能滚动的最大像素。 */
    int maxScroll(int count) {
        if (count <= 0) {
            return 0;
        }
        return Math.max(0, count * rowStride - (rowStride - rowH) - list.h());
    }

    int visibleRows() {
        return Math.max(1, (list.h() + (rowStride - rowH)) / rowStride);
    }

    /** 资产页与来源页的内容区起点。 */
    int pageTop() {
        return content.y();
    }

    /** 资产页与来源页的内容区高度。 */
    int pageHeight() {
        return Math.max(40, content.h());
    }
}

/**
 * GUI 逻辑坐标下的矩形：{@code x/y} 是左上角，{@code w/h} 恒为非负。
 *
 * <p>布局与命中测试都只通过这个类型交换位置，避免两处各自算一遍坐标。</p>
 */
record BoardRect(int x, int y, int w, int h) {

    int right() {
        return x + w;
    }

    int bottom() {
        return y + h;
    }

    int cx() {
        return x + w / 2;
    }

    int cy() {
        return y + h / 2;
    }

    boolean isEmpty() {
        return w <= 0 || h <= 0;
    }

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    BoardRect inset(int dx, int dy) {
        return new BoardRect(x + dx, y + dy, Math.max(0, w - dx * 2), Math.max(0, h - dy * 2));
    }
}
