package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.DailyShopClient;
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
import java.util.Set;

/** Daily dispatch board: navigation, explicit status filters, fixed action columns, the shop and the mailbox. */
public final class DailyTaskScreen extends Screen {

    // =====================================================================
    // 常量
    // =====================================================================

    private static final String KEY = "screen.habitrain_lottery.daily.";

    private static final int TAB_TASKS = 0;
    private static final int TAB_SHOP = 1;
    private static final int TAB_MAIL = 2;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_PROGRESS = 1;
    private static final int FILTER_CLAIMABLE = 2;
    private static final int FILTER_DONE = 3;

    /** 每五秒主动向服务端要一次快照，保证余额与进度不会长时间陈旧。 */
    private static final long REFRESH_MILLIS = 5_000L;
    /** 点击领取后，在收到服务端回执前最多锁这么久，避免网络异常把按钮永久锁死。 */
    private static final long CLAIM_TIMEOUT_MILLIS = 6_000L;
    private static final long ENTER_MILLIS = 220L;
    /** 第一次点「购买」只进入确认态；这段时间内再点一次才真正下单。 */
    private static final long CONFIRM_MILLIS = 3_000L;


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
    private int hoverFilter = -1;
    private double scroll;
    private double pageScroll;

    private final List<TaskRow> rows = new ArrayList<>();
    private final List<NavTab> navTabs = new ArrayList<>();
    private final List<StatusFilter> statusFilters = new ArrayList<>();
    private MiniButton closeButton;
    private final DailyMailPage mailPage = new DailyMailPage(this::playClick, this::requestSnapshot);

    /** 已发出领取请求、尚未收到服务端回执的任务 ID。 */
    private final Set<String> pendingClaims = new HashSet<>();
    /** 处于「再点一次确认」状态的商品，以及确认态的截止时间。 */
    private String confirmShopId;
    private long confirmUntil;
    /** 已发出购买请求、尚未收到新快照的商品。 */
    private String pendingBuy;
    private long buyLockMillis;

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

    /** 邮箱方块 / {@code mailbox} 指令的入口：直接落在终端的「邮箱」页。 */
    public static DailyTaskScreen mailbox(Screen parent) {
        DailyTaskScreen screen = new DailyTaskScreen(parent, null);
        screen.tab = TAB_MAIL;
        return screen;
    }

    /** 终端已打开时再次收到「打开邮箱」：切到邮箱页并刷新。 */
    public void openMailTab() {
        selectTab(TAB_MAIL);
        mailPage.poll();
    }

