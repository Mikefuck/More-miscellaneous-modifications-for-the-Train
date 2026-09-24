package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Daily dispatch board: navigation, explicit status filters and fixed action columns. */
public final class DailyTaskScreen extends Screen {

    // =====================================================================
    // 常量
    // =====================================================================

    private static final String KEY = "screen.habitrain_lottery.daily.";

    private static final int TAB_TASKS = 0;
    private static final int TAB_CARDS = 1;
    private static final int TAB_SOURCES = 2;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_PROGRESS = 1;
    private static final int FILTER_CLAIMABLE = 2;
    private static final int FILTER_DONE = 3;

    /** 每五秒主动向服务端要一次快照，保证余额与进度不会长时间陈旧。 */
    private static final long REFRESH_MILLIS = 5_000L;
    /** 点击领取后，在收到服务端回执前最多锁这么久，避免网络异常把按钮永久锁死。 */
    private static final long CLAIM_TIMEOUT_MILLIS = 6_000L;
    private static final long ENTER_MILLIS = 220L;


    /** 六张角色卡：与 {@code HabiCardApi.all} 的键一一对应。 */
    private static final CardInfo[] CARDS = {
            new CardInfo("civilian", WarehouseTheme.ACCENT_CIVILIAN, "\u25C6", KEY + "card.civilian"),
            new CardInfo("neutral", WarehouseTheme.ACCENT_NEUTRAL, "\u25C6", KEY + "card.neutral"),
            new CardInfo("neutral_for_killer", WarehouseTheme.ACCENT_NEUTRAL_FOR_KILLER, "\u2605",
                    KEY + "card.neutral_for_killer"),
            new CardInfo("killer", WarehouseTheme.ACCENT_KILLER, "\u2716", KEY + "card.killer"),
            new CardInfo("self_select", WarehouseTheme.ACCENT_SELF_SELECT, "\u2726",
                    KEY + "card.self_select"),
            new CardInfo("limit_break", WarehouseTheme.ACCENT_LIMIT_BREAK, "\u25B2",
                    KEY + "card.limit_break"),
    };

    /** 发放来源的三个分类；键与 {@code DailyTaskBoardService.sources()} 的 category 一致。 */
    private static final SourceCategory[] CATEGORIES = {
            new SourceCategory("draws", KEY + "source.cat.draws", DailyBoardTheme.BLUE,
                    DailyBoardTheme.BLUE_SOFT),
            new SourceCategory("coins", KEY + "source.cat.coins", DailyBoardTheme.GOLD,
                    DailyBoardTheme.AMBER_SOFT),
            new SourceCategory("cards", KEY + "source.cat.cards", DailyBoardTheme.VIOLET,
                    DailyBoardTheme.VIOLET_SOFT),
    };

    private static final String[] FILTER_KEYS = {
            KEY + "filter.all", KEY + "filter.progress", KEY + "filter.claimable", KEY + "filter.done",
    };

    // =====================================================================
    // 状态
    // =====================================================================

    private final Screen parent;
    private DailyTaskSnapshot snapshot;
    private DailyBoardLayout layout;

    private int tab = TAB_TASKS;
    private int filter = FILTER_ALL;
    private int sourceCat;
    private int hoverFilter = -1;
    private double scroll;
    private double pageScroll;

    private final List<TaskRow> rows = new ArrayList<>();
    private final List<NavTab> navTabs = new ArrayList<>();
    private final List<StatusFilter> statusFilters = new ArrayList<>();
    private MiniButton closeButton;
    private MiniButton footerAction;

    /** 已发出领取请求、尚未收到服务端回执的任务 ID。 */
    private final Set<String> pendingClaims = new HashSet<>();

    private long openedAt;
    private long nowMillis;
    private long lastFrameMillis;
    private long lastRequestMillis;
    private long claimLockMillis;
    private float frameDelta = 16.0F;
    private float partialTick;
    private int mouseX;
    private int mouseY;
    private boolean suppressBackground;

