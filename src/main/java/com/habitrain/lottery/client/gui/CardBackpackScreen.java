package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.CardUseRequestC2S;
import com.habitrain.lottery.network.LotteryNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色卡背包 —— 与「自选角色」页同源的现代卡片式重做。
 *
 * <p>原实现是六个原版按钮加一行计数文字，没有任何图形与动效。本版把它重做成
 * {@link RoleSelectScreen} 的同一套视觉语言：共用色板与外壳（{@link CardUiStyle}）、
 * 共用缓动与入场节奏（{@link GuiFx}），并给每张卡配上立绘
 * （{@code assets/habitrain_lottery/textures/gui/cards/*.png}）。</p>
 *
 * <h2>布局</h2>
 * <p><b>六张卡一律平铺在同一个列表里</b>，每张同样大小、同一套版式，顺序与存档 / 配置端的
 * 规范顺序一致（乘客 → 独立 → 中立偏杀手 → 杀手 → 自选 → 突破上限），不额外分组、不折叠：
 * 自选卡与突破上限卡只是配色不同，不再单独占一行或加角标。宽窗口 3 列 2 行，窄窗口退化为
 * 2 列 3 行；卡片高度实在不够时再用「立绘 + 单行标题」的极简版式，任何 GUI 缩放下都不重叠。</p>
 *
 * <h2>行为约束（与原实现逐条对齐，不能回退）</h2>
 * <ul>
 *   <li>打开背包时必须向服务端请求一次 {@code inventory} 快照；数量与次数全部以服务端
 *       下发为准，客户端不做任何本地推算。</li>
 *   <li>点击阵营卡 / 自选卡 → 发送 {@link CardUseRequestC2S}，由服务端回
 *       {@code CardUseMenuS2C} 打开后续页面；点击突破上限卡 → 直接发送 {@code bonus}
 *       确认包（原实现即如此，突破上限不经过中间菜单）。</li>
 *   <li>突破上限卡在等待服务端回执期间保持禁用，回执（inventory 版本变化）或超时后恢复。</li>
 *   <li>对局一旦离开大厅（{@link CardGuiGameState}）立刻关闭，且不发送任何包。</li>
 *   <li>点击有 ~190ms 的确认动画，动画期间冻结输入；动画结束才真正发包，并用
 *       {@code activatingSlot} + {@code submitLockMillis} 双重保护，一次点击最多发一个包。</li>
 *   <li>界面不暂停游戏（{@link #isPauseScreen()} 为 {@code false}）。</li>
 * </ul>
 *
 * <h2>修掉的老问题</h2>
 * <ul>
 *   <li><b>读数闪 0：</b>原实现在 {@code init()} 里清空 {@code cardBalances} 后立刻重绘，
 *       于是每次打开背包都会先显示「0 张 / 全部不可用」。现在改成骨架（同步中）状态，
 *       请求被服务端限流丢弃时自动重试，超时后给出明确提示而不是静默显示 0。</li>
 *   <li><b>窗口缩放丢数据：</b>{@code init()} 会被 resize 重复调用，原实现每次都会重新
 *       清空余额并再发一次请求；现在只在首次打开时初始化一次。</li>
 *   <li><b>悬停动画不生效：</b>页面每帧先把 {@code lastFrameMillis} 提前更新，控件里算出的
 *       帧间隔恒为 0，{@link GuiFx#approach} 因此永远不动（自选角色页有同样的问题）。
 *       现在由页面统一计算 {@link #frameDelta} 再交给控件使用。</li>
 * </ul>
 */
public final class CardBackpackScreen extends Screen {

    // ---------------------------------------------------------------------
    // 时间常量（毫秒）
    // ---------------------------------------------------------------------
    private static final float PANEL_ENTER_MILLIS = 260.0F;
    private static final float CARD_STAGGER_MILLIS = 34.0F;
    private static final float CARD_ENTER_WINDOW_MILLIS = 470.0F;
    private static final float CARD_ENTER_MILLIS = 240.0F;
    /** 点击后确认动画的播放时长；播完才真正发包。 */
    private static final float ACTIVATE_MILLIS = 190.0F;
    /** 发包后忽略重复点击的冷却，服务端无响应时自动解除。 */
    private static final long SUBMIT_LOCK_MILLIS = 1500L;
    /** 突破上限卡等待服务端回执的最长时间。 */
    private static final long LIMIT_BREAK_WAIT_MILLIS = 5000L;
    /** 库存回执重试间隔（服务端对 card_use_request 的限流窗口是 500ms）。 */
    private static final long INVENTORY_RETRY_MILLIS = 1200L;
    private static final int INVENTORY_MAX_ATTEMPTS = 4;

    private static final String INVENTORY_KEY = "inventory";
    private static final int ART_TEXTURE_SIZE = 128;

    private static final int GRID_GAP = 8;
    private static final int NAV_H = 20;
    private static final int BACK_WIDTH = 92;
    private static final int BASE_DAILY_USES = 4;
    /** 卡片高度低于这个值就切到「立绘 + 单行标题」的极简版式。 */
    private static final int TINY_CARD_HEIGHT = 76;

    /**
     * 六张卡的展示顺序，与存档 / 配置端 {@code PlayerCardAdminModels.orderedCards} 的规范顺序
     * 完全一致：四张阵营卡在前，两张特殊卡紧随其后，<b>同一个列表、同样的卡片尺寸</b>，
     * 不做分组也不折叠。
     */
    private static final List<Slot> SLOTS = List.of(
            Slot.CIVILIAN, Slot.NEUTRAL, Slot.NEUTRAL_FOR_KILLER, Slot.KILLER,
            Slot.SELF_SELECT, Slot.LIMIT_BREAK);

    // ---------------------------------------------------------------------
    // 卡牌定义
    // ---------------------------------------------------------------------

    /** 每张卡的静态视觉信息与立绘路径。 */
    private enum Slot {
        KILLER("killer", CardUiStyle.ACCENT_KILLER, "\u2716", false),
        CIVILIAN("civilian", CardUiStyle.ACCENT_CIVILIAN, "\u271A", false),
        NEUTRAL("neutral", CardUiStyle.ACCENT_NEUTRAL, "\u25C6", false),
        NEUTRAL_FOR_KILLER("neutral_for_killer", CardUiStyle.ACCENT_NEUTRAL_FOR_KILLER,
                "\u2605", false),
        SELF_SELECT("self_select", CardUiStyle.ACCENT_SELF_SELECT, "\u2726", true),
        LIMIT_BREAK("limit_break", CardUiStyle.ACCENT_LIMIT_BREAK, "\u25B2", true);

        private final String id;
        private final int accent;
        private final String glyph;
        /** 仅用于选择提示文案：虚拟卡（自选 / 突破上限）不是抽奖池里的阵营卡。 */
        private final boolean virtualCard;
        private ResourceLocation art;
        private boolean artChecked;
        /** 名称每帧都要画，缓存翻译组件避免重复查表（TranslatableComponent 在渲染时才解析语言）。 */
        private Component cachedName;

        Slot(String id, int accent, String glyph, boolean virtualCard) {
            this.id = id;
            this.accent = accent;
            this.glyph = glyph;
            this.virtualCard = virtualCard;
        }

        private String nameKey() {
            return "screen.habitrain_lottery.config.cards." + id;
        }

        private Component displayName() {
            if (cachedName == null) {
                cachedName = Component.translatable(nameKey());
            }
            return cachedName;
        }

        private String hintKey() {
            if (!virtualCard) {
                return "screen.habitrain_lottery.backpack.faction_hint";
            }
            return "screen.habitrain_lottery.backpack."
                    + (this == SELF_SELECT ? "self_hint" : "limit_break_hint");
        }

        /** 立绘贴图；资源缺失时返回 {@code null}，由调用方退化为程序化徽记。 */
        private ResourceLocation art() {
            if (!artChecked) {
                artChecked = true;
                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(
                        "habitrain_lottery", "textures/gui/cards/" + id + ".png");
                art = CardUiStyle.textureExists(candidate) ? candidate : null;
            }
            return art;
        }
    }

    /** 一张卡在当前时刻的可用状态。 */
    private enum Status {
        READY("ready", CardUiStyle.OK),
        NO_USES("no_uses", CardUiStyle.GOLD),
        EMPTY("empty", CardUiStyle.MUTED),
        SYNCING("syncing", CardUiStyle.CYAN),
        /** 重试次数用尽仍拿不到快照：数量与次数都不可信，既不能显示 0 也不能显示旧值。 */
        UNKNOWN("unknown", CardUiStyle.DANGER),
        PENDING("pending", CardUiStyle.GOLD),
        LOCKED("locked", CardUiStyle.DANGER);

        private final String key;
        private final int color;
        private Component cachedLabel;

        Status(String key, int color) {
            this.key = key;
            this.color = color;
        }

        private Component label() {
            if (cachedLabel == null) {
                cachedLabel = Component.translatable(
                        "screen.habitrain_lottery.backpack.state." + key);
            }
            return cachedLabel;
        }
    }

    // ---------------------------------------------------------------------
    // 状态
    // ---------------------------------------------------------------------

    private final Screen parent;
    private final List<CardTile> tiles = new ArrayList<>();

    private Button backButton;

    /**
     * 当前由方向键选中的卡片。
     *
     * <p><b>没有独立字段</b>：焦点以控件树（{@link #getFocused()}）为唯一真源。鼠标点击卡片
     * 同样会改变原生焦点，若在屏幕里再存一份副本，鼠标点过之后方向键/Enter 仍会作用在旧卡上，
     * 可能误用一张完全不同的卡。焦点环本来就画的是 {@code isFocused()}，两者现在天然一致。</p>
     */
    private CardTile focusedTile() {
        return getFocused() instanceof CardTile tile ? tile : null;
    }

    /** 正在播放确认动画的卡；非 null 时冻结全部输入。 */
    private Slot activatingSlot;
    private long activateStartMillis;
    private long submitLockMillis = -1L;
    private long limitBreakPendingSince = -1L;
    private boolean closedForGameStart;

    // 库存同步
    private boolean inventoryRequested;
    private int inventoryEpoch;
    private long lastRequestMillis;
    private int requestAttempts;
    private long inventoryArrivedMillis = -1L;
    private boolean gaveUp;

    // 布局
    private boolean compact;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int headerH;
    private int gridTop;
    private int navY;
    private int titleY;
    private int columns = 3;
    private int cardWidth;
    private int cardHeight;
    private int gridRows = 2;
    /** 页脚提示行是否绘制；窗口太矮时会让位给卡片（见 computeLayout）。 */
    private boolean showHint = true;

    // 右上角次数胶囊的度量（只随语言/尺寸变化，放到布局阶段算一次）
    private String chipLabelFaction = "";
    private String chipLabelSelf = "";
    private int chipWidth = 96;
    private int chipX;
    /** 胶囊文本缓存：索引 0 = 阵营卡、1 = 自选卡；值不变就不重新查表。 */
    private final String[] chipText = new String[2];
    private final int[] chipTextValue = {Integer.MIN_VALUE, Integer.MIN_VALUE};
    private final boolean[] chipTextKnown = {true, true};

    // 动画时钟
    private long baseMillis;
    private long nowMillis;
    private long lastFrameMillis;
    private long pageEnterMillis;
    /** 上一帧到这一帧的毫秒数；由 {@link #render} 统一计算，供所有控件使用。 */
    private float frameDelta = 16.0F;
    private float screenFocus = 1.0F;

    public CardBackpackScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_lottery.backpack.title"));
        this.parent = parent;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        tiles.clear();
        setFocused(null);

        long now = System.currentTimeMillis();
        baseMillis = now;
        pageEnterMillis = now;
        lastFrameMillis = now;
        nowMillis = now;
        frameDelta = 16.0F;

        computeLayout();
        buildTiles();

        backButton = Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(contentX + (contentW - BACK_WIDTH) / 2, navY, BACK_WIDTH, NAV_H)
                .build();
        addRenderableWidget(backButton);

        // 只在首次打开时清空并请求：resize 会重复调用 init()，不能每次都把面板打成空。
        if (!inventoryRequested) {
            inventoryRequested = true;
            LotteryNetwork.ClientLotteryState.cardBalances = java.util.Map.of();
            inventoryEpoch = LotteryNetwork.ClientLotteryState.cardInventoryVersion;
            inventoryArrivedMillis = -1L;
            gaveUp = false;
            requestAttempts = 0;
            requestInventory(now);
        }

        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
        }
    }

    private void requestInventory(long now) {
        lastRequestMillis = now;
        requestAttempts++;
        if (!ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) {
            // 服务端没装本模组时不该走到这个页面；真走到了就直接展示已有数据。
            gaveUp = true;
            inventoryArrivedMillis = now;
            return;
        }
        try {
            ClientPlayNetworking.send(new CardUseRequestC2S(INVENTORY_KEY));
        } catch (Throwable ignored) {
            // 网络层异常时保留当前显示，下一轮 tick 会重试。
        }
    }

    // =====================================================================
    // 布局
    // =====================================================================

    private void computeLayout() {
        compact = height < 420 || width < 640;
        int pad = compact ? 12 : 18;
        int maxPanelW = compact ? 560 : 800;

        panelW = Math.min(Math.max(280, width - 16), maxPanelW);
        panelW = Math.min(panelW, width);
        contentW = Math.max(120, panelW - pad * 2);

        // 六张卡同一个列表：优先 3 列 2 行，宽度不够再退化成 2 列 3 行。
        int minTileW = compact ? 96 : 150;
        columns = 3;
        while (columns > 2 && (contentW - (columns - 1) * GRID_GAP) / columns < minTileW) {
            columns--;
        }
        cardWidth = Math.max(64, (contentW - (columns - 1) * GRID_GAP) / columns);
        gridRows = (SLOTS.size() + columns - 1) / columns;

        headerH = compact ? 46 : 58;
        int baseFooterH = compact ? 42 : 48;
        int hintH = compact ? 13 : 15;

        int desiredCardH = Mth.clamp(Math.round(cardWidth * 0.72F),
                compact ? 74 : 110, compact ? 130 : 160);
        // 先按「页脚带提示行」试算一次；卡片被窗口高度压到过矮时，去掉提示行再算一次，
        // 保证极小 GUI 下卡片与页脚也不会重叠（页脚只留返回按钮）。
        int footerH = baseFooterH;
        showHint = true;
        for (int pass = 0; pass < 2; pass++) {
            int neededH = pad * 2 + headerH + gridRows * desiredCardH
                    + (gridRows - 1) * GRID_GAP + footerH;
            panelH = Math.min(Math.max(48, height - 16),
                    Math.max(compact ? 250 : 300, neededH));
            int availGrid = panelH - pad * 2 - headerH - footerH;
            cardHeight = Mth.clamp((availGrid - (gridRows - 1) * GRID_GAP) / gridRows,
                    34, desiredCardH);
            if (cardHeight >= 48 || !showHint) {
                break;
            }
            showHint = false;
            footerH = baseFooterH - hintH;
        }

        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        contentX = panelX + pad;

        titleY = panelY + pad + (compact ? 0 : 2);
        gridTop = panelY + pad + headerH;
        navY = panelY + panelH - pad - NAV_H;

        // 胶囊宽度用固定宽度的数字占位来量，数值变化时胶囊不会左右跳动。
        chipLabelFaction = Component.translatable(
                "screen.habitrain_lottery.backpack.chip.faction").getString();
        chipLabelSelf = Component.translatable(
                "screen.habitrain_lottery.backpack.chip.self").getString();
        String placeholder = Component.translatable(
                "screen.habitrain_lottery.backpack.chip.remaining", "88").getString();
        int barW = BASE_DAILY_USES * 5 + (BASE_DAILY_USES - 1) * 2;
        chipWidth = Mth.clamp(Math.max(font.width(chipLabelFaction), font.width(chipLabelSelf))
                + font.width(placeholder) + barW + 24, 96, Math.max(96, contentW / 2));
        chipX = panelX + panelW - pad - chipWidth;
        chipTextValue[0] = Integer.MIN_VALUE;
        chipTextValue[1] = Integer.MIN_VALUE;
    }

    private void buildTiles() {
        int index = 0;
        for (Slot slot : SLOTS) {
            int column = index % columns;
            int row = index / columns;
            addTile(slot, contentX + column * (cardWidth + GRID_GAP),
                    gridTop + row * (cardHeight + GRID_GAP), cardWidth, cardHeight, index);
            index++;
        }
    }

    private void addTile(Slot slot, int x, int y, int w, int h, int enterOrder) {
        CardTile tile = new CardTile(slot, x, y, w, h, enterOrder);
        tiles.add(tile);
        addRenderableWidget(tile);
    }

    // =====================================================================
    // 状态查询
    // =====================================================================

    private int balance(Slot slot) {
        return LotteryNetwork.ClientLotteryState.cardBalances.getOrDefault(slot.id, 0);
    }

    private int remainingUses(Slot slot) {
        return Math.max(0, slot == Slot.SELF_SELECT
                ? LotteryNetwork.ClientLotteryState.cardUseRemainingSelfUses
                : LotteryNetwork.ClientLotteryState.cardUseRemainingUses);
    }

    private boolean limitBreakPending() {
        return limitBreakPendingSince > 0
                && System.currentTimeMillis() - limitBreakPendingSince < LIMIT_BREAK_WAIT_MILLIS;
    }

    private boolean submitLocked() {
        return submitLockMillis > 0
                && System.currentTimeMillis() - submitLockMillis < SUBMIT_LOCK_MILLIS;
    }

    /** 确认动画期间或刚发包不久：不接受新的点击。 */
    private boolean busy() {
        return activatingSlot != null || submitLocked();
    }

    /** 首次快照尚未到达：数量与今日次数都不可信（重试放弃后仍然不可信）。 */
    private boolean inventoryUnknown() {
        return inventoryArrivedMillis < 0;
    }

    private boolean loading() {
        return inventoryUnknown() && !gaveUp;
    }

    /** 有数量、次数够（突破上限卡只看数量）时才算可用。 */
    private Status statusOf(Slot slot) {
        if (inventoryUnknown()) {
            // 还在等首次快照 → 骨架态；重试已放弃 → 明确的未知态，而不是假装「未持有」。
            return gaveUp ? Status.UNKNOWN : Status.SYNCING;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            return Status.LOCKED;
        }
        if (balance(slot) <= 0) {
            return Status.EMPTY;
        }
        // 确认动画期间一律显示「提交中」，突破上限卡也不例外（它没有每日次数）。
        if (activatingSlot == slot) {
            return Status.PENDING;
        }
        if (slot == Slot.LIMIT_BREAK) {
            return limitBreakPending() ? Status.PENDING : Status.READY;
        }
        if (remainingUses(slot) <= 0) {
            return Status.NO_USES;
        }
        return Status.READY;
    }

    private static int totalCards() {
        int total = 0;
        for (Slot slot : Slot.values()) {
            total += LotteryNetwork.ClientLotteryState.cardBalances.getOrDefault(slot.id, 0);
        }
        return total;
    }

    // =====================================================================
    // 交互
    // =====================================================================

    /** 点击一张卡：先播确认动画，动画结束才发包。 */
    private void activate(CardTile tile) {
        if (tile == null || closedForGameStart || busy()) {
            return;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return;
        }
        if (statusOf(tile.slot) != Status.READY) {
            return;
        }
        tile.press();
        activatingSlot = tile.slot;
        activateStartMillis = System.currentTimeMillis();
    }

    private void fireActivate(Slot slot) {
        activatingSlot = null;
        submitLockMillis = System.currentTimeMillis();
        if (slot == Slot.LIMIT_BREAK) {
            limitBreakPendingSince = submitLockMillis;
            LotteryClientNetwork.clientCardUseConfirm(slot.id, "bonus", "");
            return;
        }
        try {
            ClientPlayNetworking.send(new CardUseRequestC2S(slot.id));
        } catch (Throwable ignored) {
            // 发送失败时 submitLockMillis 会在 1.5s 后自动放行，玩家可以重试。
        }
    }

    @Override
    public void tick() {
        if (CardGuiGameState.gameActiveOrStarting() && !closedForGameStart) {
            closeForGameStart();
            return;
        }
        long now = System.currentTimeMillis();
        pollInventory(now);
        if (activatingSlot != null && now - activateStartMillis >= ACTIVATE_MILLIS) {
            fireActivate(activatingSlot);
        }
        super.tick();
    }

    /**
     * 库存回执轮询：服务端对 {@code card_use_request} 有 500ms 限流，请求可能被直接丢弃，
     * 因此这里带重试；超过次数后停止重试但给出明确提示，避免永远停在「同步中」。
     */
    private void pollInventory(long now) {
        if (!inventoryRequested) {
            return;
        }
        int version = LotteryNetwork.ClientLotteryState.cardInventoryVersion;
        if (version != inventoryEpoch) {
            // 必须把版本消费掉（inventoryEpoch = version），否则这个分支恒真，下面的
            // limitBreakPendingSince = -1 会在每 tick（50ms）把 fireActivate 刚写下的
            // 等待标记抹掉，LIMIT_BREAK_WAIT_MILLIS 变成死常量、PENDING 不可达，
            // 冷却到期后重复点击还能再发一个 bonus 包（双花）。
            inventoryEpoch = version;
            if (inventoryArrivedMillis < 0) {
                inventoryArrivedMillis = now;
            }
            // 服务端处理完一次操作（含突破上限卡）后会主动回一次 inventory，
            // 只有「新快照到达」这一个变化沿才解除等待态。
            limitBreakPendingSince = -1L;
            return;
        }
        if (!inventoryUnknown() || gaveUp) {
            // 快照已到、或已放弃重试：不再发请求，免得回包持续冲掉等待态。
            return;
        }
        if (now - lastRequestMillis < INVENTORY_RETRY_MILLIS) {
            return;
        }
        if (requestAttempts >= INVENTORY_MAX_ATTEMPTS) {
            gaveUp = true;
            return;
        }
        requestInventory(now);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return true;
        }
        if (activatingSlot != null) {
            // 确认动画期间冻结输入，避免重复发包（返回按钮也不响应，动画只有 190ms）。
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closedForGameStart) {
            return true;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return true;
        }
        switch (keyCode) {
            case 263 -> { // ←
                moveFocus(-1);
                return true;
            }
            case 262 -> { // →
                moveFocus(1);
                return true;
            }
            case 265 -> { // ↑
                moveFocus(-columns);
                return true;
            }
            case 264 -> { // ↓
                moveFocus(columns);
                return true;
            }
            case 257, 335, 32 -> { // Enter / 小键盘 Enter / 空格
                // 只认原生焦点：与焦点环（isFocused()）同源，鼠标点过之后不会用错卡。
                CardTile focused = focusedTile();
                if (focused != null) {
                    activate(focused);
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    private void moveFocus(int delta) {
        if (tiles.isEmpty() || delta == 0) {
            return;
        }
        int current = tiles.indexOf(focusedTile());
        int next = current < 0 ? (delta > 0 ? 0 : tiles.size() - 1) : current + delta;
        focusTile(tiles.get(Mth.clamp(next, 0, tiles.size() - 1)));
    }

    private void focusTile(CardTile target) {
        // 交给控件树统一处理：它会先清掉旧焦点的 setFocused(false)。
        setFocused(target);
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (closedForGameStart) {
            return;
        }
        nowMillis = System.currentTimeMillis();
        frameDelta = Mth.clamp(nowMillis - lastFrameMillis, 0.0F, 120.0F);
        lastFrameMillis = nowMillis;

        // 有卡片被指向时整屏轻微放大，形成「对焦」层次
        float targetFocus = 1.0F;
        if (!busy()) {
            for (CardTile tile : tiles) {
                if (tile.wantsAttention()) {
                    targetFocus = 1.014F;
                    break;
                }
            }
        }
        screenFocus = GuiFx.approach(screenFocus, targetFocus, frameDelta, 90.0F);

        var pose = graphics.pose();
        pose.pushPose();
        if (Math.abs(screenFocus - 1.0F) > 0.0005F) {
            pose.translate(width / 2.0F, height / 2.0F, 0.0F);
            pose.scale(screenFocus, screenFocus, 1.0F);
            pose.translate(-width / 2.0F, -height / 2.0F, 0.0F);
        }

        float enter = GuiFx.easeOutCubic(GuiFx.progress(nowMillis, baseMillis, PANEL_ENTER_MILLIS));
        CardUiStyle.drawBackdrop(graphics, nowMillis, width, height, panelX, panelY, panelW, panelH);
        // 外壳固定在最终位置（enter 传 1），入场动效交给内容淡入 + 卡片错峰滑入：
        // 若外壳自己滑入，表头 / 页脚 / 控件都不跟着动，入场那 260ms 里会明显错位。
        CardUiStyle.drawPanelShell(graphics, nowMillis, 1.0F, panelX, panelY, panelW, panelH);

        drawContent(graphics, mouseX, mouseY, partialTick, enter);

        pose.popPose();
    }

    /**
     * 表头 / 页脚 / 卡片 + 返回按钮一起绘制。入场阶段用着色器 alpha 让它们整体渐显，
     * {@code try/finally} 保证恢复着色器颜色，否则异常会把整屏其它 GUI 一起染成半透明。
     */
    private void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                             float enter) {
        if (enter < 0.999F) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            try {
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, enter);
                drawHeader(graphics);
                drawFooter(graphics);
                super.render(graphics, mouseX, mouseY, partialTick);
            } finally {
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
            return;
        }
        drawHeader(graphics);
        drawFooter(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 背景与面板完全自绘，这里刻意不调用 super，避免多铺一层原版暗色遮罩。 */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 见 render
    }

    private void drawHeader(GuiGraphics graphics) {
        GuiFx.roundedBar(graphics, contentX, titleY + 1, contentX + 3, titleY + 17, 1,
                CardUiStyle.GOLD);
        Component title = GuiFx.gradientText(
                Component.translatable("screen.habitrain_lottery.backpack.title").getString(),
                CardUiStyle.GOLD, CardUiStyle.CYAN);
        graphics.drawString(font, title, contentX + 10, titleY + 3, CardUiStyle.TEXT, false);

        Component subtitle;
        int subtitleColor = CardUiStyle.MUTED;
        if (loading()) {
            subtitle = Component.translatable("screen.habitrain_lottery.backpack.loading");
            subtitleColor = CardUiStyle.CYAN;
        } else if (gaveUp) {
            subtitle = Component.translatable("screen.habitrain_lottery.backpack.timeout");
            subtitleColor = CardUiStyle.DANGER;
        } else {
            subtitle = Component.translatable("screen.habitrain_lottery.backpack.subtitle",
                    totalCards());
        }
        // 右上角次数胶囊占位固定，窄窗口下副标题必须截断，否则会被胶囊整段盖住。
        int subtitleBudget = Math.max(24, chipX - 6 - (contentX + 10));
        graphics.drawString(font, Component.literal(
                        font.plainSubstrByWidth(subtitle.getString(), subtitleBudget)),
                contentX + 10, titleY + 17, subtitleColor, false);

        drawQuotaChips(graphics);
    }

    /** 右上角两枚次数胶囊：今日剩余的阵营卡 / 自选卡次数，各带一条 4 格进度条。 */
    private void drawQuotaChips(GuiGraphics graphics) {
        int chipH = 17;
        drawQuotaChip(graphics, 0, chipX, titleY, chipWidth, chipH, chipLabelFaction,
                remainingUses(Slot.KILLER), CardUiStyle.GOLD);
        drawQuotaChip(graphics, 1, chipX, titleY + chipH + 4, chipWidth, chipH, chipLabelSelf,
                remainingUses(Slot.SELF_SELECT), CardUiStyle.ACCENT_SELF_SELECT);
    }

    private void drawQuotaChip(GuiGraphics graphics, int side, int x, int y, int w, int h,
                               String label, int remaining, int color) {
        int segW = 5;
        int segGap = 2;
        int barW = BASE_DAILY_USES * segW + (BASE_DAILY_USES - 1) * segGap;
        boolean known = !inventoryUnknown();
        int value = known ? Math.min(remaining, 99) : 0;
        int accent = !known ? CardUiStyle.DIM : (value > 0 ? color : CardUiStyle.DANGER);

        GuiFx.roundGradient(graphics, x, y, x + w, y + h, 8,
                GuiFx.alpha(0xFF1B2434, 235), GuiFx.alpha(0xFF121821, 235));
        GuiFx.roundOutline(graphics, x, y, x + w, y + h, 8,
                GuiFx.fade(accent, known ? 0.55F : 0.3F));

        if (chipTextValue[side] != value || chipTextKnown[side] != known
                || chipText[side] == null) {
            chipTextValue[side] = value;
            chipTextKnown[side] = known;
            String valueText = known
                    ? Component.translatable("screen.habitrain_lottery.backpack.chip.remaining",
                            value).getString()
                    : "--";
            chipText[side] = label + " " + valueText;
        }
        graphics.drawString(font, Component.literal(chipText[side]), x + 7, y + 5,
                known ? CardUiStyle.TEXT : CardUiStyle.MUTED, false);

        int barX = x + w - 7 - barW;
        int barY = y + h / 2 - 2;
        int filled = Mth.clamp(value, 0, BASE_DAILY_USES);
        for (int i = 0; i < BASE_DAILY_USES; i++) {
            int sx = barX + i * (segW + segGap);
            GuiFx.roundedBar(graphics, sx, barY, sx + segW, barY + 4, 1,
                    i < filled ? accent : 0x33FFFFFF);
        }
        if (known && remaining > BASE_DAILY_USES) {
            graphics.drawString(font, Component.literal("+"), barX + barW + 1, barY - 2,
                    CardUiStyle.GOLD, false);
        }
    }

    private void drawFooter(GuiGraphics graphics) {
        int pad = compact ? 12 : 18;
        int hintY = navY - (compact ? 13 : 15);

        if (showHint) {
            GuiFx.gradient(graphics, contentX, hintY - 5, contentX + contentW, hintY - 4,
                    GuiFx.alpha(0xFF3C4A5E, 130), GuiFx.alpha(0xFF3C4A5E, 20));

            // 左提示 + 右快捷键同处一行，窄窗口（en_us 下两串合计约 644px > compact 的
            // 536px contentW）会左右对撞：各自按预算截断，宁可省略也不叠字。
            String keys = font.plainSubstrByWidth(
                    Component.translatable("screen.habitrain_lottery.backpack.keys").getString(),
                    Math.max(24, contentW / 2));
            int keysW = font.width(keys);
            String hint = font.plainSubstrByWidth(
                    Component.translatable("screen.habitrain_lottery.backpack.hint").getString(),
                    Math.max(24, contentW - keysW - 8));
            graphics.drawString(font, Component.literal(hint), contentX, hintY,
                    CardUiStyle.MUTED, false);
            graphics.drawString(font, Component.literal(keys),
                    panelX + panelW - pad - keysW, hintY, CardUiStyle.DIM, false);
        }

        if (activatingSlot != null) {
            float t = GuiFx.progress(nowMillis, activateStartMillis, ACTIVATE_MILLIS);
            Component message = Component.translatable(
                    "screen.habitrain_lottery.backpack.confirming",
                    activatingSlot.displayName());
            int color = GuiFx.mix(CardUiStyle.GOLD, 0xFFFFFFFF, GuiFx.pulse(t));
            GuiFx.centered(graphics, font, message, panelX + panelW / 2, navY - 11,
                    GuiFx.fade(color, 0.35F + 0.65F * t));
        }
    }

    // =====================================================================
    // 关闭
    // =====================================================================

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /** 开局时直接返回父页面，且不发送任何包。 */
    private void closeForGameStart() {
        if (closedForGameStart) {
            return;
        }
        closedForGameStart = true;
        activatingSlot = null;
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =====================================================================
    // 卡片控件
    // =====================================================================

    private final class CardTile extends AbstractWidget {
        private final Slot slot;
        private final long enterStartMillis;
        private final int enterOrder;

        private float hoverAnim;
        private float focusAnim;
        private float pressAnim;
        private float flashAnim;
        private boolean pressed;
        private String tooltipKey;
        /** 「×N」文本缓存，避免每帧重新查表拼接。 */
        private String countText;
        private int countTextCount = Integer.MIN_VALUE;
        private boolean countTextUnknown;

        private CardTile(Slot slot, int x, int y, int w, int h, int enterOrder) {
            super(x, y, w, h, slot.displayName());
            this.slot = slot;
            this.enterOrder = enterOrder;
            this.enterStartMillis = pageEnterMillis;
        }

        private float enterProgress() {
            float delay = enterOrder * CARD_STAGGER_MILLIS;
            float window = Math.max(CARD_ENTER_MILLIS, CARD_ENTER_WINDOW_MILLIS - delay);
            return GuiFx.easeOutCubic(GuiFx.progress(nowMillis, enterStartMillis, delay, window));
        }

        private boolean wantsAttention() {
            return active && isHovered && statusOf(slot) == Status.READY;
        }

        private void press() {
            pressed = true;
            flashAnim = 1.0F;
        }

        private void syncTooltip(Status status, int count) {
            String key = status.name() + '#' + count + '#' + remainingUses(slot);
            if (key.equals(tooltipKey)) {
                return;
            }
            tooltipKey = key;
            setTooltip(Tooltip.create(Component.empty()
                    .append(slot.displayName())
                    .append("\n")
                    .append(Component.translatable(slot.hintKey()))
                    .append("\n")
                    .append(Component.translatable(
                            "screen.habitrain_lottery.backpack.tip_click"))
                    .append("\n")
                    .append(Component.translatable(
                            "screen.habitrain_lottery.backpack.tip_state",
                            status.label(), count, remainingUses(slot)))));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Status status = statusOf(slot);
            int count = balance(slot);
            syncTooltip(status, count);

            // 让 active 与真实可用状态同步：否则不可用的卡仍会吃掉点击（播点击音并进 onClick），
            // 再被 activate() 的 statusOf != READY 静默丢弃，玩家只听到一声空响。
            active = status == Status.READY;
            boolean usable = active && status == Status.READY;
            boolean frozen = activatingSlot != null && activatingSlot != slot;
            float delta = frameDelta;

            hoverAnim = GuiFx.approach(hoverAnim, !frozen && usable && isHovered ? 1.0F : 0.0F,
                    delta, 65.0F);
            focusAnim = GuiFx.approach(focusAnim, isFocused() && !frozen ? 1.0F : 0.0F,
                    delta, 80.0F);
            pressAnim = GuiFx.approach(pressAnim, pressed && !frozen ? 1.0F : 0.0F, delta, 45.0F);
            flashAnim = GuiFx.approach(flashAnim, 0.0F, delta, 110.0F);
            if (!isHovered) {
                pressed = false;
            }

            float enter = enterProgress();
            if (enter <= 0.001F) {
                return;
            }
            float lift = usable ? Mth.clamp(Math.max(hoverAnim, focusAnim), 0.0F, 1.0F) : 0.0F;
            boolean confirming = activatingSlot == slot;
            float confirmT = confirming
                    ? GuiFx.progress(nowMillis, activateStartMillis, ACTIVATE_MILLIS) : 0.0F;
            float pop = confirming ? GuiFx.easeOutBack(GuiFx.clamp01(confirmT * 1.6F)) : 0.0F;

            float slide = Mth.clamp((1.0F - GuiFx.easeOutQuint(enter)) * 12.0F, -20.0F, 20.0F);
            float scale = 0.94F + 0.06F * enter + lift * 0.022F - pressAnim * 0.03F;
            int cx = getX() + width / 2;
            int cy = getY() + height / 2;

            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(cx, cy + slide - lift * 3.0F, 0.0F);
            pose.scale(scale, scale, 1.0F);
            pose.translate(-cx, -cy, 0.0F);

            drawFrame(graphics, status, lift, confirmT, pop);
            drawBody(graphics, status, lift, pop, confirming, confirmT);
            pose.popPose();
        }

        /** 卡面底板：圆角渐变 + 描边 + 悬停柔光 + 斜向扫光 + 焦点环。 */
        private void drawFrame(GuiGraphics graphics, Status status, float lift,
                               float confirmT, float pop) {
            int x0 = getX();
            int y0 = getY();
            int x1 = x0 + width;
            int y1 = y0 + height;
            int accent = slot.accent;
            boolean dim = status != Status.READY;

            int top = GuiFx.mix(GuiFx.mix(0xFF232C3C, 0xFF1A2230, lift), accent, lift * 0.20F);
            int bottom = GuiFx.mix(0xFF0E131C, accent, lift * 0.10F);
            if (dim) {
                top = GuiFx.shade(top, -0.22F);
                bottom = GuiFx.shade(bottom, -0.18F);
            }
            int border = activatingSlot == slot
                    ? GuiFx.mix(accent, 0xFFFFFFFF, 0.45F * GuiFx.pulse(confirmT))
                    : GuiFx.mix(GuiFx.mix(0xFF39465A, accent, 0.35F), CardUiStyle.GOLD,
                            lift * 0.5F);

            GuiFx.card(graphics, x0, y0, x1, y1, CardUiStyle.CARD_RADIUS, top, bottom, border,
                    accent, Math.max(lift, pop) * (dim ? 0.30F : 1.0F));

            if (lift > 0.05F) {
                float sweep = GuiFx.progress(nowMillis, enterStartMillis + 200L, 560.0F);
                GuiFx.beginClip(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1);
                GuiFx.sheen(graphics, x0, y0, x1, y1, -0.25F + sweep * 1.5F, lift * 0.5F);
                GuiFx.endClip(graphics);
            }
            if (flashAnim > 0.02F) {
                GuiFx.roundOutline(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1,
                        CardUiStyle.CARD_RADIUS + 1,
                        GuiFx.fade(GuiFx.alpha(0xFFFFFFFF, 220), flashAnim));
            }
            if (focusAnim > 0.03F) {
                GuiFx.roundOutline(graphics, x0 - 2, y0 - 2, x1 + 2, y1 + 2,
                        CardUiStyle.CARD_RADIUS + 2,
                        GuiFx.fade(CardUiStyle.GOLD, focusAnim * 0.75F));
            }
        }

        /**
         * 卡面内容。绘制顺序是刻意的：立绘 → 名称 → 状态遮罩 → 状态胶囊 / 数量角标，
         * 这样「不可用」的暗化遮罩只压暗立绘，胶囊与角标仍然清晰可读。
         */
        private void drawBody(GuiGraphics graphics, Status status, float lift, float pop,
                              boolean confirming, float confirmT) {
            int x0 = getX();
            int y0 = getY();
            int x1 = x0 + width;
            int y1 = y0 + height;
            boolean dim = status != Status.READY;
            boolean tiny = height < TINY_CARD_HEIGHT;
            float alpha = enterProgress();
            int artSize = artSize(tiny);
            int nameColor = dim
                    ? GuiFx.mix(CardUiStyle.MUTED, slot.accent, lift * 0.2F)
                    : GuiFx.mix(CardUiStyle.TEXT, slot.accent, lift * 0.25F);

            int pillY = -1;
            if (tiny) {
                // 极矮卡片：立绘在左，右侧两行（名称 ×N / 状态），任何尺寸下都不重叠
                int artX = x0 + 4;
                int artY = y0 + (height - artSize) / 2;
                drawArt(graphics, artX, artY, artSize, status, lift, pop);
                int textX = artX + artSize + 7;
                int textW = Math.max(20, x1 - textX - 5);
                int lineY = y0 + height / 2 - font.lineHeight;
                String title = slot.displayName().getString()
                        + " " + countText(status, balance(slot));
                graphics.drawString(font, Component.literal(
                                font.plainSubstrByWidth(title, textW)),
                        textX, lineY, nameColor, false);
                graphics.drawString(font, Component.literal(
                                font.plainSubstrByWidth(status.label().getString(), textW)),
                        textX, lineY + font.lineHeight, status.color, false);
            } else {
                // 六张卡统一版式：立绘居中在上，名称在下，状态胶囊贴底
                int artX = x0 + (width - artSize) / 2;
                int artY = y0 + Math.max(5, (height - artSize - font.lineHeight * 3) / 2);
                drawArt(graphics, artX, artY, artSize, status, lift, pop);
                int nameY = artY + artSize + 5;
                pillY = y1 - 17;
                int lines = Mth.clamp(Math.max(font.lineHeight, pillY - nameY - 2)
                        / font.lineHeight, 1, 2);
                drawName(graphics, x0 + 6, nameY, width - 12, nameColor, alpha, lines);
            }

            if (status == Status.SYNCING) {
                // 骨架态：一道扫过卡面的微光，明确「正在读数据」而不是「你没有卡」
                float sweep = (nowMillis % 1400.0F) / 1400.0F;
                GuiFx.beginClip(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1);
                GuiFx.sheen(graphics, x0, y0, x1, y1, -0.3F + sweep * 1.6F, 0.5F);
                GuiFx.endClip(graphics);
            } else if (dim && !tiny) {
                GuiFx.roundRect(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1,
                        CardUiStyle.CARD_RADIUS - 1, 0x6605070C);
            }

            if (tiny) {
                if (confirming) {
                    drawConfirmOverlay(graphics, confirmT);
                }
                return;
            }
            if (pillY > 0) {
                drawStatusPill(graphics, x0 + (width - pillWidth(status)) / 2, pillY,
                        status, dim, lift);
            }
            drawCountBadge(graphics, status);
            if (confirming) {
                drawConfirmOverlay(graphics, confirmT);
            }
        }

        private int artSize(boolean tiny) {
            // 极矮卡片把立绘压小；正常卡片在留出名称与胶囊所需行高后尽量放大（上限 90px）。
            int limit = tiny
                    ? Math.min(height - 10, 46)
                    : Math.min(width - 26, height - font.lineHeight * 3 - 18);
            int size = Mth.clamp(limit, 20, 90);
            return size - (size % 2);
        }

        /** 立绘：柔光 + 贴图；资源缺失时退化为程序化菱形徽记。 */
        private void drawArt(GuiGraphics graphics, int x, int y, int size, Status status,
                             float lift, float pop) {
            int accent = slot.accent;
            int cx = x + size / 2;
            int cy = y + size / 2;
            boolean dim = status != Status.READY;

            GuiFx.glow(graphics, cx, cy, size / 2 + 6, size / 2 + 6, accent,
                    (dim ? 0.10F : 0.22F) + lift * 0.30F + pop * 0.25F);

            ResourceLocation art = slot.art();
            if (art != null) {
                graphics.blit(art, x, y, size, size, 0.0F, 0.0F,
                        ART_TEXTURE_SIZE, ART_TEXTURE_SIZE, ART_TEXTURE_SIZE, ART_TEXTURE_SIZE);
            } else {
                int radius = Math.max(6, size / 2 - 4);
                GuiFx.diamond(graphics, cx, cy, radius, radius,
                        GuiFx.alpha(GuiFx.shade(accent, -0.55F), 235));
                GuiFx.diamond(graphics, cx, cy, Math.max(2, radius - 4), Math.max(2, radius - 4),
                        GuiFx.alpha(GuiFx.shade(accent, -0.3F), 150));
                GuiFx.diamondOutline(graphics, cx, cy, radius, radius,
                        GuiFx.mix(accent, 0xFFFFFFFF, 0.25F + lift * 0.35F));
                if (size >= 30) {
                    graphics.drawCenteredString(font, Component.literal(slot.glyph), cx, cy - 4,
                            0xFFFFFFFF);
                }
            }

            // 环绕轨道点：缓慢公转，暗示这里可以点
            if (size >= 44 && !dim) {
                double angle = ((nowMillis % 9000.0F) / 9000.0F) * Math.PI * 2.0D;
                int ox = cx + (int) (Math.cos(angle) * (size / 2.0 + 4));
                int oy = cy + (int) (Math.sin(angle) * (size / 2.0 + 3));
                graphics.fill(ox, oy, ox + 2, oy + 2, GuiFx.alpha(0xFFFFFFFF, 150));
            }
        }

        private void drawName(GuiGraphics graphics, int x, int y, int maxWidth, int color,
                              float alpha, int maxLines) {
            String typed = GuiFx.typewriter(
                    slot.displayName().getString(), alpha);
            List<FormattedCharSequence> lines = font.split(Component.literal(typed), maxWidth);
            int shown = Math.min(maxLines, lines.size());
            for (int i = 0; i < shown; i++) {
                FormattedCharSequence line = lines.get(i);
                graphics.drawString(font, line, x + (maxWidth - font.width(line)) / 2,
                        y + i * font.lineHeight, GuiFx.fade(color, alpha), false);
            }
        }

        private String countText(Status status, int count) {
            boolean unknown = status == Status.SYNCING || status == Status.UNKNOWN;
            if (countText == null || countTextCount != count || countTextUnknown != unknown) {
                countTextCount = count;
                countTextUnknown = unknown;
                countText = unknown
                        ? Component.translatable(
                                "screen.habitrain_lottery.backpack.count_unknown").getString()
                        : Component.translatable(
                                "screen.habitrain_lottery.backpack.count", count).getString();
            }
            return countText;
        }

        private int pillWidth(Status status) {
            return font.width(status.label()) + 12;
        }

        private void drawStatusPill(GuiGraphics graphics, int x, int y, Status status,
                                    boolean dim, float lift) {
            int w = pillWidth(status);
            int color = status.color;
            GuiFx.roundRect(graphics, x, y, x + w, y + 11, 3,
                    GuiFx.alpha(GuiFx.shade(color, -0.78F), status == Status.READY ? 215 : 190));
            GuiFx.roundOutline(graphics, x, y, x + w, y + 11, 3,
                    GuiFx.fade(color, dim ? 0.35F : 0.6F + lift * 0.3F));
            graphics.fill(x + 4, y + 4, x + 6, y + 7, color);
            GuiFx.centered(graphics, font, status.label(), x + w / 2 + 2, y + 2,
                    GuiFx.mix(color, 0xFFFFFFFF, dim ? 0.15F : 0.35F + lift * 0.25F));
        }

        /** 右上角数量角标：×N。 */
        private void drawCountBadge(GuiGraphics graphics, Status status) {
            int count = balance(slot);
            String text = countText(status, count);
            int w = font.width(text) + 12;
            int x = getX() + width - w - 5;
            int y = getY() + 5;
            boolean owned = count > 0 && status != Status.SYNCING && status != Status.UNKNOWN;
            int accent = owned ? slot.accent : CardUiStyle.DIM;

            GuiFx.roundGradient(graphics, x, y, x + w, y + 13, 6,
                    GuiFx.alpha(0xFF10151F, 240), GuiFx.alpha(0xFF0A0E15, 240));
            GuiFx.roundOutline(graphics, x, y, x + w, y + 13, 6, GuiFx.fade(accent, 0.6F));
            GuiFx.centered(graphics, font, Component.literal(text), x + w / 2, y + 3,
                    status == Status.SYNCING || status == Status.UNKNOWN ? CardUiStyle.DIM
                            : (owned ? GuiFx.mix(CardUiStyle.TEXT, accent, 0.45F)
                                    : CardUiStyle.MUTED));
        }

        /** 确认反馈：外圈脉冲 + 上升星点 + 右下角勾选。 */
        private void drawConfirmOverlay(GuiGraphics graphics, float confirmT) {
            int x0 = getX();
            int y0 = getY();
            int x1 = x0 + width;
            int y1 = y0 + height;
            int accent = slot.accent;
            float pulse = GuiFx.pulse(confirmT);

            GuiFx.glow(graphics, (x0 + x1) / 2, (y0 + y1) / 2, width / 2 + 4, height / 2 + 4,
                    accent, 0.7F * (0.5F + pulse * 0.5F));
            GuiFx.roundOutline(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1,
                    CardUiStyle.CARD_RADIUS + 1,
                    GuiFx.fade(GuiFx.alpha(0xFFFFFFFF, 210), 0.4F + pulse * 0.6F));

            for (int i = 0; i < 5; i++) {
                float t = GuiFx.clamp01(confirmT * 1.2F - i * 0.12F);
                if (t <= 0.0F || t >= 1.0F) {
                    continue;
                }
                int px = x0 + width / 2 + (i - 2) * Math.max(8, width / 7);
                int py = (int) (y1 - 6 - t * (height * 0.85F));
                graphics.fill(px, py, px + 2, py + 2, GuiFx.fade(accent, 1.0F - t));
            }

            int checkSize = 14;
            int checkX = x1 - checkSize - 5;
            int checkY = y1 - checkSize - 5;
            GuiFx.roundRect(graphics, checkX, checkY, checkX + checkSize, checkY + checkSize, 7,
                    GuiFx.alpha(0xFF0B0F18, (int) (220 * confirmT)));
            GuiFx.roundOutline(graphics, checkX, checkY, checkX + checkSize, checkY + checkSize, 7,
                    GuiFx.fade(GuiFx.alpha(CardUiStyle.OK, 255), confirmT));
            if (confirmT > 0.35F) {
                graphics.drawCenteredString(font, Component.literal("\u2714"),
                        checkX + checkSize / 2, checkY + 3,
                        GuiFx.fade(CardUiStyle.OK, (confirmT - 0.35F) / 0.65F));
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // 不可用 / 非左键直接放行：不播点击音，也不进入确认动画。
            if (button != 0 || !active || busy() || statusOf(slot) != Status.READY) {
                return false;
            }
            pressed = true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            pressed = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            activate(this);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (active && isFocused() && (keyCode == 257 || keyCode == 335 || keyCode == 32)) {
                activate(this);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, slot.displayName());
            output.add(NarratedElementType.HINT, Component.translatable(
                    "screen.habitrain_lottery.backpack.card_narration",
                    statusOf(slot).label(), balance(slot)));
        }
    }
}
