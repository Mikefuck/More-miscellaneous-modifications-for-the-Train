package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.LotteryNetwork;

import io.wifi.starrailexpress.client.util.PinYinUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.agmas.noellesroles.utils.RoleUtils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 自选角色页（自选卡）——现代卡片式重制版。
 *
 * <p>功能与原版完全一致：顶部搜索、职业卡网格、翻页、点击卡牌即确认消耗一张自选卡。
 * 视觉与交互在本版重做：圆角面板、分层渐变、柔光与斜向高光、卡片入场/悬停/确认三段
 * 动画、搜索框聚焦辉光、带方向感的翻页过渡、空状态与页脚提示。动画全部基于真实时间
 * （毫秒）并做帧率无关处理，因此在不同帧率下表现一致。</p>
 *
 * <h2>刻意保持不变的约束</h2>
 * <ul>
 *   <li>确认包只发送一次：{@link #selectionSubmitted} 与 {@link #confirmingCard} 双重保护。</li>
 *   <li>一旦离开大厅（{@link CardGuiGameState}），立即关闭且不发送任何确认包；即使在确认
 *       动画播放途中也会中止。</li>
 *   <li>搜索框只创建一次，过滤时只重建卡牌与翻页按钮，避免 {@link EditBox#setValue}
 *       递归刷新。</li>
 *   <li>界面不暂停游戏（{@link #isPauseScreen()} 为 {@code false}）。</li>
 * </ul>
 */
public class RoleSelectScreen extends Screen {

    // ---------------------------------------------------------------------
    // 配色与尺寸
    //
    // 全部取自 {@link CardUiStyle}：角色卡背包页与自选卡页共用同一份色板，两处不能各自
    // 漂移。这里保留同名别名只是为了让本文件里的调用点保持简短。
    // ---------------------------------------------------------------------
    private static final int BG_TOP = CardUiStyle.BG_TOP;
    private static final int BG_BOTTOM = CardUiStyle.BG_BOTTOM;
    private static final int PANEL_TOP = CardUiStyle.PANEL_TOP;
    private static final int PANEL_BOTTOM = CardUiStyle.PANEL_BOTTOM;
    private static final int PANEL_BORDER = CardUiStyle.PANEL_BORDER;
    private static final int GOLD = CardUiStyle.GOLD;
    private static final int GOLD_DEEP = CardUiStyle.GOLD_DEEP;
    private static final int CYAN = CardUiStyle.CYAN;
    private static final int TEXT = CardUiStyle.TEXT;
    private static final int MUTED = CardUiStyle.MUTED;
    private static final int DIM = CardUiStyle.DIM;
    private static final int DANGER = CardUiStyle.DANGER;
    private static final int OK = CardUiStyle.OK;

    private static final int CARD_GAP = 8;
    private static final int MAX_COLUMNS = 5;
    private static final int CARD_RADIUS = CardUiStyle.CARD_RADIUS;
    private static final int PANEL_RADIUS = CardUiStyle.PANEL_RADIUS;

    private static final float CARD_STAGGER_MILLIS = 34.0F;
    private static final float CARD_ENTER_WINDOW_MILLIS = 470.0F;
    private static final float CARD_ENTER_MILLIS = 240.0F;
    /** 点击后确认动画的播放时长（毫秒）；播完才真正发包。 */
    private static final float CONFIRM_MILLIS = 200.0F;
    private static final float PANEL_ENTER_MILLIS = 260.0F;

    private static final Gson GSON = new Gson();

    // ---------------------------------------------------------------------
    // 阵营
    // ---------------------------------------------------------------------

    /** 阵营：决定配色、徽记与筛选标签。 */
    private enum Faction {
        INNOCENT("display.type.role.innocent", 0xFF44BB66, "\u271A"),
        KILLER("display.type.role.killer", 0xFFE2555F, "\u2716"),
        NEUTRAL("display.type.role.neutral", 0xFFD9B23C, "\u25C6"),
        NEUTRAL_FOR_KILLER("display.type.role.neutral_for_killer", 0xFFB07CE8, "\u2605"),
        VIGILANTE("display.type.role.vigilante", 0xFF35C6D8, "\u2714");

        private final String langKey;
        private final int color;
        private final String glyph;

        Faction(String langKey, int color, String glyph) {
            this.langKey = langKey;
            this.color = color;
            this.glyph = glyph;
        }

        private Component label() {
            // 阵营标签每帧都会用到，缓存翻译结果避免重复查表。
            return Labels.faction(this);
        }
    }

    /** 延迟初始化的翻译缓存（Language 在客户端启动后才可用，因此不能放静态常量）。 */
    private static final class Labels {
        private static final Map<Faction, Component> FACTION = new EnumMap<>(Faction.class);

        private static Component faction(Faction faction) {
            Component cached = FACTION.get(faction);
            if (cached == null) {
                cached = Component.translatable(faction.langKey);
                FACTION.put(faction, cached);
            }
            return cached;
        }
    }

    /**
     * 一个职业在界面上的全部派生视觉信息。
     *
     * @param icon 立绘贴图；为 {@code null} 时使用程序化徽记
     */
    private record RoleVisual(int color, Faction faction, ResourceLocation icon) {
    }

    // ---------------------------------------------------------------------
    // 候选数据
    // ---------------------------------------------------------------------

    public static final class Candidate {
        public String id;
        public String name;
        public int color;
        public String bound;

        private transient String cachedDisplayName;
        private transient String cachedBoundDisplayName;
        private transient RoleVisual cachedVisual;

        public String displayName() {
            if (cachedDisplayName == null) {
                cachedDisplayName = resolveRoleDisplayName(id, name);
            }
            return cachedDisplayName;
        }

        public String boundDisplayName() {
            if (!isBound()) {
                return "";
            }
            if (cachedBoundDisplayName == null) {
                cachedBoundDisplayName = resolveBoundRoleDisplayName(bound);
            }
            return cachedBoundDisplayName;
        }

        public boolean isBound() {
            return bound != null && !bound.isBlank();
        }

        private RoleVisual visual() {
            if (cachedVisual == null) {
                cachedVisual = resolveVisual(id, color);
            }
            return cachedVisual;
        }
    }

    // ---------------------------------------------------------------------
    // 状态
    // ---------------------------------------------------------------------

    private final Screen parent;
    private final List<Candidate> allCandidates = new ArrayList<>();
    private final List<Candidate> filtered = new ArrayList<>();
    private final List<RoleCardWidget> roleCards = new ArrayList<>();
    /** 按职业 id 记忆入场起始时间，避免过滤重建时动画重播。 */
    private final Map<String, Long> cardEnterTimes = new HashMap<>();

    private EditBox searchBox;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button backButton;

    private String searchQuery = "";
    private boolean syncingSearchBox;
    private boolean closedForGameStart;
    private boolean selectionSubmitted;
    /** 正在播放确认动画的卡片；非 null 时冻结所有输入。 */
    private RoleCardWidget confirmingCard;
    private Candidate confirmedCandidate;
    private long confirmStartMillis;

    private int page;
    private int pageCount = 1;
    private int pageFirstIndex;
    private int pageLastIndexExclusive;

    private int columns = 1;
    private int rowsPerPage = 1;
    private int cardWidth;
    private int cardHeight;
    /** 本批卡片入场的稳定序号，避免每帧 indexOf 扫描。 */
    private int enterCursor;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int searchX;
    private int searchY;
    private int searchH;
    private int gridX;
    private int gridTop;
    private int gridBottom;
    private int footerTop;
    private boolean compact;

    /** 当前由方向键选中的卡片，用于焦点环与移动逻辑。 */
    private RoleCardWidget focusedCard;

    /** 有卡片被指向时的整体「对焦」缩放（1.0 = 原始大小）。 */
    private float screenFocus = 1.0F;

    /** 翻页方向（-1 上一页 / +1 下一页），用于让整屏卡片从翻页方向滑入。 */
    private float pageDirection = 1.0F;

    // 动画时钟
    private long baseMillis;
    private long nowMillis;
    private long lastFrameMillis;
    private long pageEnterMillis;
    private long filterMillis;
    /**
     * 上一帧到这一帧的毫秒数。
     *
     * <p>必须在这里统一算一次再交给控件：{@link #render} 会先把 {@code lastFrameMillis}
     * 推进到当前时间，控件里再算 {@code nowMillis - lastFrameMillis} 就恒为 0，
     * {@link GuiFx#approach} 的插值系数因此永远为 0 —— 悬停/焦点/按压动画会全部静止
     * （这是本页此前的实际表现）。</p>
     */
    private float frameDelta = 16.0F;

    public RoleSelectScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_lottery.role_select.title"));
        this.parent = parent;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        long now = System.currentTimeMillis();
        baseMillis = now;
        pageEnterMillis = now;
        filterMillis = now;
        lastFrameMillis = now;
        nowMillis = now;
        cardEnterTimes.clear();

        parseCandidates();
        computeLayout();
        createSearchBox();
        rebuildPage(true);

        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
        }
    }

    private void parseCandidates() {
        allCandidates.clear();
        String json = LotteryNetwork.ClientLotteryState.cardUseCandidatesJson;
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Type type = new TypeToken<List<Candidate>>() {
            }.getType();
            List<Candidate> parsed = GSON.fromJson(json, type);
            if (parsed != null) {
                for (Candidate candidate : parsed) {
                    if (candidate != null && candidate.id != null && !candidate.id.isBlank()) {
                        allCandidates.add(candidate);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 服务端快照异常时保持空列表，不能让 GUI 初始化崩溃。
        }
    }

    // =====================================================================
    // 布局
    // =====================================================================

    private void computeLayout() {
        compact = height < 400 || width < 620;

        int maxPanelW = compact ? 560 : 820;
        int maxPanelH = compact ? 330 : 500;

        panelW = Math.max(300, Math.min(maxPanelW, width - 16));
        panelH = Math.max(190, Math.min(maxPanelH, height - 16));
        panelW = Math.min(panelW, width);
        panelH = Math.min(panelH, height);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        int pad = compact ? 12 : 18;
        contentX = panelX + pad;
        contentW = Math.max(80, panelW - pad * 2);

        int titleRowH = compact ? 24 : 30;
        searchX = contentX;
        searchY = panelY + pad + titleRowH;
        searchH = compact ? 18 : 20;

        gridX = contentX;
        gridTop = searchY + searchH + (compact ? 20 : 24);
        footerTop = panelY + panelH - (compact ? 46 : 54);
        gridBottom = footerTop - 8;

        int usableW = Math.max(60, contentW);
        int preferredCardWidth = compact ? 90 : 118;
        columns = Mth.clamp((usableW + CARD_GAP) / (preferredCardWidth + CARD_GAP), 1, MAX_COLUMNS);
        cardWidth = Math.max(64, (usableW - (columns - 1) * CARD_GAP) / columns);

        int areaHeight = Math.max(56, gridBottom - gridTop);
        int preferredCardHeight = compact ? 84 : 118;
        int maxRows = compact ? 2 : 3;
        rowsPerPage = Mth.clamp((areaHeight + CARD_GAP) / (preferredCardHeight + CARD_GAP), 1, maxRows);
        cardHeight = Mth.clamp((areaHeight - (rowsPerPage - 1) * CARD_GAP) / rowsPerPage,
                60, preferredCardHeight);
    }

    private void createSearchBox() {
        searchBox = new EditBox(font, searchX, searchY, contentW, searchH,
                Component.translatable("screen.habitrain_lottery.role_select.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("screen.habitrain_lottery.role_select.search_hint"));
        searchBox.setEditable(true);
        searchBox.setTextColor(TEXT);
        // 自绘外框已经处理了边框与聚焦辉光，避免与原版边框重叠。
        searchBox.setBordered(false);
        // 注册 responder 期间先屏蔽回调：部分版本会在注册时立即触发一次 responder，
        // 那时 layout 尚未完成，重建页面会用错的分页尺寸（原版注释提到的递归刷新来源）。
        syncingSearchBox = true;
        searchBox.setResponder(this::onSearchChanged);
        syncingSearchBox = false;
        addRenderableWidget(searchBox);

        if (!searchQuery.isEmpty()) {
            syncingSearchBox = true;
            searchBox.setValue(searchQuery);
            syncingSearchBox = false;
        }
    }

    private void onSearchChanged(String text) {
        if (syncingSearchBox || closedForGameStart) {
            return;
        }
        String next = text == null ? "" : text;
        if (Objects.equals(searchQuery, next)) {
            return;
        }
        searchQuery = next;
        filterMillis = System.currentTimeMillis();
        page = 0;
        rebuildPage(false);
    }

    private void applyFilter() {
        filtered.clear();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            filtered.addAll(allCandidates);
        } else {
            for (Candidate candidate : allCandidates) {
                if (matches(candidate, query)) {
                    filtered.add(candidate);
                }
            }
        }

        int perPage = Math.max(1, columns * rowsPerPage);
        pageCount = Math.max(1, (int) Math.ceil(filtered.size() / (double) perPage));
        page = Mth.clamp(page, 0, pageCount - 1);
    }

    private boolean matches(Candidate candidate, String query) {
        String name = candidate.displayName().toLowerCase(Locale.ROOT);
        String id = candidate.id == null ? "" : candidate.id.toLowerCase(Locale.ROOT);
        String bound = candidate.bound == null ? "" : candidate.bound.toLowerCase(Locale.ROOT);
        String boundName = candidate.isBound()
                ? candidate.boundDisplayName().toLowerCase(Locale.ROOT)
                : "";
        if (name.contains(query) || id.contains(query) || bound.contains(query) || boundName.contains(query)) {
            return true;
        }
        try {
            return PinYinUtils.contains(query, candidate.displayName())
                    || PinYinUtils.contains(query, candidate.id == null ? "" : candidate.id)
                    || (candidate.isBound() && PinYinUtils.contains(query, boundName));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =====================================================================
    // 分页与卡片构建
    // =====================================================================

    private void rebuildPage(boolean resetEnterAnimation) {
        removePageWidgets();
        applyFilter();

        long now = System.currentTimeMillis();
        if (resetEnterAnimation) {
            pageEnterMillis = now;
            cardEnterTimes.clear();
        }

        int perPage = Math.max(1, columns * rowsPerPage);
        int start = page * perPage;
        int end = Math.min(start + perPage, filtered.size());
        pageFirstIndex = start;
        pageLastIndexExclusive = end;
        enterCursor = 0;

        for (int i = start; i < end; i++) {
            int indexOnPage = i - start;
            int column = indexOnPage % columns;
            int row = indexOnPage / columns;
            int x = gridX + column * (cardWidth + CARD_GAP);
            int y = gridTop + row * (cardHeight + CARD_GAP);
            RoleCardWidget card = new RoleCardWidget(x, y, cardWidth, cardHeight, filtered.get(i));
            roleCards.add(card);
            addRenderableWidget(card);
        }

        int navY = footerTop + (compact ? 20 : 24);
        int navH = compact ? 18 : 20;

        Component prevLabel = Component.translatable("screen.habitrain_lottery.role_select.prev");
        previousPageButton = Button.builder(prevLabel, button -> triggerPage(-1))
                .bounds(contentX, navY, 74, navH)
                .tooltip(Tooltip.create(prevLabel))
                .build();
        previousPageButton.active = page > 0;
        addRenderableWidget(previousPageButton);

        Component nextLabel = Component.translatable("screen.habitrain_lottery.role_select.next");
        nextPageButton = Button.builder(nextLabel, button -> triggerPage(1))
                .bounds(contentX + contentW - 74, navY, 74, navH)
                .tooltip(Tooltip.create(nextLabel))
                .build();
        nextPageButton.active = page + 1 < pageCount;
        addRenderableWidget(nextPageButton);

        Component backLabel = Component.translatable("screen.habitrain_lottery.role_select.back");
        int backW = 74;
        backButton = Button.builder(backLabel, button -> onClose())
                .bounds(contentX + (contentW - backW) / 2, navY, backW, navH)
                .tooltip(Tooltip.create(backLabel))
                .build();
        addRenderableWidget(backButton);

        screenFocus = 1.0F;
    }

    private void removePageWidgets() {
        for (RoleCardWidget card : roleCards) {
            removeWidget(card);
        }
        roleCards.clear();
        confirmingCard = null;
        focusedCard = null;
        if (previousPageButton != null) {
            removeWidget(previousPageButton);
            previousPageButton = null;
        }
        if (nextPageButton != null) {
            removeWidget(nextPageButton);
            nextPageButton = null;
        }
        if (backButton != null) {
            removeWidget(backButton);
            backButton = null;
        }
    }

    // =====================================================================
    // 选择流程
    // =====================================================================

    private String questKey() {
        String key = LotteryNetwork.ClientLotteryState.cardUseMenuQuestKey;
        return key == null ? "" : key;
    }

    /** 现在是否还允许提交；顺带处理「已开局」时的自动返回。 */
    private boolean canSubmit() {
        if (closedForGameStart || selectionSubmitted) {
            return false;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return false;
        }
        return !questKey().isBlank();
    }

    /**
     * 点击卡片：先播放一小段确认动画，动画结束后才真正发送确认包。
     * 这样点击有明确反馈，而结算（扣卡 / 扣次数）仍然只发生一次。
     */
    private void confirm(RoleCardWidget card) {
        if (card == null || confirmingCard != null || selectionSubmitted) {
            return;
        }
        Candidate candidate = card.candidate;
        if (candidate == null || candidate.id == null || candidate.id.isBlank()) {
            return;
        }
        if (!canSubmit()) {
            return;
        }
        confirmingCard = card;
        confirmedCandidate = candidate;
        confirmStartMillis = System.currentTimeMillis();
    }

    /** 流程终点：发包 + 回到父页面，只会执行一次。 */
    private void submitConfirmed() {
        if (selectionSubmitted || closedForGameStart) {
            return;
        }
        selectionSubmitted = true;
        String roleId = confirmedCandidate == null ? "" : confirmedCandidate.id;
        try {
            LotteryClientNetwork.clientCardUseConfirm(questKey(), "self", roleId);
        } catch (Throwable ignored) {
            // 网络层失败也不能把玩家卡在已完成的选择页：下面统一返回。
        }
        // 选择已经提交后立即返回职业卡父页面；服务端负责最终校验。
        if (parent instanceof CardUseMenuScreen menu) {
            menu.closeAfterSelection();
        } else {
            closeToParent();
        }
    }

    @Override
    public void tick() {
        if (CardGuiGameState.gameActiveOrStarting() && !closedForGameStart) {
            closeForGameStart();
            return;
        }
        if (confirmingCard != null && !closedForGameStart) {
            long now = System.currentTimeMillis();
            if (now - confirmStartMillis >= CONFIRM_MILLIS) {
                submitConfirmed();
                return;
            }
        }
        super.tick();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return true;
        }
        if (confirmingCard != null) {
            // 确认动画期间冻结一切输入，避免重复提交。
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (confirmingCard == null && scrollY != 0.0D) {
            triggerPage(scrollY < 0.0D ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void triggerPage(int delta) {
        if (confirmingCard != null || delta == 0) {
            return;
        }
        if (delta > 0 && page + 1 < pageCount) {
            page++;
            pageDirection = 1.0F;
            pageEnterMillis = System.currentTimeMillis();
            rebuildPage(false);
        } else if (delta < 0 && page > 0) {
            page--;
            pageDirection = -1.0F;
            pageEnterMillis = System.currentTimeMillis();
            rebuildPage(false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closedForGameStart || confirmingCard != null) {
            return true;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return true;
        }
        switch (keyCode) {
            case 263 -> { // ← / →
                moveFocus(-1);
                return true;
            }
            case 262 -> {
                moveFocus(1);
                return true;
            }
            case 265 -> { // ↑ / ↓
                moveFocus(-columns);
                return true;
            }
            case 264 -> {
                moveFocus(columns);
                return true;
            }
            case 266 -> { // PageUp / PageDown
                triggerPage(-1);
                return true;
            }
            case 267 -> {
                triggerPage(1);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    private void moveFocus(int delta) {
        if (roleCards.isEmpty() || delta == 0) {
            return;
        }
        int current = roleCards.indexOf(focusedCard);
        int next = current < 0 ? (delta > 0 ? 0 : roleCards.size() - 1) : current + delta;
        if (next < 0) {
            if (page > 0) {
                triggerPage(-1);
                focusCard(roleCards.isEmpty() ? null : roleCards.get(roleCards.size() - 1));
            } else {
                focusCard(roleCards.get(0));
            }
            return;
        }
        if (next >= roleCards.size()) {
            if (page + 1 < pageCount) {
                triggerPage(1);
                focusCard(roleCards.isEmpty() ? null : roleCards.get(0));
            } else {
                focusCard(roleCards.get(roleCards.size() - 1));
            }
            return;
        }
        focusCard(roleCards.get(next));
    }

    /** 移动键盘焦点；{@code target} 为 null 表示清除焦点（交给搜索框等其它控件）。 */
    private void focusCard(RoleCardWidget target) {
        if (focusedCard != null && focusedCard != target) {
            focusedCard.setFocused(false);
        }
        focusedCard = target;
        if (target != null) {
            target.setFocused(true);
        }
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
        float delta = Mth.clamp(nowMillis - lastFrameMillis, 0.0F, 120.0F);
        lastFrameMillis = nowMillis;
        frameDelta = delta;

        // 有卡片被指向时，整屏轻微放大，形成「对焦」层次
        float targetFocus = 1.0F;
        for (RoleCardWidget card : roleCards) {
            if (card.wantsAttention()) {
                targetFocus = 1.014F;
                break;
            }
        }
        screenFocus = GuiFx.approach(screenFocus, targetFocus, delta, 90.0F);

        var pose = graphics.pose();
        pose.pushPose();
        if (Math.abs(screenFocus - 1.0F) > 0.0005F) {
            pose.translate(width / 2.0F, height / 2.0F, 0.0F);
            pose.scale(screenFocus, screenFocus, 1.0F);
            pose.translate(-width / 2.0F, -height / 2.0F, 0.0F);
        }

        drawBackground(graphics);
        drawPanel(graphics);

        // 外壳固定在最终位置（见 drawPanel），入场动效交给「内容整体淡入 + 卡片错峰滑入」；
        // 若只有外壳自己滑入，表头 / 页脚 / 控件都不跟着动，入场那 260ms 里会明显错位。
        float panelEnter = GuiFx.easeOutCubic(GuiFx.progress(nowMillis, baseMillis, PANEL_ENTER_MILLIS));
        drawContent(graphics, mouseX, mouseY, partialTick, panelEnter);

        pose.popPose();
    }

    /**
     * 表头 / 列表头 / 页脚 / 空状态 + 卡片与翻页按钮一起绘制。入场阶段用着色器 alpha 让它们
     * 整体渐显，{@code try/finally} 保证恢复着色器颜色，否则异常会把整屏其它 GUI 一起染成半透明。
     */
    private void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                             float panelEnter) {
        if (panelEnter < 0.999F) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            try {
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, panelEnter);
                drawHeader(graphics);
                drawListHeader(graphics);
                drawFooter(graphics);
                drawEmptyState(graphics);
                super.render(graphics, mouseX, mouseY, partialTick);
            } finally {
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
            return;
        }
        drawHeader(graphics);
        drawListHeader(graphics);
        drawFooter(graphics);
        drawEmptyState(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBackground(GuiGraphics graphics) {
        CardUiStyle.drawBackdrop(graphics, nowMillis, width, height, panelX, panelY, panelW, panelH);
    }

    private void drawPanel(GuiGraphics graphics) {
        // enter 传 1：外壳保持在最终位置，位移入场由内容负责（见 drawContent 注释）。
        CardUiStyle.drawPanelShell(graphics, nowMillis, 1.0F, panelX, panelY, panelW, panelH);
    }

    private void drawHeader(GuiGraphics graphics) {
        int pad = compact ? 12 : 18;
        int titleY = panelY + pad + (compact ? 0 : 2);
        float subtitleEnter = GuiFx.easeOutCubic(GuiFx.progress(nowMillis, baseMillis, 200.0F, 260.0F));

        // 标题：装饰竖条 + 渐变字
        GuiFx.roundedBar(graphics, contentX, titleY + 1, contentX + 3, titleY + 17, 1, GOLD);
        Component title = GuiFx.gradientText(
                Component.translatable("screen.habitrain_lottery.role_select.title").getString(), GOLD, CYAN);
        graphics.drawString(font, title, contentX + 10, titleY + 3, TEXT, false);

        if (!compact) {
            Component subtitle = Component.translatable("screen.habitrain_lottery.role_select.subtitle");
            graphics.drawString(font, subtitle, contentX + 10, titleY + 17,
                    GuiFx.fade(MUTED, subtitleEnter), false);
        }

        // 右上：持有数量 + 今日剩余次数
        int owned = LotteryNetwork.ClientLotteryState.cardBalances.getOrDefault("self_select", 0);
        int remaining = Math.max(0, LotteryNetwork.ClientLotteryState.cardUseRemainingSelfUses);
        String stats = Component.translatable("screen.habitrain_lottery.role_select.stats", owned, remaining)
                .getString();
        int statsW = font.width(stats) + 22;
        int statsX = panelX + panelW - pad - statsW;
        int statsY = titleY + 1;
        boolean usable = remaining > 0;
        int statsColor = usable ? GOLD : DANGER;
        GuiFx.roundGradient(graphics, statsX, statsY, statsX + statsW, statsY + 17, 8,
                GuiFx.alpha(0xFF1B2434, 235), GuiFx.alpha(0xFF121821, 235));
        GuiFx.roundOutline(graphics, statsX, statsY, statsX + statsW, statsY + 17, 8,
                GuiFx.fade(statsColor, usable ? 0.55F : 0.35F));
        // 底部进度条：可视化「今日剩余 / 基础 4 次」
        int barW = Math.max(2, Mth.clamp(remaining, 0, 4) * (statsW - 12) / 4);
        GuiFx.roundedBar(graphics, statsX + 6, statsY + 14, statsX + 6 + barW, statsY + 16, 1, statsColor);
        graphics.drawString(font, stats, statsX + 11, statsY + 4, usable ? TEXT : MUTED, false);

        // 搜索框外框：聚焦时金色呼吸辉光
        boolean focused = searchBox != null && searchBox.isFocused();
        float focusAnim = focused ? 0.55F + 0.45F * GuiFx.triWave(nowMillis, 1600.0F, 0.0F) : 0.0F;
        int fieldX = searchX - 1;
        int fieldY = searchY - 1;
        int fieldW = contentW + 2;
        int fieldH = searchH + 2;
        if (focusAnim > 0.02F) {
            GuiFx.glow(graphics, fieldX + fieldW / 2, fieldY + fieldH / 2, fieldW / 2 + 4, fieldH / 2 + 4,
                    GOLD, 0.45F * focusAnim);
        }
        GuiFx.roundGradient(graphics, fieldX, fieldY, fieldX + fieldW, fieldY + fieldH, 8,
                GuiFx.alpha(0xFF141C28, 240), GuiFx.alpha(0xFF0D131B, 240));
        GuiFx.roundOutline(graphics, fieldX, fieldY, fieldX + fieldW, fieldY + fieldH, 8,
                GuiFx.mix(0xFF3C4A5E, GOLD, focusAnim));
        graphics.drawString(font, Component.literal("\u2315"), searchX + 3,
                searchY + (searchH - 8) / 2, focused ? GOLD : MUTED, false);
    }

    private void drawListHeader(GuiGraphics graphics) {
        int y = gridTop - (compact ? 15 : 18);
        GuiFx.roundedBar(graphics, contentX, y + 1, contentX + 2, y + 10, 1, GuiFx.alpha(CYAN, 190));
        graphics.drawString(font, Component.translatable("screen.habitrain_lottery.role_select.list"),
                contentX + 8, y, TEXT, false);

        String pageText;
        if (filtered.isEmpty()) {
            pageText = Component.translatable("screen.habitrain_lottery.role_select.none").getString();
        } else {
            pageText = Component.translatable("screen.habitrain_lottery.role_select.page",
                    pageFirstIndex + 1, pageLastIndexExclusive, filtered.size(),
                    page + 1, pageCount).getString();
        }
        int textW = font.width(pageText);
        int textX = panelX + panelW - (compact ? 12 : 18) - textW;
        graphics.drawString(font, Component.literal(pageText), textX, y, MUTED, false);

        // 分页进度细条
        int barX0 = textX;
        int barX1 = textX + textW;
        int barY = y + 11;
        GuiFx.roundedBar(graphics, barX0, barY, barX1, barY + 2, 1, 0x33FFFFFF);
        if (pageCount > 1) {
            int segW = Math.max(3, (barX1 - barX0) / pageCount - 1);
            for (int i = 0; i < pageCount; i++) {
                int sx = barX0 + i * (segW + 1);
                if (sx + segW > barX1) {
                    break;
                }
                GuiFx.roundedBar(graphics, sx, barY, sx + segW, barY + 2, 1,
                        i == page ? GOLD : 0x44FFFFFF);
            }
        }
    }

    private void drawFooter(GuiGraphics graphics) {
        int pad = compact ? 12 : 18;
        int navY = footerTop + (compact ? 20 : 24);

        GuiFx.gradient(graphics, contentX, footerTop + 1, contentX + contentW, footerTop + 2,
                GuiFx.alpha(0xFF3C4A5E, 130), GuiFx.alpha(0xFF3C4A5E, 20));

        int hintY = footerTop + (compact ? 7 : 9);
        graphics.drawString(font, Component.translatable("screen.habitrain_lottery.role_select.hint"),
                contentX, hintY, MUTED, false);
        String keys = Component.translatable("screen.habitrain_lottery.role_select.keys").getString();
        graphics.drawString(font, Component.literal(keys),
                panelX + panelW - pad - font.width(keys), hintY, DIM, false);

        // 确认中：在翻页行上方给出明确反馈
        if (confirmingCard != null && confirmedCandidate != null) {
            float t = GuiFx.progress(nowMillis, confirmStartMillis, CONFIRM_MILLIS);
            Component message = Component.translatable("screen.habitrain_lottery.role_select.confirming",
                    confirmedCandidate.displayName());
            int color = GuiFx.mix(GOLD, 0xFFFFFFFF, GuiFx.pulse(t));
            GuiFx.centered(graphics, font, message, panelX + panelW / 2, navY - 13,
                    GuiFx.fade(color, 0.35F + 0.65F * t));
        } else if (pageCount > 1) {
            GuiFx.centered(graphics, font, Component.literal("\u2039  \u00B7  \u00B7  \u00B7  \u203A"),
                    panelX + panelW / 2, navY + 6, GuiFx.alpha(MUTED, 70));
        }
    }

    private void drawEmptyState(GuiGraphics graphics) {
        if (!filtered.isEmpty()) {
            return;
        }
        float enter = GuiFx.easeOutCubic(GuiFx.progress(nowMillis, filterMillis, 260.0F));
        float bob = GuiFx.triWave(nowMillis, 2600.0F, 0.0F);
        int centerX = panelX + panelW / 2;
        int centerY = gridTop + Math.max(28, (gridBottom - gridTop) / 2) - (int) (bob * 3.0F);

        boolean searching = searchQuery != null && !searchQuery.isBlank();
        int color = searching ? GOLD : MUTED;
        GuiFx.glow(graphics, centerX, centerY - 8, 60, 34, color, 0.18F * enter);
        GuiFx.diamond(graphics, centerX, centerY - 8, 16, 16, GuiFx.fade(GuiFx.alpha(color, 60), enter));
        GuiFx.diamondOutline(graphics, centerX, centerY - 8, 16, 16, GuiFx.fade(GuiFx.alpha(color, 150), enter));
        GuiFx.centered(graphics, font, Component.literal("\u2315"), centerX, centerY - 14,
                GuiFx.fade(GuiFx.alpha(color, 200), enter));

        Component message = searching
                ? Component.translatable("screen.habitrain_lottery.role_select.no_match", searchQuery)
                : Component.translatable("screen.habitrain_lottery.role_select.empty");
        GuiFx.centered(graphics, font, message, centerX, centerY + 16, GuiFx.fade(MUTED, enter));
        GuiFx.centered(graphics, font,
                Component.translatable("screen.habitrain_lottery.role_select.empty_hint"),
                centerX, centerY + 30, GuiFx.fade(DIM, enter));
    }

    // =====================================================================
    // 关闭
    // =====================================================================

    @Override
    public void onClose() {
        closeToParent();
    }

    /** 开局时直接返回背包，确保未发送确认包。 */
    void closeForGameStart() {
        if (closedForGameStart) {
            return;
        }
        closedForGameStart = true;
        selectionSubmitted = false;
        confirmingCard = null;
        if (parent instanceof CardUseMenuScreen menu) {
            menu.closeForGameStart();
        } else {
            closeToParent();
        }
    }

    private void closeToParent() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen != parent) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 背景完全自绘，这里刻意不调用 super，避免多铺一层原版暗色遮罩。 */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 见 drawBackground
    }

    // =====================================================================
    // 卡片控件
    // =====================================================================

    private final class RoleCardWidget extends AbstractWidget {
        private final Candidate candidate;
        private final int cardX;
        private final int cardY;
        private final long enterStartMillis;
        private final int enterOrder;

        private float hoverAnim;
        private float focusAnim;
        private float pressAnim;
        private boolean pressed;

        private RoleCardWidget(int x, int y, int width, int height, Candidate candidate) {
            super(x, y, width, height, Component.literal(candidate.displayName()));
            this.candidate = candidate;
            this.cardX = x;
            this.cardY = y;
            this.enterOrder = enterCursor++;
            this.enterStartMillis = cardEnterTimes.computeIfAbsent(candidate.id, key -> pageEnterMillis);

            String boundText = "";
            if (candidate.isBound()) {
                String boundName = candidate.boundDisplayName();
                boundText = "\n" + Component.translatable("screen.habitrain_lottery.role_select.bound",
                        boundName.isBlank() ? shortId(candidate.bound)
                                : boundName + " (" + shortId(candidate.bound) + ")").getString();
            }
            setTooltip(Tooltip.create(Component.literal(candidate.displayName()
                    + "\n" + candidate.id + boundText + "\n"
                    + Component.translatable("screen.habitrain_lottery.role_select.card_tip").getString())));
        }

        /** 本卡片的入场进度（0..1），错峰延迟由序号决定。 */
        private float enterProgress() {
            float delay = enterOrder * CARD_STAGGER_MILLIS;
            float window = Math.max(CARD_ENTER_MILLIS, CARD_ENTER_WINDOW_MILLIS - delay);
            return GuiFx.easeOutCubic(GuiFx.progress(nowMillis, enterStartMillis, delay, window));
        }

        /** 是否被鼠标指向或键盘选中；命名避开 {@link AbstractWidget#isHoveredOrFocused()}。 */
        private boolean wantsAttention() {
            return (active && isHovered) || isFocused();
        }

        private boolean isConfirming() {
            return confirmingCard == this;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean confirming = isConfirming();
            boolean frozen = confirmingCard != null && !confirming;
            float delta = frameDelta;

            hoverAnim = GuiFx.approach(hoverAnim, !frozen && active && isHovered ? 1.0F : 0.0F, delta, 65.0F);
            focusAnim = GuiFx.approach(focusAnim, isFocused() && !frozen ? 1.0F : 0.0F, delta, 80.0F);
            pressAnim = GuiFx.approach(pressAnim, pressed && !frozen ? 1.0F : 0.0F, delta, 45.0F);
            if (frozen) {
                pressed = false;
            }

            float enter = enterProgress();
            if (enter <= 0.001F) {
                return;
            }
            float lift = Mth.clamp(Math.max(hoverAnim, focusAnim), 0.0F, 1.0F);

            RoleVisual visual = candidate.visual();
            Faction faction = visual.faction();

            // 入场：自下而上滑入 + 轻微缩放；悬停时上浮、按下时回压
            float slide = Mth.clamp((1.0F - GuiFx.easeOutQuint(enter)) * 12.0F, -20.0F, 20.0F);
            // 翻页时整屏卡片沿翻页方向轻微偏移，形成方向感
            float sweep = pageDirection * 26.0F
                    * (1.0F - GuiFx.easeOutCubic(GuiFx.progress(nowMillis, pageEnterMillis, 300.0F)));
            float scale = 0.94F + 0.06F * enter + lift * 0.022F - pressAnim * 0.03F;
            int cx = cardX + width / 2;
            int cy = cardY + height / 2;
            float confirmT = confirming
                    ? GuiFx.progress(nowMillis, confirmStartMillis, CONFIRM_MILLIS) : 0.0F;
            float pop = confirming ? GuiFx.easeOutBack(GuiFx.clamp01(confirmT * 1.6F)) : 0.0F;

            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(cx + sweep, cy + slide - lift * 3.0F, 0.0F);
            pose.scale(scale, scale, 1.0F);
            pose.translate(-cx, -cy, 0.0F);

            drawCard(graphics, visual.color(), faction, lift, confirming, confirmT, pop);
            pose.popPose();
        }

        private void drawCard(GuiGraphics graphics, int accent, Faction faction, float lift,
                              boolean confirming, float confirmT, float pop) {
            int x0 = cardX;
            int y0 = cardY;
            int x1 = cardX + width;
            int y1 = cardY + height;

            int top = GuiFx.mix(GuiFx.mix(0xFF232C3C, 0xFF1A2230, lift), accent, lift * 0.20F);
            int bottom = GuiFx.mix(0xFF0E131C, accent, lift * 0.10F);
            int border = confirming
                    ? GuiFx.mix(accent, 0xFFFFFFFF, 0.45F * GuiFx.pulse(confirmT))
                    : GuiFx.mix(GuiFx.mix(0xFF39465A, accent, 0.35F), GOLD, lift * 0.5F);

            GuiFx.card(graphics, x0, y0, x1, y1, CARD_RADIUS, top, bottom, border, accent,
                    Math.max(lift, pop));

            // 悬停时的斜向高光扫过
            if (lift > 0.05F) {
                float sweep = GuiFx.progress(nowMillis, enterStartMillis + 200L, 560.0F);
                GuiFx.beginClip(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1);
                GuiFx.sheen(graphics, x0, y0, x1, y1, -0.25F + sweep * 1.5F, lift * 0.5F);
                GuiFx.endClip(graphics);
            }

            drawPortrait(graphics, accent, faction, lift, pop);
            drawNameplate(graphics, accent, lift);

            if (confirming) {
                drawConfirmOverlay(graphics, accent, confirmT);
            }
            if (focusAnim > 0.03F) {
                GuiFx.roundOutline(graphics, x0 - 2, y0 - 2, x1 + 2, y1 + 2, CARD_RADIUS + 2,
                        GuiFx.fade(GOLD, focusAnim * 0.75F));
            }
        }

        /** 确认反馈：外圈脉冲 + 上升星点 + 右上勾选。 */
        private void drawConfirmOverlay(GuiGraphics graphics, int accent, float confirmT) {
            int x0 = cardX;
            int y0 = cardY;
            int x1 = cardX + width;
            int y1 = cardY + height;
            float pulse = GuiFx.pulse(confirmT);

            GuiFx.glow(graphics, (x0 + x1) / 2, (y0 + y1) / 2, width / 2 + 4, height / 2 + 4,
                    accent, 0.7F * (0.5F + pulse * 0.5F));
            GuiFx.roundOutline(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1, CARD_RADIUS + 1,
                    GuiFx.fade(GuiFx.alpha(0xFFFFFFFF, 210), 0.4F + pulse * 0.6F));

            for (int i = 0; i < 5; i++) {
                float t = GuiFx.clamp01(confirmT * 1.2F - i * 0.12F);
                if (t <= 0.0F || t >= 1.0F) {
                    continue;
                }
                int px = x0 + width / 2 + (i - 2) * (width / 7);
                int py = (int) (y1 - 6 - t * (height * 0.85F));
                graphics.fill(px, py, px + 2, py + 2, GuiFx.fade(accent, 1.0F - t));
            }

            int checkSize = 14;
            int checkX = x1 - checkSize - 4;
            int checkY = y0 + 4;
            GuiFx.roundRect(graphics, checkX, checkY, checkX + checkSize, checkY + checkSize, 7,
                    GuiFx.alpha(0xFF0B0F18, (int) (220 * confirmT)));
            GuiFx.roundOutline(graphics, checkX, checkY, checkX + checkSize, checkY + checkSize, 7,
                    GuiFx.fade(GuiFx.alpha(OK, 255), confirmT));
            if (confirmT > 0.35F) {
                graphics.drawCenteredString(font, Component.literal("\u2714"),
                        checkX + checkSize / 2, checkY + 3,
                        GuiFx.fade(OK, (confirmT - 0.35F) / 0.65F));
            }
        }

        /** 顶部纹章区：柔光 + 菱形纹章 + 阵营徽记（有立绘贴图时叠加立绘）。 */
        private void drawPortrait(GuiGraphics graphics, int accent, Faction faction, float lift, float pop) {
            int areaH = portraitAreaHeight();
            int centerX = cardX + width / 2;
            int centerY = cardY + areaH / 2 + 3;
            int radius = Mth.clamp((int) (Math.min(width, areaH) * 0.34F), 8, 15);

            GuiFx.glow(graphics, centerX, centerY, radius + 6, radius + 6, accent,
                    0.25F + lift * 0.35F + pop * 0.25F);

            GuiFx.diamond(graphics, centerX, centerY, radius, radius,
                    GuiFx.alpha(GuiFx.shade(accent, -0.55F), 235));
            GuiFx.diamond(graphics, centerX, centerY, Math.max(2, radius - 4), Math.max(2, radius - 4),
                    GuiFx.alpha(GuiFx.shade(accent, -0.3F), 150));
            GuiFx.diamondOutline(graphics, centerX, centerY, radius, radius,
                    GuiFx.mix(accent, 0xFFFFFFFF, 0.25F + lift * 0.35F));

            ResourceLocation icon = candidate.visual().icon();
            if (icon != null) {
                int iw = Math.max(8, (int) (radius * 1.1F));
                int ih = Math.max(10, (int) (radius * 1.35F));
                GuiFx.beginClip(graphics, centerX - radius, centerY - radius, centerX + radius, centerY + radius);
                graphics.blit(icon, centerX - iw / 2, centerY - ih / 2, iw, ih, 0.0F, 0.0F, 14, 17, 14, 17);
                GuiFx.endClip(graphics);
            } else {
                // 程序化徽记：先画深色偏移层，保证在任何底色上都清晰
                graphics.drawCenteredString(font, Component.literal(faction.glyph), centerX + 1, centerY - 3,
                        GuiFx.alpha(0xFF000000, 190));
                graphics.drawCenteredString(font, Component.literal(faction.glyph), centerX, centerY - 4,
                        0xFFFFFFFF);
            }

            // 环绕轨道点：缓慢公转，暗示「这里可以选」
            if (radius > 9) {
                double angle = ((nowMillis % 9000.0F) / 9000.0F) * Math.PI * 2.0D;
                int ox = centerX + (int) (Math.cos(angle) * (radius + 5));
                int oy = centerY + (int) (Math.sin(angle) * (radius + 4));
                graphics.fill(ox, oy, ox + 2, oy + 2, GuiFx.alpha(0xFFFFFFFF, 150));
            }

            // 阵营标签
            Component factionLabel = faction.label();
            String labelText = font.plainSubstrByWidth(factionLabel.getString(), Math.max(10, width - 14));
            int labelW = Math.min(font.width(labelText) + 10, width - 8);
            int labelX = cardX + (width - labelW) / 2;
            int labelY = cardY + areaH;
            if (labelW > 6) {
                GuiFx.roundRect(graphics, labelX, labelY, labelX + labelW, labelY + 11, 3,
                        GuiFx.alpha(GuiFx.shade(faction.color, -0.72F), (int) (200 + lift * 40)));
                GuiFx.centered(graphics, font, Component.literal(labelText), cardX + width / 2, labelY + 2,
                        GuiFx.mix(faction.color, 0xFFFFFFFF, lift * 0.4F));
            }
        }

        /** 底部名牌：职业名（打字机入场）+ ID / 绑定信息 + 悬停触点。 */
        private void drawNameplate(GuiGraphics graphics, int accent, float lift) {
            int nameY = cardY + portraitAreaHeight() + 14;
            int idY = cardY + height - 2 - font.lineHeight;
            int available = Math.max(9, idY - nameY - 1);

            String typed = GuiFx.typewriter(candidate.displayName(), enterProgress());
            List<FormattedCharSequence> lines = font.split(Component.literal(typed), width - 8);
            int maxLines = Mth.clamp(available / font.lineHeight, 1, 2);
            int shown = Math.min(maxLines, lines.size());
            int textTop = nameY + Math.max(0, (available - shown * font.lineHeight) / 2);
            for (int i = 0; i < shown; i++) {
                FormattedCharSequence line = lines.get(i);
                graphics.drawString(font, line, cardX + (width - font.width(line)) / 2,
                        textTop + i * font.lineHeight, GuiFx.mix(TEXT, accent, lift * 0.25F), false);
            }

            String meta = candidate.isBound()
                    ? Component.translatable("screen.habitrain_lottery.role_select.bound_short",
                            candidate.boundDisplayName().isBlank()
                                    ? shortId(candidate.bound) : candidate.boundDisplayName()).getString()
                    : shortId(candidate.id);
            String trimmed = font.plainSubstrByWidth(meta, Math.max(16, width - 8));
            graphics.drawString(font, Component.literal(trimmed),
                    cardX + (width - font.width(trimmed)) / 2, idY,
                    candidate.isBound() ? 0xFFFFB56B : DIM, false);

            // 底部触点：悬停时依次亮起
            int dotY = cardY + height - 5;
            int litIndex = Mth.clamp((int) (lift * 3.0F), 0, 2);
            for (int i = 0; i < 3; i++) {
                int dx = cardX + width / 2 - 5 + i * 4;
                boolean lit = lift > 0.35F && i == litIndex;
                graphics.fill(dx, dotY, dx + 2, dotY + 2,
                        GuiFx.alpha(lit ? GOLD : 0xFFFFFFFF, lit ? 190 : 40));
            }
        }

        /** 纹章区高度：随卡片高度自适应，同时为名牌与 ID 行留出空间。 */
        private int portraitAreaHeight() {
            int desired = Math.round(height * 0.38F);
            int limit = Math.max(24, height - font.lineHeight * 2 - 19);
            return Mth.clamp(desired, 24, limit);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (active && isFocused() && (keyCode == 257 || keyCode == 335 || keyCode == 32)) {
                confirm(this);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT,
                    Component.translatable("screen.habitrain_lottery.role_select.card_narration"));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            confirm(this);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            pressed = true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            pressed = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }
    }

    // =====================================================================
    // 职业信息解析
    // =====================================================================

    /**
     * 解析职业的配色、阵营与立绘。
     *
     * <p>结果按职业 id 缓存（{@link Candidate#cachedVisual}），因此整局只会查询一次
     * {@link RoleCatalogApi}。目录不可用时退化为服务端下发的颜色 + 中立阵营，
     * 界面上仍然有稳定可读的配色。</p>
     */
    private static RoleVisual resolveVisual(String roleIdentifier, int fallbackColor) {
        int color = fallbackColor;
        Faction faction = Faction.NEUTRAL;
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            ResourceLocation roleId = parseRoleId(roleIdentifier);
            if (roleId != null) {
                try {
                    EffectiveRole effective = RoleCatalogApi.instance().find(RoleKey.of(roleId)).orElse(null);
                    if (effective != null) {
                        EffectiveRoleProfile profile = effective.profile();
                        if (profile != null) {
                            int profileColor = profile.color();
                            if ((profileColor & 0x00FFFFFF) != 0) {
                                color = profileColor;
                            }
                            faction = factionOf(profile);
                        }
                    }
                } catch (Throwable ignored) {
                    // 目录不可用时保持服务端配色。
                }
            }
        }
        int normalized = (color & 0x00FFFFFF) == 0 ? Faction.NEUTRAL.color
                : ((color & 0xFF000000) == 0 ? color | 0xFF000000 : color);
        return new RoleVisual(normalized, faction, loadIcon(faction));
    }

    private static Faction factionOf(EffectiveRoleProfile profile) {
        if (profile.canUseKiller()) {
            return Faction.KILLER;
        }
        if (profile.innocent() && !profile.neutral()) {
            return Faction.INNOCENT;
        }
        if (profile.vigilanteTeam()) {
            return Faction.VIGILANTE;
        }
        return profile.neutralForKiller() ? Faction.NEUTRAL_FOR_KILLER : Faction.NEUTRAL;
    }

    /**
     * 立绘贴图只在「资源确实存在」时返回，否则返回 {@code null} 让调用方使用程序化徽记。
     *
     * <p>乘客 / 杀手阵营的贴图位于其它命名空间，无法保证随包下发，因此直接跳过；
     * 其余三个阵营使用 SRE 自带的无条件随包资源。查表结果按阵营缓存，避免逐卡 I/O。</p>
     */
    private static ResourceLocation loadIcon(Faction faction) {
        ResourceLocation id = switch (faction) {
            case NEUTRAL -> ResourceLocation.fromNamespaceAndPath("noellesroles",
                    "textures/gui/sprites/hud/mood_neu.png");
            case NEUTRAL_FOR_KILLER -> ResourceLocation.fromNamespaceAndPath("noellesroles",
                    "textures/gui/sprites/hud/mood_jester.png");
            case VIGILANTE -> ResourceLocation.fromNamespaceAndPath("noellesroles",
                    "textures/gui/sprites/hud/mood_vig.png");
            case INNOCENT, KILLER -> null;
        };
        return textureAvailable(id) ? id : null;
    }

    /** 检查客户端资源管理器是否真的能读到该贴图，避免渲染成紫黑占位图。 */
    private static boolean textureAvailable(ResourceLocation id) {
        return CardUiStyle.textureExists(id);
    }

    private static ResourceLocation parseRoleId(String roleIdentifier) {
        ResourceLocation roleId = ResourceLocation.tryParse(roleIdentifier);
        if (roleId == null && !roleIdentifier.contains(":")) {
            roleId = ResourceLocation.tryParse("starrailexpress:" + roleIdentifier);
        }
        return roleId;
    }

    public static String resolveRoleDisplayName(String roleIdentifier, String rawName) {
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            ResourceLocation roleId = parseRoleId(roleIdentifier);
            if (roleId != null) {
                // 1. habitrain_core RoleCatalogApi
                try {
                    EffectiveRole effective = RoleCatalogApi.instance().find(RoleKey.of(roleId)).orElse(null);
                    if (effective != null && effective.role() != null && effective.role().getName() != null) {
                        String effName = effective.role().getName().getString();
                        if (isMeaningfulRoleName(effName, roleId)) {
                            return effName;
                        }
                    }
                } catch (Throwable e) {
                    // catalog unavailable in unit tests / classload
                }

                // 2. SRE RoleUtils
                try {
                    Component ruName = RoleUtils.getRoleName(roleId);
                    if (ruName != null) {
                        String s = ruName.getString();
                        if (isMeaningfulRoleName(s, roleId)) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {
                }

                // 4. Client Language dictionary
                Language lang = Language.getInstance();
                String key1 = "announcement.star.role." + roleId.getPath();
                if (lang.has(key1)) {
                    return lang.getOrDefault(key1);
                }
                String key2 = "announcement.star.role." + roleId.toString();
                if (lang.has(key2)) {
                    return lang.getOrDefault(key2);
                }
                String key3 = "role." + roleId.getNamespace() + "." + roleId.getPath();
                if (lang.has(key3)) {
                    return lang.getOrDefault(key3);
                }
                String key4 = "role." + roleId.getPath();
                if (lang.has(key4)) {
                    return lang.getOrDefault(key4);
                }
                String key5 = "role." + roleId.toString();
                if (lang.has(key5)) {
                    return lang.getOrDefault(key5);
                }
                String key6 = "sre.role." + roleId.getPath();
                if (lang.has(key6)) {
                    return lang.getOrDefault(key6);
                }
            }
        }

        // 5. Check rawName
        if (rawName != null && !rawName.isBlank()) {
            Language lang = Language.getInstance();
            if (lang.has(rawName)) {
                return lang.getOrDefault(rawName);
            }
            if (!rawName.startsWith("announcement.") && !rawName.startsWith("role.") && !rawName.startsWith("sre.")) {
                return rawName;
            }
        }

        // 6. Fallback to path or id
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            int colon = roleIdentifier.indexOf(':');
            return colon >= 0 && colon + 1 < roleIdentifier.length()
                    ? roleIdentifier.substring(colon + 1)
                    : roleIdentifier;
        }
        return Component.translatable("screen.habitrain_lottery.role_select.unknown").getString();
    }

    public static String resolveBoundRoleDisplayName(String bound) {
        if (bound == null || bound.isBlank()) {
            return "";
        }
        return resolveRoleDisplayName(bound, null);
    }

    private static boolean isMeaningfulRoleName(String text, ResourceLocation roleId) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (roleId != null) {
            if (trimmed.equalsIgnoreCase(roleId.toString()) || trimmed.equalsIgnoreCase(roleId.getPath())) {
                return false;
            }
        }
        if (trimmed.startsWith("announcement.star.role.") || trimmed.startsWith("role.")
                || trimmed.startsWith("sre.role.")) {
            return false;
        }
        return true;
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return Component.translatable("screen.habitrain_lottery.role_select.unknown").getString();
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
        return font.plainSubstrByWidth(path, Math.max(16, cardWidth - 12));
    }
}
