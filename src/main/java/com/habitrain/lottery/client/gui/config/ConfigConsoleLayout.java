package com.habitrain.lottery.client.gui.config;

/** Pure responsive geometry for the Mod Menu operations console. */
public record ConfigConsoleLayout(
        Mode mode,
        Rect header,
        Rect navigation,
        Rect content,
        Rect footer,
        Rect playerList,
        Rect playerDetail
) {
    public enum Mode {
        WIDE,
        COMPACT,
        NARROW
    }

    public record Rect(int x, int y, int width, int height) {
        public Rect {
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        public boolean intersects(Rect other) {
            return other != null && width > 0 && height > 0 && other.width > 0 && other.height > 0
                    && x < other.right() && right() > other.x
                    && y < other.bottom() && bottom() > other.y;
        }
    }

    public static ConfigConsoleLayout calculate(int rawWidth, int rawHeight) {
        int width = Math.max(0, rawWidth);
        int height = Math.max(0, rawHeight);
        Mode mode = width >= 720 ? Mode.WIDE : width >= 480 ? Mode.COMPACT : Mode.NARROW;
        int margin = width < 420 ? 8 : 12;
        int gap = mode == Mode.NARROW ? 8 : 10;
        int headerHeight = Math.min(38, height);
        int footerHeight = Math.min(34, Math.max(0, height - headerHeight));
        Rect header = new Rect(0, 0, width, headerHeight);
        Rect footer = new Rect(0, Math.max(headerHeight, height - footerHeight), width, footerHeight);

        Rect navigation;
        Rect content;
        if (mode == Mode.NARROW) {
            int navY = Math.min(footer.y(), header.bottom() + 4);
            int navHeight = Math.min(24, Math.max(0, footer.y() - navY));
            navigation = new Rect(margin, navY, Math.max(0, width - margin * 2), navHeight);
            int contentY = Math.min(footer.y(), navigation.bottom() + gap);
            content = new Rect(
                    margin,
                    contentY,
                    Math.max(0, width - margin * 2),
                    Math.max(0, footer.y() - gap - contentY));
        } else {
            int bodyY = Math.min(footer.y(), header.bottom() + 6);
            int bodyHeight = Math.max(0, footer.y() - gap - bodyY);
            int navWidth = mode == Mode.WIDE ? 148 : 112;
            navWidth = Math.min(navWidth, Math.max(0, width - margin * 2));
            navigation = new Rect(margin, bodyY, navWidth, bodyHeight);
            int contentX = Math.min(width - margin, navigation.right() + gap);
            content = new Rect(
                    contentX,
                    bodyY,
                    Math.max(0, width - margin - contentX),
                    bodyHeight);
        }

        Rect playerList;
        Rect playerDetail;
        if (mode == Mode.NARROW) {
            playerList = content;
            playerDetail = content;
        } else {
            int columnGap = 10;
            int desiredList = Math.max(150, content.width() * 38 / 100);
            int listWidth = Math.min(240, Math.min(desiredList, Math.max(0, content.width() - columnGap)));
            playerList = new Rect(content.x(), content.y(), listWidth, content.height());
            int detailX = playerList.right() + columnGap;
            playerDetail = new Rect(
                    detailX,
                    content.y(),
                    Math.max(0, content.right() - detailX),
                    content.height());
        }
        return new ConfigConsoleLayout(mode, header, navigation, content, footer, playerList, playerDetail);
    }
}
