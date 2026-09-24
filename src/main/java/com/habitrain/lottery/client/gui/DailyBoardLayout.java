package com.habitrain.lottery.client.gui;

/** Responsive daily board: horizontal navigation, compact summary, full-width task list. */
record DailyBoardLayout(boolean compact, int pad, int gap,
                        BoardRect panel, BoardRect rail, BoardRect content, BoardRect close,
                        BoardRect summary, BoardRect body, int pagePad,
                        BoardRect title, BoardRect subtitle, BoardRect clock,
                        BoardRect toolbar, BoardRect list, BoardRect footer, BoardRect footerAction,
                        BoardRect filterAll, BoardRect filterOpen, BoardRect filterDone,
                        boolean showFooter, int rowH, int rowStride,
                        int cardCols, int cardH, int cardGap, int sourceRowH, int chipH, int chipGap) {
    static final int TABS = 3;
    static final int FILTERS = 3;

    static DailyBoardLayout of(int width, int height, int taskCount) {
        int pw = Math.min(width - 12, 780), ph = Math.min(height - 12, 540);
        boolean compact = pw < 540 || ph < 360;
        int pad = compact ? 10 : 18, gap = compact ? 6 : 10;
        BoardRect panel = new BoardRect((width-pw)/2, (height-ph)/2, pw, ph);
        BoardRect rail = new BoardRect(panel.x()+pad, panel.y()+pad, pw-pad*2-28, 24);
        BoardRect close = new BoardRect(panel.right()-pad-20, rail.y()+2, 20, 20);
        BoardRect content = new BoardRect(panel.x()+pad, rail.bottom()+gap, pw-pad*2,
                panel.bottom()-pad-rail.bottom()-gap);
        int summaryH = compact ? 42 : 64;
        BoardRect summary = new BoardRect(content.x(), content.y(), content.w(), summaryH);
        BoardRect body = new BoardRect(content.x(), summary.bottom()+gap, content.w(),
                content.bottom()-summary.bottom()-gap);
        BoardRect title = new BoardRect(summary.x()+10, summary.y()+7, summary.w()-110, 15);
        BoardRect subtitle = new BoardRect(title.x(), title.bottom()+5, summary.w()-20, 10);
        BoardRect clock = new BoardRect(summary.right()-100, summary.y()+9, 90, 10);
        BoardRect toolbar = new BoardRect(body.x(), body.y(), body.w(), 22);
        int fw = compact ? 48 : 60;
        BoardRect all = new BoardRect(toolbar.x(), toolbar.y(), fw, 20);
        BoardRect open = new BoardRect(all.right()+4, all.y(), fw, 20);
        BoardRect done = new BoardRect(open.right()+4, all.y(), fw, 20);
        boolean footerVisible = ph >= 300;
        BoardRect footer = new BoardRect(body.x(), body.bottom()-22, body.w(), 22);
        BoardRect action = new BoardRect(footer.right()-70, footer.y(), 70, 20);
        BoardRect list = new BoardRect(body.x(), toolbar.bottom()+6, body.w(),
                (footerVisible ? footer.y()-6 : body.bottom())-toolbar.bottom()-6);
        int rh = compact ? 64 : 76;
        return new DailyBoardLayout(compact, pad, gap, panel, rail, content, close,
                summary, body, 8, title, subtitle, clock,
                toolbar, list, footer, action, all, open, done, footerVisible, rh, rh+6,
                compact ? 2 : 3, compact ? 64 : 84, 8, compact ? 30 : 36, 20, 6);
    }

    BoardRect tab(int index) {
        int w = Math.min(94, (rail.w()-12)/3);
        return new BoardRect(rail.x()+index*(w+6), rail.y(), w, 24);
    }

    boolean tabFits() {
        return tab(TABS-1).right() <= rail.right();
    }

    BoardRect filter(int index) {
        return switch (index) {
            case 0 -> filterAll;
            case 1 -> filterOpen;
            default -> filterDone;
        };
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