    /** 服务端推送新快照时调用（刷新、领取回执、被外部打开都会走这里）。 */
    public void applySnapshot(DailyTaskSnapshot value) {
        if (value == null) {
            return;
        }
        this.snapshot = value;
        // 快照到达即视为权威状态，清掉本地等待标记
        pendingClaims.clear();
        pendingBuy = null;

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

        closeButton = new MiniButton(Component.translatable(KEY + "close"), layout.close());
        addRenderableWidget(closeButton);

        for (AbstractWidget widget : mailPage.widgets()) {
            addRenderableWidget(widget);
        }

        placeWidgets();
        rebuildRows();
        if (tab == TAB_MAIL) {
            setFocused(mailPage.claimButton);
        } else {
            setFocused(rows.isEmpty() ? navTabs.get(tab) : rows.get(0));
        }
        if (snapshot == null) {
            requestSnapshot();
        }
        // 邮箱页的未领数量也显示在导航上，所以不论落在哪一页都同步一次
        mailPage.poll();
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
        if (pendingBuy != null && now - buyLockMillis > CLAIM_TIMEOUT_MILLIS) {
            pendingBuy = null;
        }
        mailPage.sync();
        mailPage.tick(now);
        if (now - lastRequestMillis < REFRESH_MILLIS) {
            return;
        }
        lastRequestMillis = now;
        requestSnapshot();
        if (tab == TAB_MAIL) {
            mailPage.poll();
        }
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
        mailPage.layout(layout);
        mailPage.setVisible(tab == TAB_MAIL);
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
        confirmShopId = null;
        for (StatusFilter widget : statusFilters) widget.visible = tab == TAB_TASKS;
        pageScroll = 0;
        for (TaskRow row : rows) {
            row.visible = tab == TAB_TASKS;
        }
        mailPage.setVisible(tab == TAB_MAIL);
        List<AbstractWidget> focusable = new ArrayList<>();
        if (tab == TAB_TASKS && !rows.isEmpty()) {
            focusable.addAll(rows);
        }
        if (tab == TAB_MAIL) {
            focusable.add(mailPage.claimButton);
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
            DailyBoardTheme.text(g, font, "Q / S / M", layout.rail().x()+14,
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
            } else if (tab == TAB_SHOP) {
                drawShopPage(g);
            } else {
                mailPage.sync();
                mailPage.render(g, font, layout, mouseX, mouseY, nowMillis);
            }
        } finally {
            if (fade) {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }

        suppressBackground = true;
        try {
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
                    snapshot.greenApples(), snapshot.cardTotal()).getString();
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, balances, layout.subtitle().w()),
                    title.x(), layout.subtitle().y()+16, DailyBoardTheme.MUTED);
        }
        DailyBoardTheme.hairline(g, layout.summary().x(), layout.summary().right(),
                layout.summary().bottom()-1, DailyBoardTheme.PAPER_BORDER);
    }

    private void drawTasksPage(GuiGraphics g) {

        drawToolbar(g);
        drawTaskList(g);
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

    // ---------------------------------------------------------------------
    // 商店页
    // ---------------------------------------------------------------------

    /** 商店页内容区（页头下方是商品网格）。 */
    private BoardRect shopArea() {
        return new BoardRect(layout.content().x() + layout.pagePad(), layout.pageTop(),
                layout.content().w() - layout.pagePad() * 2, layout.pageHeight());
    }

    /** 商品网格顶部：页头两行说明之下。 */
    private int shopTop() {
        return shopArea().y() + 30;
    }

    /** 第 {@code index} 个商品卡片（已应用滚动）。 */
    private BoardRect shopTile(int index) {
        BoardRect area = shopArea();
        int cols = layout.shopCols();
        int gap = layout.shopGap();
        int w = (area.w() - (cols - 1) * gap) / cols;
        return new BoardRect(area.x() + (index % cols) * (w + gap),
                shopTop() + (index / cols) * (layout.shopH() + gap) - (int) pageScroll, w, layout.shopH());
    }

    private BoardRect shopBuyButton(BoardRect tile) {
        int w = layout.compact() ? 64 : 76;
        return new BoardRect(tile.right() - 8 - w, tile.bottom() - 8 - 20, w, 20);
    }

    private void drawShopPage(GuiGraphics g) {
        DailyBoardTheme.rightPage(g, layout.content(), 8);
        BoardRect area = shopArea();
        DailyBoardTheme.textScaled(g, font, Component.translatable(KEY + "shop.title").getString(),
                area.x(), area.y(), 1.15F, DailyBoardTheme.INK);
        String balance = Component.translatable(KEY + "shop.balance",
                snapshot == null ? "--" : snapshot.greenApples()).getString();
        int bw = font.width(balance);
        DailyBoardTheme.text(g, font, balance, area.right() - bw, area.y() + 2, DailyBoardTheme.GREEN);
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font,
                        Component.translatable(KEY + "shop.hint").getString(), area.w()),
                area.x(), area.y() + 16, DailyBoardTheme.MUTED);

        int top = shopTop();
        BoardRect grid = new BoardRect(area.x(), top, area.w(), area.bottom() - top);
        List<DailyTaskSnapshot.ShopRow> items = shopItems();
        if (snapshot == null || !snapshot.shopOpen() || items.isEmpty()) {
            String key = snapshot == null ? "empty.syncing" : !snapshot.shopOpen() ? "shop.closed" : "shop.empty";
            DailyBoardTheme.textCentered(g, font, Component.translatable(KEY + key), grid.cx(), grid.cy() - 4,
                    DailyBoardTheme.MUTED);
            return;
        }
        pageScroll = Mth.clamp(pageScroll, 0, pageMaxScroll());

        DailyTaskSnapshot.ShopRow hovered = null;
        GuiFx.beginClip(g, grid.x() - 2, grid.y(), grid.right() + 2, grid.bottom());
        for (int i = 0; i < items.size(); i++) {
            BoardRect r = shopTile(i);
            if (r.bottom() < grid.y() || r.y() > grid.bottom()) {
                continue;
            }
            drawShopTile(g, r, items.get(i));
            if (grid.contains(mouseX, mouseY) && r.contains(mouseX, mouseY)
                    && !shopBuyButton(r).contains(mouseX, mouseY)) {
                hovered = items.get(i);
            }
        }
        GuiFx.endClip(g);
        DailyBoardTheme.scrollbar(g, area.right() + 3, grid, pageScroll, (int) pageMaxScroll());
        // 气泡必须等裁剪结束再画，否则会被网格的 scissor 切掉
        if (hovered != null) {
            List<FormattedCharSequence> lines = font.split(shopTooltip(hovered), 200);
            DailyBoardTheme.tooltip(g, font, lines, mouseX, mouseY, layout.content());
        }
    }

    private void drawShopTile(GuiGraphics g, BoardRect r, DailyTaskSnapshot.ShopRow item) {
        ShopState state = shopState(item);
        int accent = kindAccent(item.kind());
        boolean dim = state == ShopState.SOLD_OUT || state == ShopState.OWNED;
        DailyBoardTheme.box(g, r, dim ? DailyBoardTheme.PAPER_TOP : DailyBoardTheme.CARD_TOP,
                r.contains(mouseX, mouseY) ? GuiFx.mix(DailyBoardTheme.CARD_LINE, accent, 0.5F)
                        : DailyBoardTheme.CARD_LINE);
        g.fill(r.x(), r.y(), r.x() + 3, r.bottom(), dim ? DailyBoardTheme.FAINT : accent);

        BoardRect mark = new BoardRect(r.x() + 10, r.y() + 8, 18, 18);
        DailyBoardTheme.softCard(g, mark, 5, GuiFx.mix(DailyBoardTheme.CARD_TOP, accent, 0.16F),
                GuiFx.mix(DailyBoardTheme.CARD_BOTTOM, accent, 0.10F),
                GuiFx.mix(DailyBoardTheme.CARD_LINE, accent, 0.35F));
        DailyBoardTheme.textCentered(g, font, Component.literal(kindGlyph(item.kind())), mark.cx(), mark.cy() - 4,
                accent);

        String price = item.price() == 0 ? Component.translatable(KEY + "shop.free").getString()
                : Component.translatable(KEY + "shop.price", item.price()).getString();
        int priceW = font.width(price);
        boolean poor = snapshot != null && snapshot.greenApples() < item.price();
        DailyBoardTheme.text(g, font, price, r.right() - 8 - priceW, r.y() + 13,
                poor && !dim ? DailyBoardTheme.WARN : DailyBoardTheme.GREEN);
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, nullToEmpty(item.title()), r.w() - 46 - priceW),
                r.x() + 34, r.y() + 13, dim ? DailyBoardTheme.MUTED : DailyBoardTheme.INK);

        int textW = r.w() - 20;
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, nullToEmpty(item.reward()), textW),
                r.x() + 10, r.y() + 32, dim ? DailyBoardTheme.MUTED : DailyBoardTheme.GOLD);
        if (!layout.compact() && !nullToEmpty(item.description()).isEmpty()) {
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, item.description(), textW),
                    r.x() + 10, r.y() + 45, DailyBoardTheme.MUTED);
        }

        BoardRect button = shopBuyButton(r);
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, limitText(item), button.x() - r.x() - 18),
                r.x() + 10, button.y() + 6, item.limited() ? DailyBoardTheme.INK_SOFT : DailyBoardTheme.MUTED);
        Component label = Component.translatable(KEY + "shop.state." + state.key);
        boolean hover = button.contains(mouseX, mouseY);
        switch (state) {
            case BUY -> DailyBoardTheme.button(g, font, button, label, DailyBoardTheme.GREEN, DailyBoardTheme.GREEN,
                    0xFFFFFFFF, hover ? 1 : 0);
            case CONFIRM -> DailyBoardTheme.button(g, font, button, label, DailyBoardTheme.AMBER_DEEP,
                    DailyBoardTheme.AMBER_DEEP, 0xFFFFFFFF, hover ? 1 : 0);
            default -> DailyBoardTheme.textCentered(g, font, label, button.cx(), button.cy() - 4,
                    state == ShopState.POOR ? DailyBoardTheme.WARN : DailyBoardTheme.MUTED);
        }
    }

    private Component shopTooltip(DailyTaskSnapshot.ShopRow item) {
        var out = Component.empty().append(Component.literal(nullToEmpty(item.title())));
        if (!nullToEmpty(item.description()).isEmpty()) {
            out.append("\n").append(Component.literal(item.description()));
        }
        out.append("\n").append(Component.translatable(KEY + "shop.tip.reward", nullToEmpty(item.reward())));
        out.append("\n").append(Component.literal(limitText(item)));
        if (item.limited() && item.resetDay() >= 0) {
            out.append("\n").append(Component.translatable(KEY + "shop.tip.reset",
                    LocalDate.ofEpochDay(item.resetDay()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
        }
        return out;
    }

    private String limitText(DailyTaskSnapshot.ShopRow item) {
        if (!item.limited()) {
            return Component.translatable(KEY + "shop.unlimited").getString();
        }
        if (item.limitDays() <= 0) {
            return Component.translatable(KEY + "shop.limit.forever", item.used(), item.limitCount()).getString();
        }
        if (item.limitDays() == 1) {
            return Component.translatable(KEY + "shop.limit.daily", item.used(), item.limitCount()).getString();
        }
        return Component.translatable(KEY + "shop.limit.days", item.limitDays(), item.used(), item.limitCount())
                .getString();
    }

    private ShopState shopState(DailyTaskSnapshot.ShopRow item) {
        if (item.owned()) return ShopState.OWNED;
        if (item.limited() && item.used() >= item.limitCount()) return ShopState.SOLD_OUT;
        if (item.id().equals(pendingBuy)) return ShopState.WAIT;
        if (snapshot == null || snapshot.greenApples() < item.price()) return ShopState.POOR;
        if (item.id().equals(confirmShopId) && nowMillis < confirmUntil) return ShopState.CONFIRM;
        return ShopState.BUY;
    }

    /** 点击商品卡片上的按钮：第一次进入确认态，确认态内再点才发出购买。 */
    private boolean clickShop(double mx, double my) {
        BoardRect area = shopArea();
        if (my < shopTop() || my >= area.bottom()) {
            return false;
        }
        List<DailyTaskSnapshot.ShopRow> items = shopItems();
        for (int i = 0; i < items.size(); i++) {
            BoardRect tile = shopTile(i);
            if (!tile.contains(mx, my)) {
                continue;
            }
            DailyTaskSnapshot.ShopRow item = items.get(i);
            if (!shopBuyButton(tile).contains(mx, my)) {
                confirmShopId = null;
                return true;
            }
            ShopState state = shopState(item);
            if (state == ShopState.BUY) {
                playClick();
                confirmShopId = item.id();
                confirmUntil = System.currentTimeMillis() + CONFIRM_MILLIS;
            } else if (state == ShopState.CONFIRM) {
                playClick();
                confirmShopId = null;
                if (DailyShopClient.buy(item.id(), item.price())) {
                    pendingBuy = item.id();
                    buyLockMillis = System.currentTimeMillis();
                }
            }
            return true;
        }
        confirmShopId = null;
        return false;
    }

    private static int kindAccent(String kind) {
        return switch (nullToEmpty(kind)) {
            case "card" -> WarehouseTheme.ACCENT_CIVILIAN;
            case "self_select" -> WarehouseTheme.ACCENT_SELF_SELECT;
            case "limit_break" -> WarehouseTheme.ACCENT_LIMIT_BREAK;
            case "skin" -> DailyBoardTheme.VIOLET;
            case "crate" -> DailyBoardTheme.AMBER_DEEP;
            case "key" -> DailyBoardTheme.GOLD;
            case "title" -> DailyBoardTheme.BLUE;
            default -> DailyBoardTheme.GREEN;
        };
    }

    private static String kindGlyph(String kind) {
        return switch (nullToEmpty(kind)) {
            case "card" -> "◆";
            case "self_select" -> "✦";
            case "limit_break" -> "▲";
            case "skin" -> "★";
            case "crate" -> "▣";
            case "key" -> "✚";
            case "title" -> "✎";
            default -> "●";
        };
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
            if (tab == TAB_SHOP && clickShop(mx, my)) {
                return true;
            }
            if (tab == TAB_MAIL && mailPage.mouseClicked(mx, my)) {
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
        } else if (tab == TAB_MAIL) {
            if (mailPage.mouseScrolled(mx, my, scrollY)) {
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
            if (focused instanceof DailyMailPage.MailButton button && tab == TAB_MAIL) {
                button.press();
                return true;
            }
        }
        if (tab == TAB_MAIL && mailPage.keyPressed(keyCode)) {
            return true;
        }
        switch (keyCode) {
            case 256 -> { // Esc
                onClose();
                return true;
            }
            case 82 -> { // R 手动刷新
                lastRequestMillis = System.currentTimeMillis();
                requestSnapshot();
                if (tab == TAB_MAIL) {
                    mailPage.refresh();
                } else {
                    mailPage.poll();
                }
                return true;
            }
            case 77 -> { // M 邮箱
                selectTab(TAB_MAIL);
                return true;
            }
            case 83 -> { // S 商店
                selectTab(TAB_SHOP);
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
        } else if (tab == TAB_MAIL) {
            mailPage.scrollPage(direction);
        } else {
            pageScroll = Mth.clamp(pageScroll + direction * layout.pageHeight(), 0, pageMaxScroll());
        }
    }

    private double pageMaxScroll() {
        if (tab == TAB_SHOP) {
            int count = shopItems().size();
            if (count == 0) {
                return 0;
            }
            int rowsN = (count + layout.shopCols() - 1) / layout.shopCols();
            int total = rowsN * layout.shopH() + (rowsN - 1) * layout.shopGap();
            return Math.max(0, total - (shopArea().bottom() - shopTop()));
        }
        return 0;
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

    private List<DailyTaskSnapshot.ShopRow> shopItems() {
        if (snapshot == null || !snapshot.shopOpen() || snapshot.shop() == null) {
            return List.of();
        }
        return snapshot.shop();
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
            case TAB_SHOP -> KEY + "tab.shop";
            case TAB_MAIL -> KEY + "tab.mail";
            default -> KEY + "tab.tasks";
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // =====================================================================
    // 内部记录
    // =====================================================================

    /** 商品按钮的状态；{@code key} 对应 {@code shop.state.*} 文案。 */
    private enum ShopState {
        BUY("buy"), CONFIRM("confirm"), WAIT("wait"), POOR("poor"), SOLD_OUT("sold_out"), OWNED("owned");

        private final String key;

        ShopState(String key) {
            this.key = key;
        }
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
            int unclaimed = index == TAB_MAIL ? mailPage.unclaimedCount() : 0;
            if (unclaimed > 0) {
                // 未领取邮件数：导航右上角的小方标
                String n = unclaimed > 99 ? "99+" : String.valueOf(unclaimed);
                int bw = Math.max(9, font.width(n) + 4);
                int bx = r.right() - bw - 2;
                g.fill(bx, r.y() + 2, bx + bw, r.y() + 12, DailyBoardTheme.AMBER_DEEP);
                DailyBoardTheme.textCentered(g, font, Component.literal(n), bx + bw / 2, r.y() + 3, 0xFFFFFFFF);
            }
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
    // 控件：关闭按钮
    // =====================================================================

    private final class MiniButton extends AbstractWidget {

        private float hoverAnim;

        private MiniButton(Component label, BoardRect box) {
            super(box.x(), box.y(), box.w(), box.h(), label);
            setTooltip(Tooltip.create(label));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            BoardRect r = new BoardRect(getX(), getY(), getWidth(), getHeight());
            hoverAnim = GuiFx.approach(hoverAnim, (isHovered || isFocused()) ? 1.0F : 0.0F, frameDelta, 70.0F);
            if (hoverAnim > 0.02F) {
                GuiFx.roundRect(g, r.x(), r.y(), r.right(), r.bottom(), 0,
                        GuiFx.alpha(DailyBoardTheme.WARN, (int) (30 * hoverAnim)));
            }
            DailyBoardTheme.textCentered(g, font, Component.literal("\u2716"), r.cx(), r.cy() - 4,
                    GuiFx.mix(layout.sidebar() ? DailyBoardTheme.MUTED : DailyBoardTheme.NAV_TEXT, DailyBoardTheme.WARN, hoverAnim));
        }

        @Override
        public void onClick(double mx, double my) {
            playClick();
            onClose();
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
                    .append(task.random() ? Component.translatable(KEY + "row.random_hint") : Component.empty())
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
            int titleRight = textRight;
            if (task.random()) {
                // 今日随机抽到的任务：标题右侧的小标签，与固定任务区分
                String tag = Component.translatable(KEY + "row.random").getString();
                int tw = font.width(tag) + 8;
                String shown = DailyBoardTheme.fit(font, nullToEmpty(task.title()), textRight - textX - tw - 6);
                int tx = textX + font.width(shown) + 6;
                g.fill(tx, r.y()+6, tx + tw, r.y()+17, DailyBoardTheme.VIOLET_SOFT);
                g.fill(tx, r.y()+6, tx + 1, r.y()+17, DailyBoardTheme.VIOLET);
                DailyBoardTheme.text(g, font, tag, tx + 4, r.y()+8, DailyBoardTheme.VIOLET);
                titleRight = tx - 6;
            }
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, nullToEmpty(task.title()), titleRight-textX),
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