    public DailyTaskScreen(Screen parent, DailyTaskSnapshot snapshot) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        this.snapshot = snapshot;
    }

    /** 服务端推送新快照时调用（刷新、领取回执、被外部打开都会走这里）。 */
    public void applySnapshot(DailyTaskSnapshot value) {
        if (value == null) {
            return;
        }
        this.snapshot = value;
        // 快照到达即视为权威状态，清掉本地等待标记
        pendingClaims.clear();

        String focusedId = getFocused() instanceof TaskRow row ? row.task.id() : null;
        boolean hadFocus = getFocused() != null;

        computeLayout();
        placeWidgets();
        rebuildRows();

        if (focusedId != null) {
            for (TaskRow row : rows) {
                if (row.task.id().equals(focusedId)) {
                    setFocused(row);
                    break;
                }
            }
        } else if (hadFocus && rows.isEmpty() && !navTabs.isEmpty()) {
            setFocused(navTabs.get(tab));
        }
        clampScroll();
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void init() {
        super.init();
        long now = System.currentTimeMillis();
        openedAt = now;
        nowMillis = now;
        lastFrameMillis = now;
        lastRequestMillis = now;
        computeLayout();

        footerAction = null;
        navTabs.clear();
        for (int i = 0; i < DailyBoardLayout.TABS; i++) {
            NavTab widget = new NavTab(i, Component.translatable(tabKey(i)));
            navTabs.add(widget);
            addRenderableWidget(widget);
        }

        statusFilters.clear();
        for (int i=0; i<DailyBoardLayout.FILTERS; i++) {
            StatusFilter widget = new StatusFilter(i);
            statusFilters.add(widget);
            addWidget(widget);
        }

        closeButton = new MiniButton(true, Component.translatable(KEY + "close"), layout.close());
        addRenderableWidget(closeButton);
        if (layout.showFooter()) {
            footerAction = new MiniButton(false, Component.translatable(KEY + "footer.action"),
                    layout.footerAction());
            addRenderableWidget(footerAction);
        }

        placeWidgets();
        rebuildRows();
        setFocused(rows.isEmpty() ? navTabs.get(0) : rows.get(0));
        if (snapshot == null) {
            requestSnapshot();
        }
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        // 领取等待态最长保留 CLAIM_TIMEOUT_MILLIS：服务端无响应时按钮自行复活，
        // 而不是把玩家永久锁在一个点不动的按钮上。
        if (!pendingClaims.isEmpty() && claimLockMillis > 0
                && now - claimLockMillis > CLAIM_TIMEOUT_MILLIS) {
            pendingClaims.clear();
        }
        if (now - lastRequestMillis < REFRESH_MILLIS) {
            return;
        }
        lastRequestMillis = now;
        requestSnapshot();
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 背景由 {@link #render} 显式画一次；这里挡住框架的重复调用（1.21 的 Screen#render 也会调一次）。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (suppressBackground) {
            return;
        }
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    // =====================================================================
    // 布局与控件摆放
    // =====================================================================

    private void computeLayout() {
        layout = DailyBoardLayout.of(width, height, taskCount());
    }

    private void placeWidgets() {
        for (StatusFilter widget : statusFilters) {
            BoardRect r = layout.filter(widget.index);
            widget.setX(r.x()); widget.setY(r.y());
            widget.setWidth(r.w()); widget.setHeight(r.h());
            widget.visible = tab == TAB_TASKS;
        }
        for (NavTab widget : navTabs) {
            BoardRect r = layout.tab(widget.index);
            widget.setX(r.x());
            widget.setY(r.y());
            widget.setWidth(r.w());
            widget.setHeight(r.h());
        }
        if (closeButton != null) {
            closeButton.setX(layout.close().x());
            closeButton.setY(layout.close().y());
            closeButton.setWidth(layout.close().w());
            closeButton.setHeight(layout.close().h());
        }
        if (footerAction != null) {
            footerAction.setX(layout.footerAction().x());
            footerAction.setY(layout.footerAction().y());
            footerAction.setWidth(layout.footerAction().w());
            footerAction.setHeight(layout.footerAction().h());
        }
    }

    private void rebuildRows() {
        for (TaskRow row : rows) {
            removeWidget(row);
        }
        rows.clear();
        List<DailyTaskSnapshot.TaskRow> visible = visibleTasks();
        for (int i = 0; i < visible.size(); i++) {
            TaskRow row = new TaskRow(visible.get(i), i);
            row.visible = tab == TAB_TASKS;
            rows.add(row);
            // 只登记输入，不登记渲染：绘制由 drawTaskList 在列表裁剪区内完成
            addWidget(row);
        }
        clampScroll();
    }

    private void selectTab(int next) {
        int target = Mth.clamp(next, 0, DailyBoardLayout.TABS - 1);
        if (target == tab) {
            return;
        }
        tab = target;
        for (StatusFilter widget : statusFilters) widget.visible = tab == TAB_TASKS;
        pageScroll = 0;
        for (TaskRow row : rows) {
            row.visible = tab == TAB_TASKS;
        }
        List<AbstractWidget> focusable = new ArrayList<>();
        if (tab == TAB_TASKS && !rows.isEmpty()) {
            focusable.addAll(rows);
        }
        focusable.addAll(navTabs);
        setFocused(focusable.get(0));
    }

    private void selectFilter(int next) {
        int target = Mth.clamp(next, 0, DailyBoardLayout.FILTERS - 1);
        if (target == filter) {
            return;
        }
        filter = target;
        scroll = 0;
        rebuildRows();
        if (!rows.isEmpty() && tab == TAB_TASKS) {
            setFocused(rows.get(0));
        }
    }

    private void clampScroll() {
        scroll = Mth.clamp(scroll, 0, layout.maxScroll(visibleTasks().size()));
        pageScroll = Mth.clamp(pageScroll, 0, pageMaxScroll());
    }

    private void requestSnapshot() {
        LotteryClientNetwork.clientRequestDailyTasks();
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.partialTick = partialTick;

        nowMillis = System.currentTimeMillis();
        frameDelta = Mth.clamp(nowMillis - lastFrameMillis, 0.0F, 120.0F);
        lastFrameMillis = nowMillis;

        hoverFilter = tab == TAB_TASKS ? filterAt(mouseX, mouseY) : -1;

        renderBackground(g, mouseX, mouseY, partialTick);

        float enter = GuiFx.easeOutCubic(GuiFx.progress(nowMillis, openedAt, ENTER_MILLIS));
        DailyBoardTheme.window(g, layout);
        if (layout.sidebar()) {
            DailyBoardTheme.text(g, font, Component.translatable(KEY + "dispatch").getString(),
                    layout.rail().x()+14, layout.rail().y()+20, DailyBoardTheme.NAV_TEXT);
            DailyBoardTheme.text(g, font, "HABITRAIN", layout.rail().x()+14,
                    layout.rail().y()+36, 0xFF91A7BB);
            DailyBoardTheme.text(g, font, "Q / T / C", layout.rail().x()+14,
                    layout.rail().bottom()-22, 0xFF91A7BB);
        }

        // Fade content without moving hit targets.
        boolean fade = enter < 0.999F;
        if (fade) {
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, enter);
        }
        try {
            if (tab == TAB_TASKS) {
                drawOverview(g);
                drawTasksPage(g);
            } else if (tab == TAB_CARDS) {
                drawCardsPage(g);
            } else {
                drawSourcesPage(g);
            }
        } finally {
            if (fade) {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }

        suppressBackground = true;
        try {
            // 脚注按钮只在「委托」页且版面真的留了脚注位置时存在
            if (footerAction != null) {
                footerAction.visible = tab == TAB_TASKS && layout.showFooter();
            }
            super.render(g, mouseX, mouseY, partialTick);
        } finally {
            suppressBackground = false;
        }

    }

    // ---------------------------------------------------------------------
    // 今日概览与刷新时间
    // ---------------------------------------------------------------------

    private void drawOverview(GuiGraphics g) {
        BoardRect title = layout.title();
        DailyBoardTheme.textScaled(g, font, Component.translatable(KEY + "title").getString(),
                title.x(), title.y(), layout.compact() ? 1.15F : 1.5F, DailyBoardTheme.INK);
        BoardRect clock = layout.clock();
        String reset = Component.translatable(KEY + "reset.hint", countdown()).getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, reset, clock.w()),
                clock.x(), clock.y(), DailyBoardTheme.INK_SOFT);
        String summary = snapshot == null ? leftSubtitle()
                : Component.translatable(KEY + "overview", claimedCount(), taskCount(), claimableCount()).getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, summary, layout.subtitle().w()),
                layout.subtitle().x(), layout.subtitle().y(), DailyBoardTheme.INK_SOFT);
        if (!layout.compact() && snapshot != null) {
            String balances = Component.translatable(KEY + "balances",
                    snapshot.draws(), snapshot.coins(), snapshot.cardTotal()).getString();
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, balances, layout.subtitle().w()),
                    title.x(), layout.subtitle().y()+16, DailyBoardTheme.MUTED);
        }
        DailyBoardTheme.hairline(g, layout.summary().x(), layout.summary().right(),
                layout.summary().bottom()-1, DailyBoardTheme.PAPER_BORDER);
    }

    private void drawTasksPage(GuiGraphics g) {

        drawToolbar(g);
        drawTaskList(g);
        drawFooterBar(g);
    }

    private void drawToolbar(GuiGraphics g) {
        for (int i = 0; i < DailyBoardLayout.FILTERS; i++) {
            BoardRect r = layout.filter(i);
            boolean selected = filter == i;
            int count = switch (i) {
                case FILTER_PROGRESS -> taskCount()-claimedCount()-claimableCount();
                case FILTER_CLAIMABLE -> claimableCount();
                case FILTER_DONE -> claimedCount();
                default -> taskCount();
            };
            String label = Component.translatable(FILTER_KEYS[i]).getString();
            if (!layout.compact()) label += "  " + count;
            DailyBoardTheme.box(g, r, selected ? DailyBoardTheme.NAVY
                    : hoverFilter == i ? DailyBoardTheme.BLUE_SOFT : DailyBoardTheme.CARD_TOP,
                    getFocused() == statusFilters.get(i) ? DailyBoardTheme.AMBER_DEEP
                            : selected ? DailyBoardTheme.NAVY : DailyBoardTheme.CARD_LINE);
            DailyBoardTheme.textCentered(g, font, Component.literal(DailyBoardTheme.fit(font,label,r.w()-6)),
                    r.cx(), r.y()+8, selected ? 0xFFFFFFFF : DailyBoardTheme.INK_SOFT);
            if (selected) g.fill(r.x(),r.bottom()-2,r.right(),r.bottom(),DailyBoardTheme.AMBER_DEEP);
        }
    }

    private void drawTaskList(GuiGraphics g) {
        if (snapshot == null) {
            drawPlaceholder(g, KEY + "empty.syncing", KEY + "empty.syncing.hint", true);
            return;
        }
        if (taskCount() == 0) {
            drawPlaceholder(g, KEY + "empty.none", KEY + "empty.none.hint", false);
            return;
        }
        if (rows.isEmpty()) {
            drawPlaceholder(g, KEY + "empty.filtered", KEY + "empty.filtered.hint", false);
            return;
        }

        BoardRect list = layout.list();
        GuiFx.beginClip(g, list.x() - 2, list.y(), list.right() + 2, list.bottom() + 1);
        for (TaskRow row : rows) {
            int y = layout.rowY(row.order, scroll);
            // 只处理可见行：滚出可视区的行必须关掉输入，否则点到看不见的「领取」
            boolean inView = y + layout.rowH() >= list.y() - 1 && y <= list.bottom() + 1;
            row.visible = inView;
            if (!inView) {
                continue;
            }
            row.setX(list.x());
            row.setY(y);
            row.setWidth(list.w());
            row.setHeight(layout.rowH());
            row.render(g, mouseX, mouseY, partialTick);
        }
        GuiFx.endClip(g);
        DailyBoardTheme.scrollbar(g, list.right() + 3, list, scroll,
                layout.maxScroll(rows.size()));
    }

    private void drawPlaceholder(GuiGraphics g, String titleKey, String hintKey, boolean spinner) {
        BoardRect list = layout.list();
        int cx = list.cx();
        int cy = list.cy();
        for (int i=0; i<4; i++) {
            int active = (int)((nowMillis / 220) % 4);
            g.fill(cx-19+i*10,cy-24,cx-12+i*10,cy-20,
                    spinner && i == active ? DailyBoardTheme.AMBER_DEEP : DailyBoardTheme.TRACK);
        }
        DailyBoardTheme.textCentered(g, font, Component.translatable(titleKey), cx, cy - 6,
                DailyBoardTheme.INK_SOFT);
        DailyBoardTheme.textCentered(g, font, Component.translatable(hintKey), cx, cy + 6,
                DailyBoardTheme.MUTED);
    }

    private void drawFooterBar(GuiGraphics g) {
        if (!layout.showFooter()) {
            return;
        }
        BoardRect f = layout.footer();
        DailyBoardTheme.softCard(g, f, 6, DailyBoardTheme.PAPER_TOP, DailyBoardTheme.PAPER_TOP, DailyBoardTheme.CARD_LINE);
        String title = Component.translatable(KEY + "footer.title").getString();
        DailyBoardTheme.text(g, font, title, f.x() + 8, f.y() + (f.h() - 8) / 2, DailyBoardTheme.INK);
        int hintX = f.x() + 8 + font.width(title) + 8;
        int hintW = layout.footerAction().x() - 6 - hintX;
        DailyBoardTheme.text(g, font,
                DailyBoardTheme.fit(font, Component.translatable(KEY + "footer.hint").getString(), hintW),
                hintX, f.y() + (f.h() - 8) / 2, DailyBoardTheme.MUTED);
    }

    // ---------------------------------------------------------------------
    // 资产页
    // ---------------------------------------------------------------------

    private void drawCardsPage(GuiGraphics g) {
        DailyBoardTheme.rightPage(g, layout.content(), 8);
        BoardRect area = new BoardRect(layout.content().x() + layout.pagePad(), layout.pageTop(),
                layout.content().w() - layout.pagePad() * 2, layout.pageHeight());
        String hint = Component.translatable(KEY + "cards.subtitle",
                snapshot == null ? "--" : snapshot.cardTotal()).getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, hint, area.w()), area.x(), area.y(), DailyBoardTheme.MUTED);

        int top = area.y() + 13;
        int cols = layout.cardCols();
        int gap = layout.cardGap();
        int cardW = (area.w() - (cols - 1) * gap) / cols;
        int cardH = layout.cardH();
        int rowCount = (CARDS.length + cols - 1) / cols;
        int totalH = rowCount * cardH + (rowCount - 1) * gap;
        int avail = area.bottom() - top;
        pageScroll = Mth.clamp(pageScroll, 0, Math.max(0, totalH - avail));

        Map<String, Integer> cards = snapshot == null || snapshot.cards() == null ? Map.of()
                : snapshot.cards();

        GuiFx.beginClip(g, area.x() - 2, top, area.right() + 2, area.bottom());
        for (int i = 0; i < CARDS.length; i++) {
            int x = area.x() + (i % cols) * (cardW + gap);
            int y = top + (i / cols) * (cardH + gap) - (int) pageScroll;
            if (y + cardH < top || y > area.bottom()) {
                continue;
            }
            drawCardTile(g, new BoardRect(x, y, cardW, cardH), CARDS[i],
                    cards.getOrDefault(CARDS[i].id, 0));
        }
        GuiFx.endClip(g);
        DailyBoardTheme.scrollbar(g, area.right() + 3, new BoardRect(area.x(), top, area.w(), avail),
                pageScroll, Math.max(0, totalH - avail));
    }

    private void drawCardTile(GuiGraphics g, BoardRect r, CardInfo info, int count) {
        boolean owned = count > 0;
        DailyBoardTheme.card(g, r, 7);
        GuiFx.roundedBar(g, r.x() + 1, r.y() + 6, r.x() + 4, r.bottom() - 6, 0,
                GuiFx.fade(info.accent, owned ? 0.95F : 0.35F));
        BoardRect mark = new BoardRect(r.x() + 10, r.y() + 9, 18, 18);
        DailyBoardTheme.softCard(g, mark, 5, GuiFx.mix(DailyBoardTheme.CARD_TOP, info.accent, 0.16F),
                GuiFx.mix(DailyBoardTheme.CARD_BOTTOM, info.accent, 0.10F),
                GuiFx.mix(DailyBoardTheme.CARD_LINE, info.accent, 0.35F));
        DailyBoardTheme.textCentered(g, font, Component.literal(info.glyph), mark.cx(), mark.cy() - 4,
                info.accent);

        String name = Component.translatable(info.nameKey).getString();
        String value = String.valueOf(count);
        int valueW = (int) (font.width(value) * 1.25F);
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, name, r.w() - 40 - valueW),
                r.x() + 34, r.y() + 12, owned ? DailyBoardTheme.INK : DailyBoardTheme.MUTED);
        DailyBoardTheme.textScaled(g, font, value, r.right() - 10 - valueW, r.y() + 11, 1.25F,
                owned ? info.accent : DailyBoardTheme.FAINT);

        String desc = Component.translatable(info.nameKey + ".desc").getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, desc, r.w() - 20), r.x() + 10,
                r.bottom() - 15, DailyBoardTheme.MUTED);
    }

    // ---------------------------------------------------------------------
    // 发放来源页
    // ---------------------------------------------------------------------

    private void drawSourcesPage(GuiGraphics g) {
        DailyBoardTheme.rightPage(g, layout.content(), 8);
        BoardRect area = new BoardRect(layout.content().x() + layout.pagePad(), layout.pageTop(),
                layout.content().w() - layout.pagePad() * 2, layout.pageHeight());
        String hint = Component.translatable(KEY + "sources.hint").getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, hint, area.w()), area.x(), area.y(), DailyBoardTheme.MUTED);

        int chipTop = area.y() + 13;
        int chipH = layout.chipH();
        int chipGap = layout.chipGap();
        int chipW = Math.max(46, (area.w() - chipGap * (CATEGORIES.length - 1)) / CATEGORIES.length);
        for (int i = 0; i < CATEGORIES.length; i++) {
            BoardRect r = new BoardRect(area.x() + i * (chipW + chipGap), chipTop, chipW, chipH);
            boolean active = sourceCat == i;
            boolean hover = r.contains(mouseX, mouseY);
            int accent = CATEGORIES[i].accent;
            DailyBoardTheme.chip(g, font, r,
                    Component.translatable(CATEGORIES[i].labelKey).getString(),
                    active ? GuiFx.mix(DailyBoardTheme.CARD_TOP, CATEGORIES[i].soft, 0.75F)
                            : (hover ? DailyBoardTheme.TEAL_SOFT : DailyBoardTheme.PAPER_TOP),
                    active ? GuiFx.mix(DailyBoardTheme.CARD_LINE, accent, 0.55F)
                            : DailyBoardTheme.CARD_LINE,
                    active ? accent : (hover ? DailyBoardTheme.INK_SOFT : DailyBoardTheme.MUTED));
        }

        int top = chipTop + chipH + layout.gap();
        int rowH = layout.sourceRowH();
        List<DailyTaskSnapshot.SourceRow> sources = filteredSources();
        int totalH = sources.isEmpty() ? 0 : sources.size() * sourceStride() - 4;
        int avail = area.bottom() - top;
        pageScroll = Mth.clamp(pageScroll, 0, Math.max(0, totalH - avail));

        DailyTaskSnapshot.SourceRow hovered = null;
        GuiFx.beginClip(g, area.x() - 2, top, area.right() + 2, area.bottom());
        if (sources.isEmpty()) {
            DailyBoardTheme.textCentered(g, font, Component.translatable(KEY + "sources.empty"),
                    area.cx(), top + 20, DailyBoardTheme.MUTED);
        }
        for (int i = 0; i < sources.size(); i++) {
            DailyTaskSnapshot.SourceRow src = sources.get(i);
            int y = top + i * sourceStride() - (int) pageScroll;
            if (y + rowH < top || y > area.bottom()) {
                continue;
            }
            BoardRect r = new BoardRect(area.x(), y, area.w(), rowH);
            boolean hover = r.contains(mouseX, mouseY);
            int accent = CATEGORIES[sourceCat].accent;
            DailyBoardTheme.softCard(g, r, 6, DailyBoardTheme.CARD_TOP,
                    hover ? DailyBoardTheme.CARD_BOTTOM : DailyBoardTheme.CARD_TOP,
                    hover ? GuiFx.mix(DailyBoardTheme.CARD_LINE, accent, 0.4F)
                            : DailyBoardTheme.CARD_LINE);
            GuiFx.roundedBar(g, r.x() + 1, r.y() + 5, r.x() + 4, r.bottom() - 5, 0,
                    GuiFx.fade(accent, 0.9F));
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, src.title(), r.w() - 24),
                    r.x() + 12, r.y() + 4, DailyBoardTheme.INK);
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, src.detail(), r.w() - 24),
                    r.x() + 12, r.y() + 15, DailyBoardTheme.MUTED);
            if (hover) {
                hovered = src;
            }
        }
        GuiFx.endClip(g);
        DailyBoardTheme.scrollbar(g, area.right() + 3, new BoardRect(area.x(), top, area.w(), avail),
                pageScroll, Math.max(0, totalH - avail));
        // 气泡必须等裁剪结束再画，否则会被列表的 scissor 切掉
        if (hovered != null) {
            List<FormattedCharSequence> lines = font.split(
                    Component.literal(hovered.title() + "\n" + hovered.detail()), 190);
            DailyBoardTheme.tooltip(g, font, lines, mouseX, mouseY, area);
        }
    }

    /** 发放来源每行的步进（行高 + 行距）。 */
    private int sourceStride() {
        return layout.sourceRowH() + 4;
    }

    // ---------------------------------------------------------------------
    // 输入
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (tab == TAB_TASKS && filterAt(mx, my) >= 0) {
                playClick();
                selectFilter(filterAt(mx, my));
                return true;
            }
            if (tab == TAB_SOURCES) {
                int cat = sourceChipAt(mx, my);
                if (cat >= 0) {
                    if (cat != sourceCat) {
                        playClick();
                        sourceCat = cat;
                        pageScroll = 0;
                    }
                    return true;
                }
            }
            // 脚注整行都是「查看发放来源」的入口，不必瞄准右端的小按钮
            if (tab == TAB_TASKS && layout.showFooter() && layout.footer().contains(mx, my)) {
                playClick();
                selectTab(TAB_SOURCES);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (tab == TAB_TASKS) {
            int max = layout.maxScroll(rows.size());
            if (max > 0) {
                scroll = Mth.clamp(scroll - scrollY * (layout.rowStride() * 0.6D), 0, max);
                return true;
            }
        } else {
            double max = pageMaxScroll();
            if (max > 0) {
                pageScroll = Mth.clamp(pageScroll - scrollY * 22.0D, 0, max);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter / 空格 / 小键盘回车：激活当前焦点控件。
        // 这里显式处理而不是依赖 AbstractWidget 的键盘路径，是为了让「焦点在任务行上按回车」
        // 也走同一条领取逻辑（鼠标只认按钮矩形，避免误领）。
        if (keyCode == 257 || keyCode == 32 || keyCode == 335) {
            GuiEventListener focused = getFocused();
            if (focused instanceof StatusFilter widget && tab == TAB_TASKS) {
                playClick(); selectFilter(widget.index); return true;
            }
            if (focused instanceof TaskRow row) {
                row.activateByKeyboard();
                return true;
            }
            if (focused instanceof NavTab navTab) {
                playClick();
                selectTab(navTab.index);
                return true;
            }
            if (focused != null && focused == closeButton) {
                playClick();
                onClose();
                return true;
            }
            if (focused != null && focused == footerAction) {
                playClick();
                selectTab(TAB_SOURCES);
                return true;
            }
        }
        switch (keyCode) {
            case 256 -> { // Esc
                onClose();
                return true;
            }
            case 82 -> { // R 手动刷新
                lastRequestMillis = System.currentTimeMillis();
                requestSnapshot();
                return true;
            }
            case 84 -> { // T 资产
                selectTab(TAB_CARDS);
                return true;
            }
            case 67 -> { // C 发放来源
                selectTab(TAB_SOURCES);
                return true;
            }
            case 81 -> { // Q 今日任务
                selectTab(TAB_TASKS);
                return true;
            }
            case 70 -> { // F 依次切换筛选
                selectFilter((filter + 1) % DailyBoardLayout.FILTERS);
                return true;
            }
            case 266, 267 -> { // PageUp / PageDown
                scrollPage(keyCode == 266 ? -1 : 1);
                return true;
            }
            default -> {
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void scrollPage(int direction) {
        if (tab == TAB_TASKS) {
            scroll = Mth.clamp(scroll + direction * layout.list().h(), 0,
                    layout.maxScroll(rows.size()));
        } else {
            pageScroll = Mth.clamp(pageScroll + direction * layout.pageHeight(), 0, pageMaxScroll());
        }
    }

    private double pageMaxScroll() {
        if (tab == TAB_CARDS) {
            int cols = layout.cardCols();
            int rowsN = (CARDS.length + cols - 1) / cols;
            int total = rowsN * layout.cardH() + (rowsN - 1) * layout.cardGap();
            return Math.max(0, total - (layout.pageHeight() - 13));
        }
        if (tab == TAB_SOURCES) {
            int total = filteredSources().size() * sourceStride() - 4;
            return Math.max(0, total - (layout.pageHeight() - 13 - layout.chipH() - layout.gap()));
        }
        return 0;
    }

    /** 发放来源页的分类胶囊命中测试；几何与 {@link #drawSourcesPage} 完全一致。 */
    private int sourceChipAt(double mx, double my) {
        BoardRect area = new BoardRect(layout.content().x() + layout.pagePad(), layout.pageTop(),
                layout.content().w() - layout.pagePad() * 2, layout.pageHeight());
        int chipTop = area.y() + 13;
        int chipGap = layout.chipGap();
        int chipW = Math.max(46, (area.w() - chipGap * (CATEGORIES.length - 1)) / CATEGORIES.length);
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (new BoardRect(area.x() + i * (chipW + chipGap), chipTop, chipW, layout.chipH())
                    .contains(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    private int filterAt(double mx, double my) {
        for (int i = 0; i < DailyBoardLayout.FILTERS; i++) {
            if (layout.filter(i).contains(mx, my)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void updateNarratedWidget(NarrationElementOutput output) {
        super.updateNarratedWidget(output);
        output.add(NarratedElementType.USAGE, Component.translatable(KEY + "usage"));
    }

    // =====================================================================
    // 领取
    // =====================================================================

    private void claim(DailyTaskSnapshot.TaskRow task) {
        if (task == null || !canClaim(task)) {
            return;
        }
        if (LotteryClientNetwork.clientClaimDailyTask(task.id())) {
            pendingClaims.add(task.id());
            claimLockMillis = System.currentTimeMillis();
        }
    }

    private boolean canClaim(DailyTaskSnapshot.TaskRow task) {
        return task != null && task.progress() >= task.target() && !task.claimed()
                && !pendingClaims.contains(task.id());
    }

    private void playClick() {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    // =====================================================================
    // 数据读取
    // =====================================================================

    private List<DailyTaskSnapshot.TaskRow> tasks() {
        if (snapshot == null || snapshot.tasks() == null) {
            return List.of();
        }
        return snapshot.tasks();
    }

    /** 可领取优先，进行中其次，已领取最后；筛选不改变组内顺序。 */
    private List<DailyTaskSnapshot.TaskRow> visibleTasks() {
        List<DailyTaskSnapshot.TaskRow> all = tasks().stream().sorted(java.util.Comparator.comparingInt(
                t -> t.claimed() ? 2 : (t.progress() >= t.target() ? 0 : 1))).toList();
        if (filter == FILTER_ALL) {
            return all;
        }
        List<DailyTaskSnapshot.TaskRow> out = new ArrayList<>();
        for (DailyTaskSnapshot.TaskRow task : all) {
            boolean claimable = task.progress() >= task.target() && !task.claimed();
            if (filter == FILTER_CLAIMABLE ? claimable : filter == FILTER_PROGRESS
                    ? !task.claimed() && !claimable : task.claimed()) {
                out.add(task);
            }
        }
        return out;
    }

    private int taskCount() {
        return snapshot == null || snapshot.tasks() == null ? 0 : snapshot.tasks().size();
    }

    private List<DailyTaskSnapshot.SourceRow> filteredSources() {
        if (snapshot == null || snapshot.sources() == null) {
            return List.of();
        }
        String cat = CATEGORIES[sourceCat].id;
        return snapshot.sources().stream().filter(s -> cat.equals(s.category())).toList();
    }

    private int claimedCount() {
        return (int) tasks().stream().filter(DailyTaskSnapshot.TaskRow::claimed).count();
    }

    private int claimableCount() {
        return (int) tasks().stream()
                .filter(t -> t.progress() >= t.target() && !t.claimed()).count();
    }

    /** 左页说明：同步中 / 没有任务 / 正常时的规则说明。 */
    private String leftSubtitle() {
        if (snapshot == null) {
            return Component.translatable(KEY + "subtitle.syncing").getString();
        }
        if (taskCount() == 0) {
            return Component.translatable(KEY + "subtitle.empty", utcDate()).getString();
        }
        return Component.translatable(KEY + "subtitle.hint").getString();
    }

    private String utcDate() {
        long day = snapshot == null ? Instant.now().getEpochSecond() / 86_400L : snapshot.epochDayUtc();
        return LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " UTC";
    }

    private String countdown() {
        if (snapshot == null) {
            return "--:--:--";
        }
        long next = (snapshot.epochDayUtc() + 1) * 86_400L;
        long secs = Math.max(0, next - Instant.now().getEpochSecond());
        return String.format(Locale.ROOT, "%02d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60);
    }

    private static String tabKey(int index) {
        return switch (index) {
            case TAB_CARDS -> KEY + "tab.cards";
            case TAB_SOURCES -> KEY + "tab.sources";
            default -> KEY + "tab.tasks";
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // =====================================================================
    // 内部记录
    // =====================================================================

    private record CardInfo(String id, int accent, String glyph, String nameKey) {
    }

    private record SourceCategory(String id, String labelKey, int accent, int soft) {
    }

    /** 领取按钮的视觉与文案状态。 */
    private enum ClaimState {
        READY(DailyBoardTheme.TEAL, DailyBoardTheme.TEAL_SOFT, DailyBoardTheme.TEAL, 0xFFFFFFFF, "\u2726",
                KEY + "row.claim"),
        WAIT(DailyBoardTheme.BLUE, DailyBoardTheme.BLUE_SOFT, 0xFFDCE7F6, DailyBoardTheme.BLUE,
                "\u25C6", KEY + "row.claiming"),
        DONE(DailyBoardTheme.GREEN, DailyBoardTheme.GREEN_SOFT, 0xFFE4F0E7, DailyBoardTheme.GREEN,
                "\u2714", KEY + "row.claimed"),
        BUSY(0xFF778B89, 0xFFF0F5F4, 0xFFF0F5F4, DailyBoardTheme.MUTED, "\u25C7",
                KEY + "row.in_progress");

        private final int accent;
        private final int soft;
        private final int fill;
        private final int text;
        private final String glyph;
        private final String labelKey;

        ClaimState(int accent, int soft, int fill, int text, String glyph, String labelKey) {
            this.accent = accent;
            this.soft = soft;
            this.fill = fill;
            this.text = text;
            this.glyph = glyph;
            this.labelKey = labelKey;
        }

        private Component label() {
            return Component.translatable(labelKey);
        }
    }

    // =====================================================================
    // 控件：左侧导航
    // =====================================================================

    private final class StatusFilter extends AbstractWidget {
        private final int index;
        private StatusFilter(int index) {
            super(0,0,1,1,Component.translatable(FILTER_KEYS[index]));
            this.index = index;
        }
        @Override protected void renderWidget(GuiGraphics g,int x,int y,float delta) { }
        @Override public void onClick(double x,double y) { selectFilter(index); }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE,getMessage());
            out.add(NarratedElementType.USAGE,Component.translatable(KEY + "usage.tab"));
        }
    }

    private final class NavTab extends AbstractWidget {

        private final int index;
        private float hoverAnim;

        private NavTab(int index, Component label) {
            super(0, 0, 1, 1, label);
            this.index = index;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            BoardRect r = new BoardRect(getX(), getY(), getWidth(), getHeight());
            boolean active = tab == index;
            hoverAnim = GuiFx.approach(hoverAnim, (isHovered || isFocused()) && !active ? 1.0F : 0.0F, frameDelta, 80.0F);
            int fill = active ? DailyBoardTheme.CARD_TOP : (isHovered || isFocused()) ? 0xFF344F68 : DailyBoardTheme.NAVY;
            DailyBoardTheme.box(g, r, fill, isFocused() ? DailyBoardTheme.AMBER : fill);
            if (active) g.fill(r.x(),r.y(),r.x()+3,r.bottom(),DailyBoardTheme.AMBER_DEEP);
            DailyBoardTheme.textCentered(g, font, getMessage(), r.cx(), r.y()+(r.h()-8)/2,
                    active ? DailyBoardTheme.INK : DailyBoardTheme.NAV_TEXT);
        }

        @Override
        public void onClick(double mx, double my) {
            playClick();
            selectTab(index);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.USAGE, Component.translatable(KEY + "usage.tab"));
        }
    }





    // =====================================================================
    // 控件：关闭 / 脚注按钮
    // =====================================================================

    private final class MiniButton extends AbstractWidget {

        private final boolean close;
        private float hoverAnim;

        private MiniButton(boolean close, Component label, BoardRect box) {
            super(box.x(), box.y(), box.w(), box.h(), label);
            this.close = close;
            setTooltip(Tooltip.create(label));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            BoardRect r = new BoardRect(getX(), getY(), getWidth(), getHeight());
            hoverAnim = GuiFx.approach(hoverAnim, (isHovered || isFocused()) ? 1.0F : 0.0F, frameDelta, 70.0F);
            if (close) {
                if (hoverAnim > 0.02F) {
                    GuiFx.roundRect(g, r.x(), r.y(), r.right(), r.bottom(), 0,
                            GuiFx.alpha(DailyBoardTheme.WARN, (int) (30 * hoverAnim)));
                }
                DailyBoardTheme.textCentered(g, font, Component.literal("\u2716"), r.cx(), r.cy() - 4,
                        GuiFx.mix(layout.sidebar() ? DailyBoardTheme.MUTED : DailyBoardTheme.NAV_TEXT, DailyBoardTheme.WARN, hoverAnim));
                return;
            }
            DailyBoardTheme.button(g, font, r, getMessage(), 0xFFF0F5F4,
                    GuiFx.mix(DailyBoardTheme.CARD_LINE, DailyBoardTheme.GOLD,
                            0.35F + 0.3F * hoverAnim),
                    GuiFx.mix(DailyBoardTheme.INK_SOFT, DailyBoardTheme.GOLD, hoverAnim), hoverAnim);
        }

        @Override
        public void onClick(double mx, double my) {
            playClick();
            if (close) {
                onClose();
            } else {
                selectTab(TAB_SOURCES);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }

    // =====================================================================
    // 控件：任务行
    // =====================================================================

    /**
     * 任务信息与操作列分开；状态条、奖励文字和进度各自固定位置。
     *
     * <p>整行是控件（Tab 焦点、旁白、Enter 触发都可用），但只有右侧按钮矩形会真正领取；
     * 点行内其它位置不产生任何不可撤销的操作。</p>
     */
    private final class TaskRow extends AbstractWidget {

        private final DailyTaskSnapshot.TaskRow task;
        private final int order;
        private float hoverAnim;
        private float pressAnim;

        private TaskRow(DailyTaskSnapshot.TaskRow task, int order) {
            super(layout.list().x(), layout.list().y(), layout.list().w(), layout.rowH(),
                    Component.literal(nullToEmpty(task.title())));
            this.task = task;
            this.order = order;
            setTooltip(Tooltip.create(Component.empty()
                    .append(Component.literal(nullToEmpty(task.title())))
                    .append("\n")
                    .append(Component.literal(nullToEmpty(task.description())))
                    .append("\n")
                    .append(Component.translatable(KEY + "row.progress", task.progress(), task.target()))
                    .append("\n")
                    .append(Component.literal(nullToEmpty(task.reward())))));
        }

        private ClaimState state() {
            if (task.claimed()) {
                return ClaimState.DONE;
            }
            if (pendingClaims.contains(task.id())) {
                return ClaimState.WAIT;
            }
            return canClaim(task) ? ClaimState.READY : ClaimState.BUSY;
        }

        private BoardRect claimButton() {
            int w = layout.compact() ? 58 : 76;
            int h = 24;
            // 按钮在「去掉底部进度带」的高度里居中
            return new BoardRect(getX() + getWidth() - 8 - w, getY() + (getHeight() - 4 - h) / 2, w, h);
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return visible && layout.list().contains(mx, my) && super.isMouseOver(mx, my);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button == 0 && visible && tab == TAB_TASKS && state() == ClaimState.READY
                    && layout.list().contains(mx, my) && claimButton().contains(mx, my)) {
                pressAnim = 1.0F;
                claim(task);
                return true;
            }
            return false;
        }

        /** 键盘激活（焦点在本行时按回车/空格）：与点按钮等价，鼠标仍只认按钮矩形。 */
        private void activateByKeyboard() {
            if (visible && tab == TAB_TASKS && state() == ClaimState.READY) {
                playClick();
                pressAnim = 1.0F;
                claim(task);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            BoardRect r = new BoardRect(getX(), getY(), getWidth(), getHeight());
            ClaimState state = state();
            boolean over = isHovered && tab == TAB_TASKS;
            hoverAnim = GuiFx.approach(hoverAnim, over ? 1.0F : 0.0F, frameDelta, 80.0F);
            pressAnim = GuiFx.approach(pressAnim, 0.0F, frameDelta, 90.0F);

            int accent = state.accent;
            int fill = state == ClaimState.DONE ? DailyBoardTheme.PAPER_TOP : DailyBoardTheme.CARD_TOP;
            DailyBoardTheme.box(g, r, fill, isFocused() ? DailyBoardTheme.BLUE : DailyBoardTheme.CARD_LINE);
            g.fill(r.x(),r.y(),r.x()+3,r.bottom(),accent);
            BoardRect button = claimButton();
            int textX = r.x()+12, textRight = button.x()-12;
            g.fill(button.x()-7,r.y()+8,button.x()-6,r.bottom()-8,DailyBoardTheme.CARD_LINE);
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, nullToEmpty(task.title()), textRight-textX),
                    textX, r.y()+8, DailyBoardTheme.INK);
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, nullToEmpty(task.description()), textRight-textX),
                    textX, r.y()+22, DailyBoardTheme.INK_SOFT);
            String reward = Component.translatable(KEY + "row.reward", nullToEmpty(task.reward())).getString();
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, reward, textRight-textX),
                    textX, r.y()+36, state == ClaimState.DONE ? DailyBoardTheme.MUTED : DailyBoardTheme.GOLD);
            float ratio = task.target() <= 0 ? 1.0F : Mth.clamp(task.progress()/(float)task.target(), 0, 1);
            DailyBoardTheme.progressBar(g,textX,textRight,r.bottom()-10,4,ratio,DailyBoardTheme.TRACK,accent);
            String counter = task.progress()+" / "+task.target();
            DailyBoardTheme.textCentered(g, font, Component.literal(DailyBoardTheme.fit(font,counter,button.w())),
                    button.cx(), button.bottom()+5, DailyBoardTheme.INK_SOFT);
            if (state == ClaimState.READY) {
                DailyBoardTheme.button(g, font, button, state.label(), state.fill, state.fill,
                        state.text, over && button.contains(mouseX,mouseY) ? 1 : 0);
            } else {
                DailyBoardTheme.textCentered(g,font,state.label(),button.cx(),button.cy()-4,state.text);
            }
        }



        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(nullToEmpty(task.title())));
            output.add(NarratedElementType.HINT,
                    Component.translatable(KEY + "row.progress", task.progress(), task.target()));
            output.add(NarratedElementType.USAGE, state().label());
        }
    }
}
