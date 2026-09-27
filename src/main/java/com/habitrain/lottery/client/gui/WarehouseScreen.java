package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.SkinClient;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.CardUseMenuS2C;
import com.habitrain.lottery.network.CardUseRequestC2S;
import com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState;
import com.habitrain.lottery.network.WarehouseNetwork;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.warehouse.WarehouseEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 账户奖励仓库：与开箱终端同一套视觉语汇。
 *
 * <p>失焦庭院舞台 → 居中眉题 / 大标题 / 资产标签 → 玻璃展柜（分类标签 + 搜索 + 品质卡片格栅）
 * → 底部操作栏（状态 · 关闭 · 账户）。点击卡片从右侧滑出玻璃详情面板：展台光、品质标签、
 * 说明与操作按钮。几何全部由 {@link WarehouseLayout} 计算。</p>
 *
 * <p>只保留有实际作用的控件：分类标签（按数量自动隐藏空分类）、搜索、关闭（职业自选时为返回）、
 * 同步失败时的重试，以及详情面板里的返回 / 操作按钮（没有可执行操作的物品不显示操作按钮）。</p>
 */
public final class WarehouseScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.warehouse.";
    /** 分类标签，顺序即显示顺序。 */
    private static final String[] CATEGORIES = {"all", "crates", "cards", "items", "appearance"};

    /** 仓库本体；职业自选只在使用自选卡后进入，不作为常驻入口。 */
    private enum Page { WAREHOUSE, ROLES }

    private enum Kind { TAB, PRIMARY, GHOST }

    private final Screen parent;
    private WarehouseLayout layout;
    private Page page = Page.WAREHOUSE;
    private String filter = "all", query = "";
    private boolean draggingPreview;
    private float previewYaw, previewPitch;
    private BakedModel measuredSkinModel;
    private SkinGeometry skinGeometry;
    private static final float YAW_SENSITIVITY = 0.03F, PITCH_SENSITIVITY = 0.02F, PITCH_LIMIT = 1.4F;
    private List<WarehouseEntry> inventory = List.of();
    private List<WarehouseRole> roles = List.of();
    private final List<Tile> tiles = new ArrayList<>();
    private List<Tile> outgoing = List.of();
    private final List<Control> tabControls = new ArrayList<>();
    private Control closeControl, retryControl, detailBack, detailAction;
    private EditBox search;
    private int searchX, searchY, searchW, searchH;
    private double scroll;
    private long opened = -1, changed = -1000, lastFrame, requestAt;
    /** 每次进入本界面（首次打开或从开箱终端返回）的时间，驱动合焦与落位动画。 */
    private long enteredAt = -1;
    /** 前往开箱终端的交接标记，−1 表示没有正在进行的交接。 */
    private long crateDepartAt = -1;
    private String pendingCrate = "";
    private boolean handedOff;
    private float delta = 16, switchDirection = 1;
    private int requestId, requestAttempts, expectedTotal = -1, cardEpoch;
    private long expectedInventoryRevision, incomingRevision = -1, knownInventoryRevision;
    private long cardRequestAt;
    private int cardAttempts;
    private final List<WarehouseEntry> incoming = new ArrayList<>();
    private boolean loading, known, cardsKnown, refreshAfterReturn;
    private String error = "", notice = "", waitingCard = "";
    private long waitingSince, submitAt = -1, pendingSince = -1;
    private String submitKey = "", submitMode = "", submitRole = "";
    private Tile detail;
    private long detailAt;
    private boolean detailClosing;
    private int detailScroll, detailMaxScroll;
    /** 舞台里缓慢漂浮的浮尘，与开箱终端同一套粒子（参考空间 1920 宽）。 */
    private final CrateParticles motes = new CrateParticles();
    private float moteClock;

    public WarehouseScreen(Screen parent) { super(text("title")); this.parent = parent; }
    private static Component text(String suffix, Object... args) { return Component.translatable(KEY + suffix, args); }
    private static Component translated(String key) { return Component.translatableWithFallback(key, key); }
    private static long now() { return Util.getMillis(); }

    @Override protected void init() {
        // 像素字体：每个字形像素占整数个屏幕像素才清晰，大标题固定为「字形像素 = 2 屏幕像素」。
        double guiScale = minecraft == null ? 2 : minecraft.getWindow().getGuiScale();
        layout = WarehouseLayout.of(width, height, (float) (4 / Math.max(1, guiScale)));
        boolean first = opened < 0;
        if (first) { opened = now(); }
        // 从开箱终端返回时同样播放一次合焦与落位，保证两个方向的转场风格一致。
        enteredAt = now();
        lastFrame = enteredAt;
        crateDepartAt = -1;
        handedOff = false;
        detail = null;
        rebuildChrome();
        rebuildGrid(false);
        if (first || refreshAfterReturn) { refreshAfterReturn = false; refresh(true); }
    }

    // =====================================================================
    // 控件
    // =====================================================================

    /** 分类标签 + 搜索框（展柜顶行）、底栏按钮、详情面板按钮。 */
    private void rebuildChrome() {
        boolean searching = search != null && search.isFocused();
        clearWidgets(); tabControls.clear();
        int panelW = layout.panelX1() - layout.panelX0();
        int left = layout.panelX0() + layout.pad(), right = layout.panelX1() - layout.pad();
        searchH = layout.tabHeight();
        searchY = layout.tabY();
        searchW = Mth.clamp(Math.round(panelW * .26F), 64, 150);

        if (page == Page.WAREHOUSE) {
            List<String> ids = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (String id : CATEGORIES) {
                int count = categoryCount(id);
                if (id.equals("all") || count > 0) { ids.add(id); counts.add(count); }
            }
            if (!ids.contains(filter)) { filter = "all"; scroll = 0; }
            int padX = layout.compact() ? 5 : 7, tabGap = layout.compact() ? 2 : 4;
            boolean withCounts = tabsWidth(ids, counts, true, padX, tabGap) <= right - searchW - 8 - left;
            int total = tabsWidth(ids, counts, withCounts, padX, tabGap);
            // 分类必须完整可见：放不下时让搜索框让位，最窄保留一个放大镜的宽度。
            if (left + total > right - searchW - 8) searchW = Math.max(36, right - 8 - left - total);
            int tx = left;
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                Control tab = new Control(tx, layout.tabY(), tabWidth(id, counts.get(i), withCounts, padX), layout.tabHeight(),
                        Kind.TAB, text("category." + id), () -> selectCategory(id), id);
                tab.badge = withCounts ? counts.get(i) : -1;
                tabControls.add(addRenderableWidget(tab));
                tx += tab.getWidth() + tabGap;
            }
        }
        searchX = right - searchW;

        int glyph = Math.max(6, searchH - 8);
        search = new EditBox(font, searchX + glyph + 8, searchY + (searchH - 8) / 2, Math.max(12, searchW - glyph - 12), 8,
                text("search"));
        search.setBordered(false); search.setMaxLength(96);
        // EditBox 不裁剪提示文字，窄搜索框里要自己截断。
        search.setHint(Component.literal(trim(text("search").getString(), search.getWidth(), 1)));
        search.setTextColor(WarehouseTheme.TEXT_BRIGHT);
        search.setValue(query); search.setResponder(value -> { query = value; scroll = 0; rebuildGrid(false); });
        addRenderableWidget(search);

        // 底栏：关闭（职业自选时为返回仓库）；同步失败时多一个重试。
        int buttonH = Mth.clamp(layout.barHeight() - 8, 16, 22);
        int buttonY = layout.barTop() + (layout.barHeight() - buttonH) / 2 + 1;
        Component closeLabel = text(page == Page.ROLES ? "back" : "close");
        int closeW = Math.max(64, font.width(closeLabel) + 28);
        Component retryLabel = text("retry");
        int retryW = Math.max(64, font.width(retryLabel) + 28);
        closeControl = addRenderableWidget(new Control(0, buttonY, closeW, buttonH, Kind.GHOST, closeLabel,
                () -> { if (page == Page.ROLES) switchPage(Page.WAREHOUSE); else onClose(); }, null));
        retryControl = addRenderableWidget(new Control(0, buttonY, retryW, buttonH, Kind.PRIMARY, retryLabel,
                () -> refresh(true), null));

        detailBack = new Control(0, 0, 60, 22, Kind.GHOST, text("back"), this::closeDetail, null);
        detailAction = new Control(0, 0, 60, 22, Kind.PRIMARY, text("use"), this::detailAction, null);
        addRenderableWidget(detailBack); addRenderableWidget(detailAction);
        detailBack.visible = detailAction.visible = false;
        if (searching) setFocused(search);
        updateControls();
    }

    private int tabWidth(String id, int count, boolean withCount, int padX) {
        int w = font.width(text("category." + id)) + padX * 2;
        return withCount ? w + 4 + font.width(String.valueOf(count)) : w;
    }

    private int tabsWidth(List<String> ids, List<Integer> counts, boolean withCounts, int padX, int gap) {
        int total = gap * Math.max(0, ids.size() - 1);
        for (int i = 0; i < ids.size(); i++) total += tabWidth(ids.get(i), counts.get(i), withCounts, padX);
        return total;
    }

    private void selectCategory(String id) {
        if (filter.equals(id) || busy()) return;
        int from = indexOf(filter), to = indexOf(id);
        switchDirection = to >= from ? 1 : -1;
        filter = id; scroll = 0;
        rebuildGrid(true);
        updateControls();
    }

    private static int indexOf(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(category)) return i;
        return 0;
    }

    private void switchPage(Page next) {
        if (next == page || busy()) return;
        switchDirection = next.ordinal() >= page.ordinal() ? 1 : -1;
        captureOutgoing();
        // 从职业自选返回时落在「角色卡」分类：那正是进入自选的地方。
        page = next; filter = next == Page.WAREHOUSE ? "cards" : "all"; query = ""; scroll = 0; detail = null;
        rebuildChrome(); rebuildGrid(false); changed = now();
    }

    // =====================================================================
    // 数据 → 卡片
    // =====================================================================

    /** 合并实时的角色卡余额后的仓库条目。 */
    private List<WarehouseEntry> entries() {
        List<WarehouseEntry> out = new ArrayList<>(inventory.size());
        for (WarehouseEntry entry : inventory) {
            if ("card".equals(entry.kind()) && cardsKnown) entry = new WarehouseEntry(entry.kind(), entry.id(), entry.name(),
                    entry.description(), entry.icon(), ClientLotteryState.cardBalances.getOrDefault(entry.id(), entry.count()),
                    entry.color(), false);
            out.add(entry);
        }
        return out;
    }

    private static String categoryOf(WarehouseEntry e) {
        return switch (e.kind()) {
            case "card" -> "cards";
            case "skin", "title" -> "appearance";
            case "special" -> CrateCatalog.isCrateItem(e.id()) || CrateCatalog.isKeyItem(e.id()) ? "crates" : "items";
            default -> "items";
        };
    }

    /** 「全部」只列持有的条目；「角色卡」分类列出全部卡种，余额为 0 的卡以暗色显示。 */
    private static boolean shown(WarehouseEntry e, String category) {
        if (!"all".equals(category) && !categoryOf(e).equals(category)) return false;
        return e.count() > 0 || e.kind().equals("currency") || "cards".equals(category) && e.kind().equals("card");
    }

    private int categoryCount(String category) {
        int count = 0;
        for (WarehouseEntry e : entries()) if (shown(e, category) && (e.count() > 0 || e.kind().equals("currency"))) count++;
        return count;
    }

    private void captureOutgoing() {
        outgoing = tiles.stream().filter(t -> t.getY() + t.getHeight() > layout.top() && t.getY() < layout.bottom()).toList();
    }

    private void rebuildGrid(boolean animate) {
        if (layout == null) return;
        if (animate) { captureOutgoing(); changed = now(); }
        for (Tile tile : tiles) removeWidget(tile);
        tiles.clear();
        if (getFocused() instanceof Tile) setFocused(null);
        List<Tile> result = new ArrayList<>();
        if (page == Page.ROLES) {
            for (WarehouseRole role : roles) {
                WarehouseEntry entry = new WarehouseEntry("role", role.id, role.displayName(),
                        role.isBound() ? text("bound_to", role.boundDisplayName()).getString() : text("role_hint").getString(),
                        "minecraft:paper", role.taken ? 0 : 1, role.color, false);
                Tile tile = new Tile(entry, role);
                if (matches(tile)) result.add(tile);
            }
        } else {
            for (WarehouseEntry entry : entries()) {
                if (!shown(entry, filter)) continue;
                Tile tile = new Tile(entry, null);
                if (matches(tile)) result.add(tile);
            }
        }
        // 固定顺序：可开启的箱子与钥匙 → 货币 → 角色卡（服务端顺序）→ 皮肤按品质 → 称号 → 其他道具。
        Comparator<Tile> order = Comparator.<Tile>comparingInt(WarehouseScreen::rank)
                .thenComparing(t -> "card".equals(t.entry.kind()) ? "" : t.getMessage().getString(), String.CASE_INSENSITIVE_ORDER);
        result.sort(order);
        tiles.addAll(result);
        for (int i = 0; i < tiles.size(); i++) { tiles.get(i).index = i; addWidget(tiles.get(i)); }
        scroll = Mth.clamp(scroll, 0, layout.maxScroll(tiles.size()));
        positionTiles();
    }

    private static int rank(Tile t) {
        WarehouseEntry e = t.entry;
        if (t.role != null) return t.role.taken ? 1 : 0;
        return switch (e.kind()) {
            case "special" -> CrateCatalog.isCrateItem(e.id()) ? 0 : CrateCatalog.isKeyItem(e.id()) ? 1 : 30;
            case "currency" -> 2;
            case "card" -> e.count() > 0 ? 3 : 4;
            case "skin" -> 10 + (SkinQuality.values().length - 1 - e.quality().ordinal());
            case "title" -> 20;
            default -> 40;
        };
    }

    private boolean matches(Tile tile) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        String name = tile.getMessage().getString();
        if (q.isEmpty() || (name + " " + tile.entry.id() + " " + tile.entry.description()).toLowerCase(Locale.ROOT).contains(q)) return true;
        try { return io.wifi.starrailexpress.client.util.PinYinUtils.contains(q, name); }
        catch (RuntimeException ex) { return false; }
    }

    private void positionTiles() {
        for (Tile t : tiles) {
            t.setX(layout.tileX(t.index)); t.setY(layout.tileY(t.index, scroll));
            t.visible = t.getY() + t.getHeight() > layout.top() && t.getY() < layout.bottom();
            t.active = t.visible && detail == null && !transitioning() && !busy();
        }
    }

    // =====================================================================
    // 网络
    // =====================================================================

    private void refresh(boolean resetAttempts) {
        if (busy() || loading && now() - requestAt < 1000) return;
        if (resetAttempts) { requestAttempts = 0; cardsKnown = false; }
        if (!ClientPlayNetworking.canSend(WarehouseNetwork.Request.TYPE)) { error = "unavailable"; loading = false; return; }
        requestId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        incoming.clear(); expectedTotal = -1; incomingRevision = -1; loading = true; error = ""; requestAt = now(); requestAttempts++;
        ClientPlayNetworking.send(new WarehouseNetwork.Request(requestId));
        if (!CardGuiGameState.gameActiveOrStarting() && ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) {
            cardEpoch = ClientLotteryState.cardInventoryVersion;
            if (resetAttempts) cardAttempts = 0;
            requestCards();
        }
    }

    private void requestCards() {
        cardRequestAt = now(); cardAttempts++;
        ClientPlayNetworking.send(new CardUseRequestC2S("inventory"));
    }

    public void receive(WarehouseNetwork.Snapshot snapshot) {
        if (!loading || snapshot.requestId() != requestId) return;
        if (!snapshot.error().isEmpty()) { loading = false; error = snapshot.error(); return; }
        if (snapshot.inventoryRevision() < expectedInventoryRevision
                || incomingRevision >= 0 && incomingRevision != snapshot.inventoryRevision()) {
            loading = false; error = "sync_error"; return;
        }
        if (snapshot.offset() != incoming.size() || expectedTotal >= 0 && expectedTotal != snapshot.total()) {
            loading = false; error = "sync_error"; return;
        }
        expectedTotal = snapshot.total(); incomingRevision = snapshot.inventoryRevision(); incoming.addAll(snapshot.entries());
        if (incoming.size() == expectedTotal) {
            inventory = List.copyOf(incoming); loading = false; known = true; error = "";
            knownInventoryRevision = incomingRevision;
            if (layout != null) rebuildChrome();
            rebuildGrid(false);
        }
    }

    public void expectInventoryRevision(long revision) {
        expectedInventoryRevision = Math.max(expectedInventoryRevision, revision);
        refreshAfterReturn = true;
    }

    public void receiveLateCrateResult(long revision) {
        expectInventoryRevision(revision);
        refreshAfterReturn = false;
        refresh(true);
    }

    /** A delayed response never opens a closed screen or interrupts a different request. */
    public void receiveCardMenu(CardUseMenuS2C payload) {
        if (waitingCard.isEmpty() || !waitingCard.equals(payload.questKey()) || CardGuiGameState.gameActiveOrStarting()) return;
        waitingCard = "";
        if ("self_select".equals(payload.questKey())) {
            try {
                List<WarehouseRole> parsed = new Gson().fromJson(payload.candidatesJson(), new TypeToken<List<WarehouseRole>>() {}.getType());
                roles = parsed == null ? List.of() : parsed.stream().filter(r -> r != null && r.id != null && !r.id.isBlank()).toList();
                closeDetailImmediately(); switchPage(Page.ROLES);
            } catch (RuntimeException e) { notice = "sync_error"; }
        } else {
            // Faction activation uses an explicit confirmation inside the detail panel.
            notice = "";
        }
    }

    private boolean canUse(WarehouseEntry e) {
        if (!cardsKnown || CardGuiGameState.gameActiveOrStarting() || busy() || !error.isEmpty() || loading
                || ClientLotteryState.cardBalances.getOrDefault(e.id(), 0) <= 0) return false;
        return "limit_break".equals(e.id()) || ("self_select".equals(e.id())
                ? ClientLotteryState.cardUseRemainingSelfUses : ClientLotteryState.cardUseRemainingUses) > 0;
    }

    private boolean busy() { return submitAt >= 0 || pendingSince >= 0 || !waitingCard.isEmpty(); }
    private boolean transitioning() {
        if (crateDepartAt >= 0) return true;
        // 落位动画期间格栅是被位移绘制的，命中区域却没有位移，因此同样锁住输入。
        if (ScreenSwap.arriving(now(), enteredAt)) return true;
        return now() - opened < WarehouseMotion.OPEN_MS || now() - changed < WarehouseMotion.SWITCH_MS;
    }

    /**
     * 前往开箱终端：与开箱终端的进场约定一致，是 0ms 硬切（{@link ScreenSwap#bridge} 在该方向返回 0）。
     * 记下交接标记后把 Screen 切换交给主线程队列，最快下一帧完成替换。
     */
    private void beginCrateDepart(String crateId) {
        if (crateDepartAt >= 0) return;
        pendingCrate = crateId;
        crateDepartAt = now();
        handedOff = false;
        closeDetail();
        finishCrateDepart();
    }

    /** 交接完成后真正切换 Screen；在渲染中直接切换会打断当前帧，故交给主线程队列（下一帧生效）。 */
    private void finishCrateDepart() {
        if (handedOff || pendingCrate.isEmpty() || minecraft == null) return;
        handedOff = true;
        String crateId = pendingCrate;
        refreshAfterReturn = true;
        minecraft.execute(() -> minecraft.setScreen(new CrateOpenScreen(this, crateId)));
    }

    // =====================================================================
    // 详情面板
    // =====================================================================

    private void openDetail(Tile tile) {
        if (busy() || transitioning()) return;
        detail = tile; detailAt = now(); detailClosing = false; detailScroll = 0; notice = "";
        draggingPreview = false; previewYaw = previewPitch = 0;
        measuredSkinModel = null; skinGeometry = null;
        updateControls();
        setFocused(detailAction.visible ? detailAction : detailBack);
    }

    private void closeDetail() {
        if (busy()) return;
        detailClosing = true; detailAt = now();
    }

    private void closeDetailImmediately() {
        Tile previous = detail;
        detail = null; detailClosing = false; draggingPreview = false;
        detailBack.visible = detailAction.visible = false;
        if (previous != null && tiles.contains(previous)) setFocused(previous); else setFocused(null);
        updateControls();
    }

    private static boolean isCrate(WarehouseEntry e) {
        return "special".equals(e.kind()) && CrateCatalog.isCrateItem(e.id());
    }

    /** 这个条目在详情面板里有没有可执行的操作；没有时只显示返回按钮。 */
    private static boolean hasAction(WarehouseEntry e) {
        return switch (e.kind()) {
            case "card", "role", "skin", "title" -> true;
            default -> isCrate(e);
        };
    }

    private void detailAction() {
        if (detail == null || busy() || detailClosing) return;
        WarehouseEntry e = detail.entry;
        if ("card".equals(e.kind())) {
            if (!canUse(e)) return;
            if ("self_select".equals(e.id())) {
                waitingCard = e.id(); waitingSince = now();
                ClientPlayNetworking.send(new CardUseRequestC2S(e.id()));
            } else prepareSubmit(e.id(), "limit_break".equals(e.id()) ? "bonus" : "direct", "");
        } else if ("role".equals(e.kind())) {
            if (detail.role.taken || !cardsKnown || CardGuiGameState.gameActiveOrStarting()
                    || ClientLotteryState.cardBalances.getOrDefault("self_select", 0) <= 0
                    || ClientLotteryState.cardUseRemainingSelfUses <= 0) return;
            prepareSubmit("self_select", "self", detail.role.id);
        } else if ("skin".equals(e.kind()) || "title".equals(e.kind())) minecraft.setScreen(new SkinWardrobeScreen(this));
        else if (isCrate(e)) {
            String crateId = e.id().substring((CrateService.NAMESPACE + ":crate_").length());
            beginCrateDepart(crateId);
        }
    }

    private void prepareSubmit(String key, String mode, String role) {
        submitKey = key; submitMode = mode; submitRole = role; submitAt = now(); notice = "";
    }

    @Override public void tick() {
        long time = now();
        boolean inGame = CardGuiGameState.gameActiveOrStarting();
        if (inGame && (busy() || page == Page.ROLES || detail != null && detail.entry.kind().equals("card"))) {
            submitAt = pendingSince = -1; waitingCard = ""; closeDetailImmediately();
            if (page == Page.ROLES) switchPage(Page.WAREHOUSE);
            notice = "lobby_only";
        }
        if (ClientLotteryState.cardInventoryVersion != cardEpoch) {
            cardEpoch = ClientLotteryState.cardInventoryVersion; cardsKnown = true;
            if (pendingSince >= 0) {
                pendingSince = -1; closeDetailImmediately();
                if (page == Page.ROLES) switchPage(Page.WAREHOUSE);
                notice = "receipt"; refresh(true);
            }
            rebuildChrome();
            rebuildGrid(false);
        }
        if (!cardsKnown && !busy() && !inGame && cardAttempts < 4 && time - cardRequestAt > 1200
                && ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) requestCards();
        if (loading && time - requestAt > 4000) {
            loading = false;
            if (requestAttempts < 3) refresh(false); else error = "timeout";
        }
        if (!loading && expectedInventoryRevision > knownInventoryRevision && requestAttempts < 3
                && time - requestAt > 600 && !busy()) refresh(false);
        if (!waitingCard.isEmpty() && time - waitingSince > 5000) { waitingCard = ""; notice = "timeout"; }
        if (submitAt >= 0 && time - submitAt >= 180) {
            submitAt = -1;
            if (!inGame) {
                cardEpoch = ClientLotteryState.cardInventoryVersion;
                pendingSince = time;
                if (!LotteryClientNetwork.clientCardUseConfirm(submitKey, submitMode, submitRole)) {
                    pendingSince = -1; notice = "sync_error";
                }
            }
        }
        if (pendingSince >= 0 && time - pendingSince > 6000) {
            pendingSince = -1; cardsKnown = false; notice = "timeout"; refresh(true);
        }
        if (detailClosing && time - detailAt > 180) closeDetailImmediately();
        if (time - changed > WarehouseMotion.SWITCH_MS) outgoing = List.of();
        updateControls();
        super.tick();
    }

    private void updateControls() {
        if (detailBack == null) return;
        boolean modal = detail != null;
        boolean locked = crateDepartAt >= 0;
        boolean usable = !modal && !busy() && !locked;
        for (Control c : tabControls) { c.on = c.value.equals(filter); c.active = usable; }
        if (search != null) search.active = usable;

        // 底栏：重试只在同步失败时出现，两个按钮整体居中。
        boolean retry = !error.isEmpty() && !loading && page == Page.WAREHOUSE;
        retryControl.visible = retry;
        closeControl.active = retryControl.active = usable;
        int gap = 8, total = closeControl.getWidth() + (retry ? retryControl.getWidth() + gap : 0);
        int bx = (width - total) / 2;
        if (retry) { retryControl.setX(bx); bx += retryControl.getWidth() + gap; }
        closeControl.setX(bx);

        detailBack.visible = modal;
        detailAction.visible = modal && hasAction(detail.entry);
        detailBack.active = modal && !busy() && !detailClosing;
        detailAction.active = false;
        if (modal) {
            placeDetailButtons();
            var e = detail.entry;
            if (e.kind().equals("card")) {
                detailAction.setMessage(text(busy() ? "pending" : e.id().equals("self_select") ? "choose_role" : "confirm_use"));
                detailAction.active = canUse(e);
            } else if (e.kind().equals("role")) {
                detailAction.setMessage(text(busy() ? "pending" : "confirm_role"));
                detailAction.active = !busy() && !detail.role.taken && cardsKnown && !CardGuiGameState.gameActiveOrStarting()
                        && ClientLotteryState.cardBalances.getOrDefault("self_select", 0) > 0 && ClientLotteryState.cardUseRemainingUses > 0;
            } else {
                boolean crate = isCrate(e);
                detailAction.setMessage(text(crate ? "open_crate" : "wardrobe"));
                detailAction.active = !busy() && (!crate || e.count() > 0);
            }
            detailAction.active &= !detailClosing && now() - detailAt >= 220;
        }
        positionTiles();
    }

    private int drawerInset() { return width >= 480 && height >= 300 ? 8 : 0; }
    private int drawerWidth() { return Math.min(Math.min(300, width - 24), Math.max(200, width * 2 / 5)); }
    private int drawerX0() { return width - drawerInset() - drawerWidth(); }
    private int drawerX1() { return width - drawerInset(); }
    private int drawerY0() { return drawerInset(); }
    private int drawerY1() { return height - drawerInset(); }
    private int artTop() { return drawerY0() + (height < 280 ? 8 : 14); }
    private int artHeight() { return Math.min(128, Math.max(44, height / 4)); }
    private int buttonRowY() { return drawerY1() - 32; }

    private void placeDetailButtons() {
        int x0 = drawerX0() + 12, x1 = drawerX1() - 12, y = buttonRowY();
        int backW = detailAction.visible ? Math.max(56, font.width(detailBack.getMessage()) + 24) : x1 - x0;
        detailBack.setRectangle(backW, 22, x0, y);
        detailAction.setRectangle(Math.max(40, x1 - x0 - backW - 6), 22, x0 + backW + 6, y);
    }

    // =====================================================================
    // 绘制
    // =====================================================================

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now(); delta = Math.min(80, time - lastFrame); lastFrame = time;
        float arrive = ScreenSwap.arrive(time, enteredAt);
        float a = ScreenSwap.arriveFade(arrive);
        WarehouseTheme.stage(g, width, height, arrive);
        drawMotes(g, a);
        drawHeader(g, a);
        drawPanel(g, a);
        drawGrid(g, mx, my, time, a);
        drawBar(g, a);

        // 详情按钮画在面板自己的平移层里；控件通道只画标签、搜索与底栏按钮。
        boolean backShown = detailBack.visible, actionShown = detailAction.visible;
        detailBack.visible = detailAction.visible = false;
        if (search != null) drawSearchField(g, a);
        super.render(g, detail == null ? mx : -1, detail == null ? my : -1, partialTick);
        detailBack.visible = backShown; detailAction.visible = actionShown;

        if (detail != null) drawDetail(g, mx, my, partialTick);
        else if (!transitioning() && layout.contains(mx, my)) {
            for (Tile t : tiles) if (t.visible && t.isMouseOver(mx, my)) {
                List<FormattedCharSequence> tooltip = new ArrayList<>(font.split(t.getMessage(), Math.min(240, width - 24)));
                Component kind = t.kindLabel();
                tooltip.add(kind.copy().withColor(t.accent & 0xFFFFFF).getVisualOrderText());
                if (t.role == null && (t.entry.count() > 1 || t.entry.kind().equals("currency"))) {
                    tooltip.add(text("quantity", t.entry.count()).getVisualOrderText());
                }
                g.renderTooltip(font, tooltip, mx, my); break;
            }
        }
        if (crateDepartAt >= 0) {
            float bridge = ScreenSwap.bridge(time, crateDepartAt, -1L);
            if (bridge > 0.005F) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 600);
                CrateArt.bridge(g, width, height, ScreenSwap.BRIDGE_COLOR, bridge);
                g.pose().popPose();
            }
            if (ScreenSwap.departed(time, crateDepartAt)) finishCrateDepart();
        }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) { /* Single background in render. */ }

    /** 舞台浮尘：在 1920 宽的参考空间里发射与绘制，与开箱终端的尘埃同尺度。 */
    private void drawMotes(GuiGraphics g, float a) {
        float unit = width / 1920F;
        moteClock += delta;
        while (moteClock > 110) {
            moteClock -= 110;
            motes.motes(160, 80, 1760, height / unit - 80, 1, 0xFFFFE9C4);
        }
        motes.tick(delta);
        g.pose().pushPose();
        g.pose().scale(unit, unit, 1);
        motes.render(g, .8F * a, false);
        g.pose().popPose();
    }

    /** 居中眉题 + 大标题 + 资产标签，构图与开箱终端的 header 一致。 */
    private void drawHeader(GuiGraphics g, float a) {
        if (a <= .02F) return;
        float cx = width / 2F;
        int gold = CrateArt.GOLD_LINE;
        if (layout.eyebrowY() >= 0) {
            float ew = spacedWidth(text("eyebrow").getString(), 1, 2);
            spaced(g, text("eyebrow").getString(), cx, layout.eyebrowY(), 1, 2,
                    GuiFx.fade(GuiFx.mix(WarehouseTheme.TEXT_DIM, gold, .5F), a));
            float y = layout.eyebrowY() + 4;
            CrateFx.hairline(g, cx - ew / 2 - 90, cx - ew / 2 - 10, y, 1, 40, GuiFx.fade(gold, .75F * a), false);
            CrateFx.hairline(g, cx + ew / 2 + 10, cx + ew / 2 + 90, y, 1, 40, GuiFx.fade(gold, .75F * a), false);
        }
        String title = text(page == Page.ROLES ? "roles" : "title").getString();
        float s = layout.titleScale();
        float tw = font.width(title) * s;
        CrateFx.glow(g, cx, layout.titleY() + 4.5F * s, tw * .7F + 30, 11 * s, gold, .18F * a, true);
        draw(g, title, cx - tw / 2, layout.titleY(), s, GuiFx.fade(WarehouseTheme.TEXT_BRIGHT, a), true);

        // 资产标签：绿苹果 / 箱子 / 今日用卡次数；放不下时从后往前省略。
        List<Chip> chips = new ArrayList<>();
        if (page == Page.WAREHOUSE) {
            WarehouseEntry apples = null;
            int crates = 0;
            ItemStack crateIcon = ItemStack.EMPTY;
            for (WarehouseEntry e : inventory) {
                if (e.kind().equals("currency") && e.id().equals("green_apples")) apples = e;
                if (isCrate(e) && e.count() > 0) {
                    crates += e.count();
                    if (crateIcon.isEmpty()) crateIcon = itemOf(e.icon());
                }
            }
            if (apples != null) chips.add(new Chip(text("chip.apples", apples.count()).getString(), WarehouseTheme.APPLE,
                    ItemStack.EMPTY, ResourceLocation.tryParse(apples.icon())));
            if (crates > 0) chips.add(new Chip(text("chip.crates", crates).getString(), gold, crateIcon, null));
        } else {
            chips.add(new Chip(text("chip.self_select", Math.max(0, ClientLotteryState.cardBalances.getOrDefault("self_select", 0))).getString(),
                    WarehouseTheme.ACCENT_SELF_SELECT, ItemStack.EMPTY, null));
        }
        if (cardsKnown) chips.add(new Chip(text("chip.quota", Math.max(0, ClientLotteryState.cardUseRemainingUses),
                Math.max(0, ClientLotteryState.cardUseRemainingSelfUses)).getString(), WarehouseTheme.TEAL, ItemStack.EMPTY, null));
        float gap = 6, total;
        while (true) {
            total = -gap;
            for (Chip c : chips) total += c.width() + gap;
            if (chips.isEmpty() || total <= width - 16) break;
            chips.remove(chips.size() - 1);
        }
        float x = cx - total / 2;
        for (Chip c : chips) {
            c.draw(g, x, layout.chipY(), layout.chipHeight(), a);
            x += c.width() + gap;
        }
    }

    private ItemStack itemOf(String id) {
        ResourceLocation item = ResourceLocation.tryParse(id);
        ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(item));
        return stack.isEmpty() ? new ItemStack(Items.CHEST) : stack;
    }

    /** 头部标签：暗色斜切底 + 左侧图标（或品质菱形）+ 文字。 */
    private final class Chip {
        final String label;
        final int accent;
        final ItemStack icon;
        final ResourceLocation art;

        Chip(String label, int accent, ItemStack icon, ResourceLocation art) {
            this.label = label; this.accent = accent; this.icon = icon;
            this.art = art != null && minecraft.getResourceManager().getResource(art).isPresent() ? art : null;
        }

        float width() { return font.width(label) + 28; }

        void draw(GuiGraphics g, float x, float y, int h, float a) {
            float w = width();
            chip(g, x, y, x + w, y + h, accent, a);
            float iy = y + h / 2F;
            if (art != null) {
                g.setColor(1, 1, 1, a);
                int s = h - 4;
                g.blit(art, Math.round(x + 11 - s / 2F), Math.round(iy - s / 2F), s, s, 0, 0, 32, 32, 32, 32);
                g.setColor(1, 1, 1, 1);
            } else if (!icon.isEmpty()) {
                CrateArt.item(g, icon, x + 11, iy, (h - 3) / 16F, 0, 0, 1, a);
            } else {
                CrateFx.diamond(g, x + 11, iy, 3, 3, GuiFx.fade(accent, a), false);
                CrateFx.glow(g, x + 11, iy, 8, accent, .4F * a);
            }
            g.pose().pushPose(); g.pose().translate(0, 0, 200);
            WarehouseScreen.this.draw(g, label, x + 21, Math.round(iy - 4), 1, GuiFx.fade(WarehouseTheme.TEXT_BRIGHT, a), false);
            g.pose().popPose();
        }
    }

    /** 玻璃展柜：开箱终端物品条的底板、细描边与金色分隔线。 */
    private void drawPanel(GuiGraphics g, float a) {
        int x0 = layout.panelX0(), y0 = layout.panelY0(), x1 = layout.panelX1(), y1 = layout.panelY1();
        CrateArt.stripPanel(g, x0, y0, x1, y1, .95F * a);
        GuiFx.outline(g, x0, y0, x1, y1, GuiFx.fade(0x26FFFFFF, a));
        CrateFx.hairline(g, x0, x1, y0, 1, (x1 - x0) * .3F, GuiFx.fade(CrateArt.GOLD_LINE, .55F * a), false);
        CrateFx.hairline(g, x0, x1, y0 - 3, 7, (x1 - x0) * .35F, GuiFx.fade(CrateArt.GOLD_LINE, .16F * a), true);
        float sep = layout.top() - (layout.compact() ? 3 : 4);
        CrateFx.hairline(g, x0 + layout.pad(), x1 - layout.pad(), sep, 1, 60, GuiFx.fade(0x33FFFFFF, a), false);
        if (page == Page.ROLES) {
            String hint = text("role_page_hint").getString();
            int max = searchX - 8 - (x0 + layout.pad());
            draw(g, trim(hint, max, 1), x0 + layout.pad(), layout.tabY() + (layout.tabHeight() - 8) / 2F, 1,
                    GuiFx.fade(WarehouseTheme.TEXT_GOLD, a), false);
        }
    }

    private void drawSearchField(GuiGraphics g, float a) {
        boolean focused = search.isFocused();
        chip(g, searchX, searchY, searchX + searchW, searchY + searchH,
                focused ? CrateArt.GOLD_LINE : 0xFF8A9490, a * (search.active ? 1 : .5F));
        int glyph = Math.max(6, searchH - 8);
        WarehouseTheme.glyphSearch(g, searchX + 5, searchY + (searchH - glyph) / 2, glyph,
                GuiFx.fade(focused ? CrateArt.GOLD_LINE : WarehouseTheme.TEXT_DIM, a));
    }

    private void drawGrid(GuiGraphics g, int mx, int my, long time, float sceneAlpha) {
        boolean switching = time - changed < WarehouseMotion.SWITCH_MS;
        float progress = switching ? WarehouseMotion.progress(time, changed, WarehouseMotion.SWITCH_MS) : 1;
        // 留出悬停上浮与外发光的空间，但不压到分类行。
        g.enableScissor(layout.panelX0() + 1, layout.top() - 3, layout.panelX1() - 1, layout.bottom());
        g.pose().pushPose();
        boolean interactive = detail == null && !transitioning();
        if (switching && progress < .5F) {
            float p = WarehouseMotion.ease(progress * 2);
            g.pose().translate(-switchDirection * 24 * p, 0, 0);
            for (Tile t : outgoing) t.paint(g, -1, -1, (1 - p) * sceneAlpha, 0);
        } else {
            float p = switching ? WarehouseMotion.ease((progress - .5F) * 2) : 1;
            g.pose().translate(switchDirection * 24 * (1 - p), 0, 0);
            int firstRow = Math.max(0, (int) scroll / layout.rowHeight());
            for (Tile t : tiles) if (t.visible) {
                float enter = WarehouseMotion.reveal(time, Math.max(opened, enteredAt) + 120,
                        Math.max(0, t.index - firstRow * layout.columns()));
                t.paint(g, interactive ? mx : -1, interactive ? my : -1, p * enter * sceneAlpha,
                        Math.round((1 - enter) * 10));
            }
        }
        g.pose().popPose();
        if (!known && loading) drawSkeleton(g, time, sceneAlpha);
        else if (tiles.isEmpty() && (known || page == Page.ROLES)) drawEmpty(g, sceneAlpha);
        g.disableScissor();
        drawScrollbar(g, sceneAlpha);
    }

    /** 首次同步中：一行暗色卡片呼吸。 */
    private void drawSkeleton(GuiGraphics g, long time, float a) {
        for (int i = 0; i < layout.columns(); i++) {
            float breathe = .35F + .25F * (float) Math.sin(time / 260.0 - i * .5);
            int x = layout.tileX(i);
            ref(g, x, layout.top(), x + layout.tileWidth(), layout.top() + layout.tileHeight(),
                    (w, h) -> CrateArt.rarityCard(g, 0, 0, w, h, WarehouseTheme.NEUTRAL, .6F, 0, breathe * a));
        }
        centeredText(g, text("loading").getString(), layout.top() + layout.tileHeight() + 12, 1, WarehouseTheme.TEXT_DIM, a);
    }

    private void drawEmpty(GuiGraphics g, float a) {
        float cx = (layout.panelX0() + layout.panelX1()) / 2F;
        float cy = layout.top() + Math.min(60, layout.viewportHeight() / 2F) - 10;
        CrateFx.glow(g, cx, cy, 70, 26, CrateArt.GOLD_LINE, .10F * a, true);
        CrateFx.diamond(g, cx, cy - 12, 4, 4, GuiFx.fade(CrateArt.GOLD_LINE, .8F * a), false);
        centeredText(g, text("empty").getString(), cy - 2, 1, WarehouseTheme.TEXT_BODY, a);
        centeredText(g, text(query.isBlank() ? "empty_hint" : "search_empty").getString(), cy + 12, .8F, WarehouseTheme.TEXT_MUTED, a);
    }

    private void centeredText(GuiGraphics g, String s, float y, float scale, int color, float a) {
        float cx = (layout.panelX0() + layout.panelX1()) / 2F;
        draw(g, s, cx - font.width(s) * scale / 2, y, scale, GuiFx.fade(color, a), false);
    }

    private void drawScrollbar(GuiGraphics g, float a) {
        int max = layout.maxScroll(tiles.size());
        if (max <= 0) return;
        int view = layout.viewportHeight();
        int h = Math.max(16, view * view / (view + max));
        int x = layout.panelX1() - 4;
        int y = layout.top() + (int) ((view - h) * scroll / max);
        g.fill(x, layout.top(), x + 2, layout.bottom(), GuiFx.fade(0x22FFFFFF, a));
        g.fill(x, y, x + 2, y + h, GuiFx.fade(CrateArt.GOLD_LINE, .8F * a));
    }

    /** 底部操作栏：开箱终端的暗带 + 发丝线；左侧状态，右侧账户，中间按钮由控件通道绘制。 */
    private void drawBar(GuiGraphics g, float a) {
        int top = layout.barTop();
        CrateFx.vGradient(g, 0, top - 18, width, top, 0x00030404, GuiFx.fade(0xB0030404, a), false);
        g.fill(0, top, width, height, GuiFx.fade(0xD8030404, a));
        CrateFx.hairline(g, 0, width, top, 1, width * .35F, GuiFx.fade(0x70FFFFFF, a), false);
        CrateFx.hairline(g, width * .3F, width * .7F, top, 1, width * .12F, GuiFx.fade(WarehouseTheme.TEAL, .8F * a), false);
        Component status = !error.isEmpty() ? text(error) : !notice.isEmpty() ? text(notice) : loading ? text("loading")
                : text(page == Page.ROLES ? "role_count" : "item_count", tiles.size());
        int buttonsLeft = Math.min(closeControl.getX(), retryControl.visible ? retryControl.getX() : closeControl.getX());
        int ty = top + (layout.barHeight() - 8) / 2 + 1;
        int margin = Math.max(8, layout.panelX0());
        boolean warn = !error.isEmpty() || "timeout".equals(notice) || "sync_error".equals(notice) || "lobby_only".equals(notice);
        draw(g, trim(status.getString(), buttonsLeft - margin - 8, 1), margin, ty, 1,
                GuiFx.fade(warn ? WarehouseTheme.DANGER : WarehouseTheme.TEXT_BODY, a), false);
        String account = minecraft == null || minecraft.player == null ? "" : minecraft.player.getGameProfile().getName();
        int right = width - margin;
        int room = right - (closeControl.getX() + closeControl.getWidth()) - 8;
        if (!account.isEmpty() && room > 40) {
            String s = trim(text("account", account).getString(), room, 1);
            draw(g, s, right - font.width(s), ty, 1, GuiFx.fade(WarehouseTheme.TEXT_DIM, a), false);
        }
    }

    // ---------------------------------------------------------------------
    // 详情面板
    // ---------------------------------------------------------------------

    private record SkinGeometry(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

    /** Read the baked vertices once per model; display transforms may still animate each frame. */
    private SkinGeometry skinGeometry(BakedModel model) {
        if (model == measuredSkinModel && skinGeometry != null) return skinGeometry;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        RandomSource random = RandomSource.create(42L);
        for (int face = -1; face < Direction.values().length; face++) {
            random.setSeed(42L);
            Direction direction = face < 0 ? null : Direction.values()[face];
            for (BakedQuad quad : model.getQuads(null, direction, random)) {
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                if (stride < 3) continue;
                for (int i = 0; i < 4; i++) {
                    float vx = Float.intBitsToFloat(vertices[i * stride]);
                    float vy = Float.intBitsToFloat(vertices[i * stride + 1]);
                    float vz = Float.intBitsToFloat(vertices[i * stride + 2]);
                    if (!Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz)) continue;
                    minX = Math.min(minX, vx); minY = Math.min(minY, vy); minZ = Math.min(minZ, vz);
                    maxX = Math.max(maxX, vx); maxY = Math.max(maxY, vy); maxZ = Math.max(maxZ, vz);
                }
            }
        }
        // A missing/custom model has no baked quads; retain a conservative item-sized box.
        skinGeometry = minX == Float.POSITIVE_INFINITY
                ? new SkinGeometry(0, 0, 0, 1, 1, 1)
                : new SkinGeometry(minX, minY, minZ, maxX, maxY, maxZ);
        measuredSkinModel = model;
        return skinGeometry;
    }

    private record PreviewFit(Vector3f center, float radius) {}

    /** Bound the model after its FIXED display transform, including any skin animation. */
    private PreviewFit previewFit(BakedModel model) {
        SkinGeometry bounds = skinGeometry(model);
        var transform = new com.mojang.blaze3d.vertex.PoseStack();
        model.getTransforms().getTransform(ItemDisplayContext.FIXED).apply(false, transform);
        transform.translate(-.5F, -.5F, -.5F);
        var matrix = transform.last().pose();
        Vector3f center = new Vector3f(
                (bounds.minX + bounds.maxX) / 2,
                (bounds.minY + bounds.maxY) / 2,
                (bounds.minZ + bounds.maxZ) / 2).mulPosition(matrix);
        float radius = 0;
        for (int i = 0; i < 8; i++) {
            Vector3f corner = new Vector3f(
                    (i & 1) == 0 ? bounds.minX : bounds.maxX,
                    (i & 2) == 0 ? bounds.minY : bounds.maxY,
                    (i & 4) == 0 ? bounds.minZ : bounds.maxZ).mulPosition(matrix);
            radius = Math.max(radius, corner.distance(center));
        }
        return new PreviewFit(center, Math.max(.01F, radius));
    }

    /** Draw only the selected skin's item model, without the local player or equipped gear. */
    private void drawSkinModel(GuiGraphics g, ItemStack skin, int x1, int y1, int x2, int y2, float slide) {
        if (skin.isEmpty()) return;
        BakedModel model = SkinClient.model(skin, false,
                minecraft.getItemRenderer().getModel(skin, minecraft.level, minecraft.player, 0));
        PreviewFit fit = previewFit(model);
        float size = Math.min((x2 - x1 - 20) / (2 * fit.radius),
                (y2 - y1 - 16) / (2 * fit.radius));
        int sx = Math.round(slide);
        g.enableScissor(x1 + sx, y1 - detailScroll, x2 + sx, y2 - detailScroll);
        g.pose().pushPose();
        try {
            g.pose().translate((x1 + x2) / 2.0F, (y1 + y2) / 2.0F, 150);
            g.pose().mulPose(new Quaternionf().rotateY(previewYaw).rotateX(previewPitch));
            g.pose().scale(size, -size, size);
            g.pose().translate(-fit.center.x, -fit.center.y, -fit.center.z);
            minecraft.getItemRenderer().render(skin, ItemDisplayContext.FIXED, false, g.pose(),
                    g.bufferSource(), 0xF000F0, OverlayTexture.NO_OVERLAY, model);
            g.flush();
        } finally {
            g.pose().popPose();
            g.disableScissor();
        }
    }

    /**
     * 详情面板：开箱终端确认弹窗的语汇——压暗整屏、右侧玻璃面板滑入、品质色展台光与符文环、
     * 品质标签、名称与品质横条、说明，底部返回 + 主按钮。
     */
    private void drawDetail(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now();
        float p = WarehouseMotion.ease(WarehouseMotion.progress(time, detailAt, detailClosing ? 180 : 240));
        if (detailClosing) p = 1 - p;
        int x0 = drawerX0(), y0 = drawerY0(), x1 = drawerX1(), y1 = drawerY1(), w = x1 - x0;
        int accent = detail.accent;
        // Item rendering adds 150/200 to Z: both the scrim and panel must cover those items.
        g.pose().pushPose(); g.pose().translate(0, 0, 350);
        g.fill(0, 0, width, height, GuiFx.fade(0x70000000, p));
        CrateFx.hGradient(g, width * .4F, 0, width, height, 0x00000000, GuiFx.fade(0x60000000, p), false);
        g.pose().popPose();
        float slide = (1 - p) * (w + drawerInset());
        g.pose().pushPose(); g.pose().translate(slide, 0, 400);
        final float alpha = p;
        ref(g, x0, y0, x1, y1, (rw, rh) -> {
            CrateFx.rectGlow(g, 0, 0, rw, rh, 60, accent, .16F * alpha);
            // 玻璃面板本身半透明；先垫一层实底，身后的卡片与文字不会透出来干扰阅读。
            CrateFx.bevelPanel(g, 0, 0, rw, rh, 14, GuiFx.fade(0xFF161B1D, alpha), GuiFx.fade(0xFF0B0E10, alpha));
            CrateArt.glassPanel(g, 0, 0, rw, rh, 14, accent, alpha);
        });

        int artTop = artTop(), artH = artHeight();
        int contentBottom = buttonRowY() - 16;
        g.enableScissor(x0 + 2 + Math.round(slide), artTop - 6, x1 - 2 + Math.round(slide), contentBottom);
        g.pose().pushPose(); g.pose().translate(0, -detailScroll, 0);
        float cx = (x0 + x1) / 2F, cy = artTop + artH / 2F - 3;
        float pedY = artTop + artH - 6, pedR = w * .30F;
        CrateFx.glow(g, cx, cy, w * .44F, artH * .56F, accent, .30F * p, true);
        CrateFx.glow(g, cx, pedY, pedR, 7, 0xFF000000, .55F * p, false);
        CrateFx.dashRing(g, cx, pedY, pedR, 7, 30, .55F, time / 1400F, 1.4F, accent, .55F * p);
        CrateFx.ring(g, cx, pedY, pedR * 1.04F, 2, 7 / pedR, accent, .25F * p);
        if (detail.entry.kind().equals("skin") && !detail.icon.isEmpty()) {
            drawSkinModel(g, detail.icon, x0 + 8, artTop, x1 - 8, artTop + artH - 4, slide);
        } else {
            float bob = (float) Math.sin(time / 520.0) * 1.5F;
            detail.drawIcon(g, cx, cy + bob, artH - 22, p);
        }

        int tx = x0 + 14, tw = w - 28;
        float y = artTop + artH + 8;
        // 品质 / 种类标签 + 数量标签
        String kind = detail.kindLabel().getString();
        float kw = font.width(kind) + 24;
        chip(g, tx, y, tx + kw, y + 14, accent, p);
        CrateFx.diamond(g, tx + 8, y + 7, 2.5F, 2.5F, GuiFx.fade(accent, p), false);
        CrateFx.glow(g, tx + 8, y + 7, 7, accent, .5F * p);
        draw(g, kind, tx + 15, y + 3, 1, GuiFx.fade(GuiFx.mix(WarehouseTheme.TEXT_BRIGHT, accent, .4F), p), false);
        if (detail.role == null) {
            String count = text("quantity", detail.entry.count()).getString();
            float qx = tx + kw + 5, qw = font.width(count) + 14;
            chip(g, qx, y, qx + qw, y + 14, 0xFF8A9490, p);
            draw(g, count, qx + 7, y + 3, 1, GuiFx.fade(WarehouseTheme.TEXT_BODY, p), false);
        }
        y += 20;

        // 名称一行放得下时用大标题的清晰倍率，否则回到 1 倍换行。
        float nameScale = font.width(detail.getMessage()) * layout.titleScale() <= tw ? layout.titleScale() : 1F;
        List<FormattedCharSequence> name = font.split(detail.getMessage(), Math.max(20, (int) (tw / nameScale)));
        for (int i = 0; i < Math.min(2, name.size()); i++) {
            g.pose().pushPose(); g.pose().translate(tx, y, 0); g.pose().scale(nameScale, nameScale, 1);
            g.drawString(font, name.get(i), 0, 0, GuiFx.fade(WarehouseTheme.TEXT_BRIGHT, p), true);
            g.pose().popPose();
            y += 10 * nameScale + 1;
        }
        y += 4;
        final float ruleAlpha = p;
        ref(g, tx, y, tx + tw, y + 1, (rw, rh) -> CrateArt.rarityRule(g, 0, 0, Math.round(rw), accent, ruleAlpha));
        y += 8;

        String description = translated(detail.entry.description()).getString();
        if (detail.role != null && detail.role.taken) description += "\n" + text("taken").getString();
        for (FormattedCharSequence line : font.split(Component.literal(description), tw)) {
            g.drawString(font, line, tx, Math.round(y), GuiFx.fade(WarehouseTheme.TEXT_BODY, p), false);
            y += 11;
        }
        Component state = detailState();
        if (state != null) {
            y += 3;
            boolean ok = detailAction.active;
            for (FormattedCharSequence line : font.split(state, tw)) {
                g.drawString(font, line, tx, Math.round(y), GuiFx.fade(ok ? WarehouseTheme.TEXT_GOLD : WarehouseTheme.DANGER, p), false);
                y += 11;
            }
        }
        detailMaxScroll = Math.max(0, Math.round(y + detailScroll) - contentBottom + 4);
        detailScroll = Math.min(detailScroll, detailMaxScroll);
        g.pose().popPose();
        g.disableScissor();
        if (detailMaxScroll > 0) {
            int track = contentBottom - artTop - 18;
            int sy = artTop + track * detailScroll / detailMaxScroll;
            g.fill(x1 - 5, sy, x1 - 3, sy + 18, GuiFx.fade(accent, .8F * p));
        }

        CrateFx.hairline(g, x0 + 10, x1 - 10, buttonRowY() - 8, 1, 50, GuiFx.fade(0x40FFFFFF, p), false);
        if (!notice.isEmpty()) {
            draw(g, trim(text(notice).getString(), tw, 1), tx, buttonRowY() - 20, 1, GuiFx.fade(WarehouseTheme.DANGER, p), false);
        }
        if (submitAt >= 0) {
            float fill = WarehouseMotion.progress(time, submitAt, 180);
            CrateFx.hairline(g, detailAction.getX(), detailAction.getX() + detailAction.getWidth() * fill,
                    buttonRowY() - 5, 2, 10, GuiFx.fade(CrateArt.GOLD_LINE, p), false);
        }
        int hx = p == 1 ? mx : -1, hy = p == 1 ? my : -1;
        detailBack.render(g, hx, hy, partialTick);
        if (detailAction.visible) detailAction.render(g, hx, hy, partialTick);
        g.pose().popPose();
    }

    /** 面板里的状态行：角色卡能否使用、为什么不能；自选职业是否已被占用。 */
    private Component detailState() {
        WarehouseEntry e = detail.entry;
        if (e.kind().equals("card")) {
            return text(!cardsKnown ? "card_sync" : CardGuiGameState.gameActiveOrStarting() ? "lobby_only"
                    : e.count() <= 0 ? "not_owned" : !canUse(e) && !busy() ? "no_uses" : "cost_one");
        }
        if (e.equipped()) return text("equipped");
        if (isCrate(e) && e.count() <= 0) return text("caption_spent");
        return null;
    }

    // ---------------------------------------------------------------------
    // 参考画布：开箱终端的斜切角、发光、品质卡都是按 1920×1080 调的，
    // 这里按同一比例缩放后绘制，两边的线宽、光晕与倒角观感一致。
    // ---------------------------------------------------------------------

    private float unit() { return Math.max(.15F, Math.min(width / 1920F, height / 1080F)); }

    private interface RefPainter { void paint(float w, float h); }

    private void ref(GuiGraphics g, float x0, float y0, float x1, float y1, RefPainter painter) {
        float u = unit();
        g.pose().pushPose();
        g.pose().translate(x0, y0, 0);
        g.pose().scale(u, u, 1);
        painter.paint((x1 - x0) / u, (y1 - y0) / u);
        g.pose().popPose();
    }

    private void chip(GuiGraphics g, float x0, float y0, float x1, float y1, int accent, float a) {
        ref(g, x0, y0, x1, y1, (w, h) -> CrateArt.chip(g, 0, 0, w, h, accent, a));
    }

    // ---------------------------------------------------------------------
    // 文字工具
    // ---------------------------------------------------------------------

    private void draw(GuiGraphics g, String s, float x, float y, float scale, int color, boolean shadow) {
        if ((color >>> 24) < 5 || s.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        if (scale != 1) g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, color, shadow);
        g.pose().popPose();
    }

    private String trim(String raw, float maxWidth, float scale) {
        int allowed = Math.max(1, (int) (maxWidth / scale));
        return font.width(raw) <= allowed ? raw : font.plainSubstrByWidth(raw, Math.max(0, allowed - font.width("…"))) + "…";
    }

    private float spacedWidth(String s, float scale, float spacing) {
        return (font.width(s) + spacing * Math.max(0, s.length() - 1)) * scale;
    }

    /** 带字距的居中文字（眉题）。 */
    private void spaced(GuiGraphics g, String s, float cx, float y, float scale, float spacing, int color) {
        if ((color >>> 24) < 5) return;
        g.pose().pushPose();
        g.pose().translate(cx - spacedWidth(s, scale, spacing) / 2, y, 0);
        g.pose().scale(scale, scale, 1);
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            g.drawString(font, ch, 0, 0, color, false);
            g.pose().translate(font.width(ch) + spacing, 0, 0);
        }
        g.pose().popPose();
    }

    // =====================================================================
    // 输入
    // =====================================================================

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        if (detail != null) {
            if (busy() || detailClosing || now() - detailAt < 240) return true;
            if (detailBack.mouseClicked(x, y, button)) { setFocused(detailBack); return true; }
            if (detailAction.visible && detailAction.mouseClicked(x, y, button)) { setFocused(detailAction); return true; }
            if (detail.entry.kind().equals("skin") && !detail.icon.isEmpty()
                    && x >= drawerX0() + 8 && x < drawerX1() - 8
                    && y >= artTop() - detailScroll && y < artTop() + artHeight() - detailScroll) {
                draggingPreview = true;
                return true;
            }
            if (x < drawerX0()) closeDetail();
            return true;
        }
        if (transitioning() || busy()) return true;
        if (layout.contains(x, y)) {
            if (search != null) search.setFocused(false);
            if (x >= layout.panelX1() - 8 && layout.maxScroll(tiles.size()) > 0) { dragScroll(y); return true; }
            for (Tile t : tiles) if (t.visible && t.isMouseOver(x, y)) { setFocused(t); openDetail(t); return true; }
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    private void dragScroll(double y) {
        scroll = Mth.clamp((y - layout.top()) / layout.viewportHeight(), 0, 1) * layout.maxScroll(tiles.size());
        positionTiles();
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && draggingPreview && detail != null) {
            previewYaw -= (float) dx * YAW_SENSITIVITY;
            previewPitch = Mth.clamp(previewPitch - (float) dy * PITCH_SENSITIVITY, -PITCH_LIMIT, PITCH_LIMIT);
            return true;
        }
        if (button == 0 && detail == null && !busy() && x >= layout.panelX1() - 8 && x <= layout.panelX1()
                && y >= layout.top() && y < layout.bottom()) {
            dragScroll(y); return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && draggingPreview) { draggingPreview = false; return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (detail != null && x >= drawerX0()) {
            detailScroll = Mth.clamp(detailScroll - (int) (vertical * 22), 0, detailMaxScroll); return true;
        }
        if (detail != null || busy() || transitioning() || !layout.contains(x, y)) return false;
        scroll = Mth.clamp(scroll - vertical * layout.rowHeight() * .48, 0, layout.maxScroll(tiles.size()));
        positionTiles(); return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) {
            if (detail != null) { closeDetail(); return true; }
            if (page == Page.ROLES) { switchPage(Page.WAREHOUSE); return true; }
            onClose(); return true;
        }
        if (busy() || transitioning()) return true;
        if (detail != null) {
            if (key == 264 || key == 265 || key == 266 || key == 267) {
                detailScroll = Mth.clamp(detailScroll + (key == 264 || key == 267 ? 1 : -1) * (key >= 266 ? 66 : 22), 0, detailMaxScroll); return true;
            }
            if (key == 258) {
                setFocused(getFocused() == detailBack && detailAction.visible && detailAction.active ? detailAction : detailBack);
                return true;
            }
            return getFocused() instanceof Control c && c.keyPressed(key, scan, modifiers);
        }
        if (search != null && search.isFocused()) return super.keyPressed(key, scan, modifiers);
        int step = switch (key) { case 263 -> -1; case 262 -> 1; case 265 -> -layout.columns(); case 264 -> layout.columns(); default -> 0; };
        if (step != 0 && !tiles.isEmpty()) {
            int current = getFocused() instanceof Tile t ? t.index : -1;
            Tile target = tiles.get(Mth.clamp(current < 0 ? 0 : current + step, 0, tiles.size() - 1));
            int y = layout.tileY(target.index, scroll);
            if (y < layout.top()) scroll -= layout.top() - y;
            else if (y + layout.tileHeight() > layout.bottom()) scroll += y + layout.tileHeight() - layout.bottom();
            scroll = Mth.clamp(scroll, 0, layout.maxScroll(tiles.size()));
            positionTiles(); setFocused(target); return true;
        }
        if ((key == 257 || key == 335 || key == 32) && getFocused() instanceof Tile t) { openDetail(t); return true; }
        if (key == 266 || key == 267) {
            scroll = Mth.clamp(scroll + (key == 267 ? 1 : -1) * layout.viewportHeight(), 0, layout.maxScroll(tiles.size()));
            positionTiles(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean charTyped(char c, int modifiers) { return detail == null && !busy() && super.charTyped(c, modifiers); }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public void removed() { refreshAfterReturn = true; }
    @Override public boolean isPauseScreen() { return false; }

    // =====================================================================
    // 控件与卡片
    // =====================================================================

    /** 分类标签（选中时金色斜切底）、主按钮（绿色斜切）与次级按钮（暗色玻璃），与开箱终端同款。 */
    private final class Control extends AbstractWidget {
        private final Runnable action;
        private final Kind kind;
        private final String value;
        private boolean on;
        private int badge = -1;
        private float hover;

        Control(int x, int y, int w, int h, Kind kind, Component message, Runnable action, String value) {
            super(x, y, w, h, message);
            this.kind = kind; this.action = action; this.value = value;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = width, h = height;
            hover = GuiFx.approach(hover, isHoveredOrFocused() && active ? 1 : 0, delta, 45);
            switch (kind) {
                case TAB -> {
                    if (on) {
                        chip(g, x, y, x + w, y + h, CrateArt.GOLD_LINE, 1);
                        CrateFx.hairline(g, x + 3, x + w - 3, y + h - 1, 1, w * .3F, GuiFx.fade(CrateArt.GOLD_LINE, .9F), false);
                    } else if (hover > .02F) {
                        chip(g, x, y, x + w, y + h, 0xFF8A9490, .8F * hover);
                    }
                    int color = on ? WarehouseTheme.TEXT_BRIGHT
                            : GuiFx.mix(WarehouseTheme.TEXT_DIM, WarehouseTheme.TEXT_BRIGHT, hover);
                    String label = getMessage().getString();
                    int padX = layout.compact() ? 5 : 7;
                    int ty = y + (h - 8) / 2;
                    g.drawString(font, label, x + padX, ty, active ? color : GuiFx.fade(color, .5F), on);
                    if (badge >= 0) {
                        draw(g, String.valueOf(badge), x + padX + font.width(label) + 4, ty, 1,
                                on ? WarehouseTheme.TEXT_GOLD : WarehouseTheme.TEXT_MUTED, false);
                    }
                }
                case PRIMARY, GHOST -> {
                    float a = active ? 1 : .45F;
                    float lift = -hover;
                    float hv = hover;
                    if (kind == Kind.PRIMARY) ref(g, x, y + lift, x + w, y + h + lift,
                            (rw, rh) -> CrateArt.primaryButton(g, 0, 0, rw, rh, a, hv, active ? .6F : 0, now()));
                    else ref(g, x, y + lift, x + w, y + h + lift,
                            (rw, rh) -> CrateArt.ghostButton(g, 0F, 0, rw, rh, active ? 1 : .55F, hv));
                    String s = trim(getMessage().getString(), w - 8, 1);
                    g.drawString(font, s, x + (w - font.width(s)) / 2, Math.round(y + (h - 8) / 2F + lift),
                            active ? 0xFFFFFFFF : 0xFFBDBDBD, true);
                }
            }
        }

        @Override public void onClick(double x, double y) { if (active) action.run(); }
        @Override public boolean keyPressed(int key, int scan, int mods) {
            if (active && (key == 257 || key == 335 || key == 32)) { action.run(); return true; } return false;
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    /** 品质卡片：开箱物品条同款（暗玻璃底、底部品质光与品质条），角标显示数量与可执行状态。 */
    private final class Tile extends AbstractWidget {
        final WarehouseEntry entry;
        final WarehouseRole role;
        final ItemStack icon;
        final ResourceLocation art;
        final int accent;
        int index;
        float hover;
        long hoverAt;

        Tile(WarehouseEntry entry, WarehouseRole role) {
            super(0, 0, layout.tileWidth(), layout.tileHeight(), entryName(entry));
            this.entry = entry; this.role = role;
            int color = switch (entry.kind()) {
                case "skin" -> entry.quality().color();
                case "card" -> WarehouseTheme.cardColor(entry.id());
                case "currency" -> WarehouseTheme.APPLE;
                default -> (entry.color() & 0xFFFFFF) != 0 ? entry.color() : WarehouseTheme.NEUTRAL;
            };
            accent = 0xFF000000 | color;
            String artId = role != null ? role.cardArt() : entry.kind().equals("card") ? entry.id() : null;
            ResourceLocation candidate = artId != null ? ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/cards/" + artId + ".png") : null;
            if (entry.kind().equals("currency") && entry.id().equals("green_apples")) {
                candidate = ResourceLocation.tryParse(entry.icon());
            }
            art = candidate != null && minecraft.getResourceManager().getResource(candidate).isPresent() ? candidate : null;
            ItemStack stack = ItemStack.EMPTY;
            if (entry.kind().equals("skin")) stack = SkinItems.preview(entry.id());
            if (stack.isEmpty()) {
                ResourceLocation item = ResourceLocation.tryParse(entry.kind().equals("currency") ? "minecraft:apple" : entry.icon());
                stack = new ItemStack(item == null ? Items.CHEST : BuiltInRegistries.ITEM.get(item));
            }
            icon = stack.isEmpty() ? new ItemStack(Items.CHEST) : stack;
        }

        private static Component entryName(WarehouseEntry entry) {
            if (entry.kind().equals("skin")) {
                String s = translated(entry.name()).getString();
                if (s.equals(entry.name())) {
                    String[] parts = entry.id().split("/", 2);
                    String legacy = parts.length == 2 ? "screen.sre.skins." + parts[0] + "." + parts[1] + ".name" : entry.id();
                    return Component.translatableWithFallback(legacy, parts[parts.length - 1]);
                }
            }
            return translated(entry.name());
        }

        /** 种类 / 品质标签（详情面板与悬停提示）。 */
        Component kindLabel() {
            if (role != null) return text("kind.role");
            return switch (entry.kind()) {
                case "skin" -> Component.translatable("skin.habitrain_lottery.quality.label",
                        Component.translatable(entry.quality().translationKey()));
                case "special" -> text(CrateCatalog.isCrateItem(entry.id()) ? "kind.crate"
                        : CrateCatalog.isKeyItem(entry.id()) ? "kind.key" : "kind.special");
                case "card", "currency", "title" -> text("kind." + entry.kind());
                default -> text("kind.special");
            };
        }

        /** 左上角状态角标：只标出「现在能做点什么」的卡片，其余保持干净。 */
        String tag() {
            if (role != null) return role.taken ? text("tag.taken").getString() : null;
            if (entry.equipped()) return text("tag.equipped").getString();
            if (entry.kind().equals("card") && canUse(entry)) return text("tag.usable").getString();
            if (isCrate(entry) && entry.count() > 0) return text("tag.openable").getString();
            return null;
        }

        boolean dimmed() {
            return role != null ? role.taken : entry.kind().equals("card") && entry.count() <= 0;
        }

        String quantity() {
            if (role != null) return "";
            return entry.kind().equals("currency") || entry.count() > 1 ? text("quantity_compact", entry.count()).getString() : "";
        }

        void paint(GuiGraphics g, int mx, int my, float opacity, int drop) {
            if (opacity < .01F) return;
            boolean over = isMouseOver(mx, my) || isFocused() && detail == null;
            if (over && hover < .05F) hoverAt = now();
            hover = GuiFx.approach(hover, over ? 1 : 0, delta, 60);
            float x = getX(), y = getY() + drop - 2 * hover;
            int w = width, h = height;
            boolean dim = dimmed();
            float glow = .12F + .88F * hover;
            ref(g, x, y, x + w, y + h, (rw, rh) -> CrateArt.rarityCard(g, 0, 0, rw, rh, accent, dim ? .55F : 1, glow, opacity));
            if (hover > .05F) {
                float sweep = WarehouseMotion.progress(now(), hoverAt, 560);
                if (sweep < 1) CrateArt.sheen(g, x, y, w, h, sweep, hover * opacity);
            }
            float wellBottom = y + h - 16;
            // 图标落在角标行（数量 / 状态）之下，与名称之间居中。
            float wellTop = y + 13;
            float size = Math.min(w * .58F, wellBottom - wellTop - 2) + 2 * hover;
            drawIcon(g, x + w / 2F, (wellTop + wellBottom) / 2F, size, opacity * (dim ? .55F : 1));

            g.pose().pushPose(); g.pose().translate(0, 0, 200);
            String name = trim(getMessage().getString(), w - 6, 1);
            int nameColor = dim ? WarehouseTheme.TEXT_MUTED : GuiFx.mix(WarehouseTheme.TEXT_BODY, WarehouseTheme.TEXT_BRIGHT, hover);
            draw(g, name, Math.round(x + (w - font.width(name)) / 2F), Math.round(y + h - 15), 1,
                    GuiFx.fade(nameColor, opacity), true);
            String quantity = quantity();
            int quantityW = quantity.isEmpty() ? 0 : font.width(quantity);
            if (quantityW > 0) {
                draw(g, quantity, Math.round(x + w - 3 - quantityW), Math.round(y + 3), 1, GuiFx.fade(WarehouseTheme.TEXT_GOLD, opacity), true);
            }
            String tag = tag();
            if (tag != null) {
                int color = role != null ? WarehouseTheme.DANGER : entry.equipped() ? WarehouseTheme.TEAL : CrateArt.GOLD_LINE;
                float tw = font.width(tag) + 13;
                // 放不下文字时只留状态菱形，完整状态在悬停提示与详情面板里。
                boolean label = tw + quantityW + 8 <= w;
                if (label) chip(g, x + 2, y + 2, x + 2 + tw, y + 13, color, opacity);
                CrateFx.diamond(g, x + 7, y + 7.5F, 2.2F, 2.2F, GuiFx.fade(color, opacity), false);
                CrateFx.glow(g, x + 7, y + 7.5F, 6, color, .5F * opacity);
                if (label) draw(g, tag, Math.round(x + 11), Math.round(y + 4), 1,
                        GuiFx.fade(GuiFx.mix(WarehouseTheme.TEXT_BRIGHT, color, .35F), opacity), false);
            }
            g.pose().popPose();
        }

        void drawIcon(GuiGraphics g, float cx, float cy, float size, float opacity) {
            if (size < 4 || opacity < .01F) return;
            if (art != null) {
                g.setColor(1, 1, 1, opacity);
                int pixels = entry.kind().equals("currency") ? 32 : 128;
                int s = Math.round(size);
                g.blit(art, Math.round(cx - s / 2F), Math.round(cy - s / 2F), s, s, 0, 0, pixels, pixels, pixels, pixels);
                g.setColor(1, 1, 1, 1);
            } else {
                CrateArt.item(g, icon, cx, cy, size / 16F, 0, 0, 1, opacity);
            }
        }

        @Override protected void renderWidget(GuiGraphics g, int x, int y, float pt) {
            // 格栅由 render 的统一绘制通道处理（它要带入场/切换动画），控件通道保持空实现。
        }
        @Override public void onClick(double x, double y) { openDetail(this); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            return layout.contains(x, y) && detail == null && !transitioning() && super.mouseClicked(x, y, button);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, getMessage());
            out.add(NarratedElementType.POSITION, text("quantity", entry.count()));
            out.add(NarratedElementType.HINT, kindLabel());
        }
    }
}
