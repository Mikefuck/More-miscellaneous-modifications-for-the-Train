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
import net.minecraft.client.gui.components.Tooltip;
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
 * 账户奖励仓库：分页格栅 + 详情抽屉 + 用卡流程。
 *
 * <p>外观按参考视频（Steam 库存风）重建：背景 {@code #101410} + 顶部等高线 + 网格暗场
 * {@code #1A1D1A}；三行顶部 chrome（主导航 / 次级标签 / 三级筛选 + 右侧排序下拉）；
 * 瓦片解剖为图标井 + 说明条 + 物品名。几何全部由 {@link WarehouseLayout} 从 1920×1080
 * 的实测坐标等比换算。录制噪声（水印 / 字幕 / bilibili 头像栏 / 编码残影 / 鼠标指针）不复刻。</p>
 */
public final class WarehouseScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.warehouse.";

    private enum Page { WAREHOUSE, CARDS, ROLES }
    private enum SortMode { QUALITY, NAME, COUNT }

    /** chrome 控件类型：决定各自的绘制语言（参考视频里的四类元素）。 */
    private enum Kind { ICON, NAV, TAB, PILL, SORT, PRIMARY, GHOST }

    /** 26×26 图标行里的小字形。 */
    private interface Icon {
        void paint(GuiGraphics g, int x, int y, int size, int color, boolean on);
    }

    private final Screen parent;
    private WarehouseLayout layout;
    private Page page = Page.WAREHOUSE;
    private String filter = "all", state = "all", query = "";
    private SortMode sortMode = SortMode.QUALITY;
    private boolean reducedMotion, draggingPreview;
    private float previewYaw, previewPitch;
    private BakedModel measuredSkinModel;
    private SkinGeometry skinGeometry;
    private static final float YAW_SENSITIVITY = 0.03F, PITCH_SENSITIVITY = 0.02F, PITCH_LIMIT = 1.4F;
    private List<WarehouseEntry> inventory = List.of();
    private List<WarehouseRole> roles = List.of();
    private final List<Tile> tiles = new ArrayList<>();
    private List<Tile> outgoing = List.of();
    private final List<Control> controls = new ArrayList<>();
    private final List<Control> navControls = new ArrayList<>();
    private final List<Control> tabControls = new ArrayList<>();
    private final List<Control> stateControls = new ArrayList<>();
    private final List<Control> iconControls = new ArrayList<>();
    private Control sortControl, motionControl, detailBack, detailAction;
    private EditBox search;
    private int searchX, searchY, searchW, searchH;
    private double scroll;
    private long opened = -1, changed = -1000, lastFrame, requestAt;
    /** 每次进入本界面（首次打开或从开箱终端返回）的时间，驱动同一套落位动画。 */
    private long enteredAt = -1;
    /** 前往开箱终端的交接标记，−1 表示没有正在进行的交接。 */
    private long crateDepartAt = -1;
    private String pendingCrate = "";
    private float departFocusX, departFocusY, departDirX, departDirY;
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

    public WarehouseScreen(Screen parent) { super(text("title")); this.parent = parent; }
    private static Component text(String suffix, Object... args) { return Component.translatable(KEY + suffix, args); }
    private static Component translated(String key) { return Component.translatableWithFallback(key, key); }
    private static long now() { return Util.getMillis(); }

    @Override protected void init() {
        layout = WarehouseLayout.of(width, height);
        boolean first = opened < 0;
        if (first) { opened = now(); }
        // 从开箱终端返回时同样播放一次落位动画，保证两个方向的转场风格一致。
        enteredAt = now();
        lastFrame = enteredAt;
        crateDepartAt = -1;
        handedOff = false;
        detail = null;
        rebuildChrome();
        rebuildGrid(false);
        if (first || refreshAfterReturn) { refreshAfterReturn = false; refresh(true); }
    }

    /**
     * 顶部三行 chrome（参考视频 y 20–50 / 66–86 / 113–140）+ 左上角图标行 + 右侧排序下拉。
     */
    private void rebuildChrome() {
        clearWidgets(); controls.clear();
        navControls.clear(); tabControls.clear(); stateControls.clear(); iconControls.clear();

        int navY = layout.navY(), navH = layout.navHeight();
        // 左上角图标行：返回 / 刷新 / 动效 / 关闭（参考视频 x 22、60、98、136 的四个 26×26 字形）
        int iconSize = WarehouseTheme.iconSize(navH);
        int iconHit = iconSize + 4;
        int step = iconHit + 2;
        int ix = Math.max(2, Math.round(22 * layout.scale()));
        iconControls.add(control(ix, navY, iconHit, navH, Kind.ICON, Component.empty(), this::onClose, null, null,
                (g, x, y, s, c, on) -> WarehouseTheme.glyphBack(g, x, y, s, c)));
        iconControls.get(iconControls.size() - 1).setTooltip(Tooltip.create(Component.translatable("gui.back")));
        iconControls.add(control(ix + step, navY, iconHit, navH, Kind.ICON, Component.empty(),
                () -> refresh(true), null, null,
                (g, x, y, s, c, on) -> WarehouseTheme.glyphRefresh(g, x, y, s, c)));
        iconControls.get(iconControls.size() - 1).setTooltip(Tooltip.create(text("refresh")));
        motionControl = control(ix + step * 2, navY, iconHit, navH, Kind.ICON, Component.empty(), () -> {
            reducedMotion = !reducedMotion; outgoing = List.of(); changed = -1000;
            rebuildGrid(false);
        }, null, null, (g, x, y, s, c, on) -> WarehouseTheme.glyphMotion(g, x, y, s, c, on));
        motionControl.setTooltip(Tooltip.create(text("motion_hint")));
        iconControls.add(motionControl);
        iconControls.add(control(ix + step * 3, navY, iconHit, navH, Kind.ICON, Component.empty(), this::onClose, null, null,
                (g, x, y, s, c, on) -> WarehouseTheme.glyphClose(g, x, y, s, c)));
        iconControls.get(iconControls.size() - 1).setTooltip(Tooltip.create(text("close")));

        // 主导航：映射到现有分页（仓库 / 角色卡 / 职业自选），ASCII | 分隔
        Component[] navLabels = { text("warehouse"), text("cards"), text("roles") };
        Page[] navPages = Page.values();
        int navPad = Math.max(5, Math.round(24 * layout.scale()));
        int sep = Math.max(3, Math.round(14 * layout.scale()));
        int total = sep * (navLabels.length - 1);
        int[] navWidth = new int[navLabels.length];
        for (int i = 0; i < navLabels.length; i++) {
            navWidth[i] = font.width(navLabels[i]) + navPad * 2;
            total += navWidth[i];
        }
        int nx = Math.max(layout.margin(), (width - total) / 2);
        for (int i = 0; i < navLabels.length; i++) {
            Page target = navPages[i];
            navControls.add(control(nx, navY, navWidth[i], navH, Kind.NAV, navLabels[i],
                    () -> switchPage(target), target, null, null));
            nx += navWidth[i] + sep;
        }

        // 次级标签：分类过滤（参考视频的 全部 装备 艺术作品 武器箱 …）
        int tabY = layout.tabY(), tabH = layout.tabHeight();
        String[] tabs = page == Page.WAREHOUSE ? new String[]{"all", "currency", "special", "appearance"}
                : page == Page.CARDS ? new String[]{"all", "faction", "self_select", "limit_break"}
                : new String[]{"all", "bound"};
        int tabPad = Math.max(4, Math.round(16 * layout.scale()));
        int tabGap = Math.max(2, Math.round(6 * layout.scale()));
        total = tabGap * (tabs.length - 1);
        int[] tabWidth = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            tabWidth[i] = font.width(text("filter." + tabs[i])) + tabPad * 2;
            total += tabWidth[i];
        }
        searchW = Math.max(56, Math.round(300 * layout.scale()));
        searchH = tabH;
        searchX = layout.margin();
        searchY = tabY;
        int tx = Math.max(searchX + searchW + tabGap, (width - total) / 2);
        if (tx + total > width - layout.margin()) tx = Math.max(searchX + searchW + tabGap, width - layout.margin() - total);
        for (int i = 0; i < tabs.length; i++) {
            String id = tabs[i];
            tabControls.add(control(tx, tabY, tabWidth[i], tabH, Kind.TAB, text("filter." + id), () -> {
                if (filter.equals(id)) return;
                filter = id; scroll = 0; rebuildGrid(true);
            }, null, id, null));
            tx += tabWidth[i] + tabGap;
        }

        // 三级筛选：状态过滤（参考视频的 所有 武器箱 印花胶囊 …），活跃项同一套 teal 药丸
        int filterY = layout.filterY(), filterH = layout.filterHeight();
        String[] states = page == Page.WAREHOUSE ? new String[]{"all", "owned", "usable", "equipped"}
                : page == Page.CARDS ? new String[]{"all", "owned", "usable"}
                : new String[]{"all", "available", "taken"};
        total = tabGap * (states.length - 1);
        int[] stateWidth = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            stateWidth[i] = font.width(text("state." + states[i])) + tabPad * 2;
            total += stateWidth[i];
        }
        int sx = Math.max(layout.margin(), (width - total) / 2);
        for (int i = 0; i < states.length; i++) {
            String id = states[i];
            stateControls.add(control(sx, filterY, stateWidth[i], filterH, Kind.PILL, text("state." + id), () -> {
                if (state.equals(id)) return;
                state = id; scroll = 0; rebuildGrid(true);
            }, null, id, null));
            sx += stateWidth[i] + tabGap;
        }

        // 右侧排序下拉框：field #1A1F1E / 1px #3A4442 / r=4 / ⇅ … ∨
        sortControl = control(layout.sortFieldX(), layout.sortFieldY(), layout.sortFieldWidth(), layout.sortFieldHeight(),
                Kind.SORT, text(switch (sortMode) {
                    case QUALITY -> "quality_sort";
                    case NAME -> "name_sort";
                    case COUNT -> "count_sort";
                }), () -> {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            rebuildChrome(); rebuildGrid(true);
        }, null, null, null);

        // 搜索框（参考视频次级行左侧的放大镜）
        int glyph = WarehouseTheme.iconSize(tabH);
        search = new EditBox(font, searchX + glyph + 5, tabY + (tabH - 8) / 2, Math.max(20, searchW - glyph - 12), 8,
                text("search"));
        search.setBordered(false); search.setMaxLength(96); search.setHint(text("search"));
        search.setValue(query); search.setResponder(value -> { query = value; scroll = 0; rebuildGrid(false); });
        addRenderableWidget(search);

        detailBack = new Control(width - drawerWidth() + 10, 8, 46, 18,
                Kind.GHOST, Component.translatable("gui.back"), this::closeDetail, null, null, null);
        detailAction = new Control(width - drawerWidth() + 12, height - 42, drawerWidth() - 24, 24,
                Kind.PRIMARY, text("use"), this::detailAction, null, null, null);
        addRenderableWidget(detailBack); addRenderableWidget(detailAction);
        detailBack.visible = detailAction.visible = false;
        updateControls();
    }

    private Control control(int x, int y, int w, int h, Kind kind, Component label, Runnable click,
                            Page target, String value, Icon icon) {
        Control c = new Control(x, y, w, h, kind, label, click, target, value, icon);
        controls.add(c);
        return addRenderableWidget(c);
    }

    private void switchPage(Page next) {
        if (next == page || busy()) return;
        switchDirection = next.ordinal() >= page.ordinal() ? 1 : -1;
        captureOutgoing();
        page = next; filter = "all"; state = "all"; query = ""; scroll = 0; detail = null;
        rebuildChrome(); rebuildGrid(false); changed = now();
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
                if ("bound".equals(filter) && !role.isBound()) continue;
                WarehouseEntry entry = new WarehouseEntry("role", role.id, role.displayName(),
                        role.isBound() ? text("bound_to", role.boundDisplayName()).getString() : text("role_hint").getString(),
                        "minecraft:paper", role.taken ? 0 : 1, role.color, false);
                Tile tile = new Tile(entry, role);
                if (stateMatches(tile) && matches(tile)) result.add(tile);
            }
        } else {
            for (WarehouseEntry original : inventory) {
                WarehouseEntry entry = original;
                if ("card".equals(entry.kind()) && cardsKnown) entry = new WarehouseEntry(entry.kind(), entry.id(), entry.name(),
                        entry.description(), entry.icon(), ClientLotteryState.cardBalances.getOrDefault(entry.id(), entry.count()), entry.color(), false);
                if (page == Page.CARDS) {
                    if (!entry.kind().equals("card") || !matchesCardGroup(entry)) continue;
                } else {
                    if (entry.count() <= 0 && !entry.kind().equals("currency")) continue;
                    if (!matchesCategory(entry)) continue;
                }
                Tile tile = new Tile(entry, null);
                if (stateMatches(tile) && matches(tile)) result.add(tile);
            }
        }
        Comparator<Tile> byName = Comparator.comparing(t -> t.getMessage().getString(), String.CASE_INSENSITIVE_ORDER);
        Comparator<Tile> order = switch (sortMode) {
            // Only skins carry a quality. Keep ungraded entries together after the five grades.
            case QUALITY -> Comparator.<Tile>comparingInt(t -> t.entry.kind().equals("skin")
                    ? SkinQuality.values().length - 1 - t.entry.quality().ordinal() : SkinQuality.values().length)
                    .thenComparing(byName);
            case NAME -> byName;
            case COUNT -> Comparator.<Tile>comparingInt(t -> t.entry.count()).reversed().thenComparing(byName);
        };
        result.sort(order);
        tiles.addAll(result);
        for (int i = 0; i < tiles.size(); i++) { tiles.get(i).index = i; addWidget(tiles.get(i)); }
        scroll = Mth.clamp(scroll, 0, layout.maxScroll(tiles.size()));
        positionTiles();
    }

    /** 次级标签（分类）：仓库 -- 货币 / 特殊道具 / 外观收藏。 */
    private boolean matchesCategory(WarehouseEntry entry) {
        return switch (filter) {
            case "currency" -> entry.kind().equals("currency");
            case "special" -> entry.kind().equals("special");
            case "appearance" -> entry.kind().equals("skin") || entry.kind().equals("title");
            default -> true;
        };
    }

    /** 次级标签（分类）：角色卡 -- 阵营卡 / 自选卡 / 突破卡。 */
    private boolean matchesCardGroup(WarehouseEntry entry) {
        return switch (filter) {
            case "self_select" -> entry.id().equals("self_select");
            case "limit_break" -> entry.id().equals("limit_break");
            case "faction" -> !entry.id().equals("self_select") && !entry.id().equals("limit_break");
            default -> true;
        };
    }

    /**
     * 三级筛选（状态）。参考视频这一行是更细的物品类型；这里映射成与分类正交的可用状态，
     * 好让它对货币 / 卡牌 / 特殊道具 / 称号 / 角色五种异构内容都成立。
     */
    private boolean stateMatches(Tile tile) {
        if ("all".equals(state)) return true;
        WarehouseEntry e = tile.entry;
        if (tile.role != null) {
            return switch (state) {
                case "available" -> !tile.role.taken;
                case "taken" -> tile.role.taken;
                case "owned" -> !tile.role.taken;
                default -> true;
            };
        }
        return switch (state) {
            case "owned" -> e.count() > 0 || e.equipped();
            case "usable" -> canUse(e) || e.kind().equals("skin") || e.kind().equals("title")
                    || e.kind().equals("special") && CrateCatalog.isCrateItem(e.id()) && e.count() > 0;
            case "equipped" -> e.equipped();
            default -> true;
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
            // Faction activation uses an explicit confirmation inside the new detail drawer.
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
        if (reducedMotion) return false;
        // 落位动画期间格栅是被缩放绘制的，命中区域却没有缩放，因此同样锁住输入。
        if (ScreenSwap.arriving(now(), enteredAt)) return true;
        return now() - opened < WarehouseMotion.OPEN_MS || now() - changed < WarehouseMotion.SWITCH_MS;
    }

    /**
     * 前往开箱终端。参考视频里仓库 → 开箱是 <b>0ms 硬切</b>（f041→f042，整帧平均亮度 75.8→50.9，
     * 无闪白、无淡出、无划像），所以这里不再播 220ms 压暗 / 缩放 / 淡出：记下交接标记后立刻把
     * Screen 切换交给主线程队列，最快下一帧就完成替换。{@link ScreenSwap} 的调用点保留，
     * 具体语义由它自己决定（该方向的 {@code bridge} 返回 0，因此不会额外画任何压暗）。
     */
    private void beginCrateDepart(String crateId) {
        if (crateDepartAt >= 0) return;
        pendingCrate = crateId;
        crateDepartAt = now();
        handedOff = false;
        // 聚焦点取抽屉中的箱子图标，位移方向由屏幕中心指向它。
        departFocusX = width - drawerWidth() / 2.0F;
        departFocusY = 75.0F + Math.min(128, Math.max(48, height / 4)) / 2.0F;
        float dx = departFocusX - width / 2.0F;
        float dy = departFocusY - height / 2.0F;
        float length = Math.max(1.0F, (float) Math.sqrt(dx * dx + dy * dy));
        departDirX = dx / length;
        departDirY = dy / length;
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

    private void openDetail(Tile tile) {
        if (busy() || transitioning()) return;
        detail = tile; detailAt = now(); detailClosing = false; detailScroll = 0; notice = "";
        draggingPreview = false; previewYaw = previewPitch = 0;
        measuredSkinModel = null; skinGeometry = null;
        setFocused(detailBack); updateControls();
    }

    private void closeDetail() {
        if (busy()) return;
        detailClosing = true; detailAt = now();
        if (reducedMotion) closeDetailImmediately();
    }

    private void closeDetailImmediately() {
        Tile previous = detail;
        detail = null; detailClosing = false; draggingPreview = false;
        detailBack.visible = detailAction.visible = false;
        if (previous != null && tiles.contains(previous)) setFocused(previous); else setFocused(null);
        updateControls();
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
        else if ("special".equals(e.kind()) && CrateCatalog.isCrateItem(e.id())) {
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
            if (page == Page.ROLES) switchPage(Page.CARDS);
            notice = "lobby_only";
        }
        if (ClientLotteryState.cardInventoryVersion != cardEpoch) {
            cardEpoch = ClientLotteryState.cardInventoryVersion; cardsKnown = true;
            if (pendingSince >= 0) {
                pendingSince = -1; closeDetailImmediately();
                if (page == Page.ROLES) switchPage(Page.CARDS);
                notice = "receipt"; refresh(true);
            }
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
        if (submitAt >= 0 && time - submitAt >= (reducedMotion ? 0 : 180)) {
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
        boolean modal = detail != null;
        boolean locked = crateDepartAt >= 0;
        boolean usable = !modal && !busy() && !locked;
        for (Control c : controls) c.active = usable;
        for (Control c : navControls) { c.on = c.page == page; c.active = usable && c.page != page; }
        for (Control c : tabControls) c.on = c.value.equals(filter);
        for (Control c : stateControls) c.on = c.value.equals(state);
        for (Control c : iconControls) c.active = !busy() && !locked;
        if (sortControl != null) { sortControl.on = sortMode == SortMode.QUALITY; sortControl.active = usable; }
        if (motionControl != null) motionControl.glyphOn = !reducedMotion;
        if (search != null) search.active = usable;
        if (detailBack == null) return;
        detailBack.visible = detailAction.visible = modal;
        detailBack.active = modal && !busy() && !detailClosing;
        detailAction.active = false;
        if (modal) {
            var e = detail.entry;
            if (e.kind().equals("card")) {
                detailAction.setMessage(text(busy() ? "pending" : e.id().equals("self_select") ? "choose_role" : "confirm_use"));
                detailAction.active = canUse(e);
            } else if (e.kind().equals("role")) {
                detailAction.setMessage(text(busy() ? "pending" : "confirm_role"));
                detailAction.active = !busy() && !detail.role.taken && cardsKnown && !CardGuiGameState.gameActiveOrStarting()
                        && ClientLotteryState.cardBalances.getOrDefault("self_select", 0) > 0 && ClientLotteryState.cardUseRemainingUses > 0;
            } else {
                boolean appearance = e.kind().equals("skin") || e.kind().equals("title");
                boolean crate = e.kind().equals("special") && CrateCatalog.isCrateItem(e.id());
                detailAction.setMessage(text(appearance ? "wardrobe" : crate ? "open_crate" : "stored"));
                detailAction.active = !busy() && (appearance || crate && e.count() > 0);
            }
            detailAction.active &= !detailClosing && (reducedMotion || now() - detailAt >= 220);
        }
        positionTiles();
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now(); delta = Math.min(80, time - lastFrame); lastFrame = time;
        float open = reducedMotion ? 1 : WarehouseMotion.ease(WarehouseMotion.progress(time, opened, 380));
        WarehouseTheme.background(g, width, height, layout.chromeBottom(), open);
        drawChrome(g, open);
        boolean switching = !reducedMotion && time - changed < WarehouseMotion.SWITCH_MS;
        float progress = switching ? WarehouseMotion.progress(time, changed, WarehouseMotion.SWITCH_MS) : 1;
        // 入场与交接共用 ScreenSwap 的曲线；格栅自身的运动仍然归本界面（参考视频的仓库是静止的）。
        float arrive = reducedMotion ? 1 : ScreenSwap.arrive(time, enteredAt);
        float depart = ScreenSwap.depart(time, crateDepartAt);
        float sceneScale = 1;
        float sceneAlpha = reducedMotion ? 1 : ScreenSwap.arriveFade(arrive);
        float focusX = crateDepartAt >= 0 ? departFocusX : width / 2.0F;
        float focusY = crateDepartAt >= 0 ? departFocusY : height / 2.0F;
        float shift = ScreenSwap.departShift(depart);
        g.enableScissor(layout.x(), layout.top(), layout.x() + layout.width(), layout.bottom());
        g.pose().pushPose();
        if (sceneScale != 1.0F) {
            g.pose().translate(focusX, focusY, 0);
            g.pose().scale(sceneScale, sceneScale, 1);
            g.pose().translate(-focusX, -focusY, 0);
        }
        g.pose().translate(departDirX * shift, departDirY * shift, 0);
        if (switching && progress < .5f) {
            float p = WarehouseMotion.ease(progress * 2);
            g.pose().translate(-switchDirection * 28 * p, 0, 0);
            for (Tile t : outgoing) t.paint(g, -1, -1, (1 - p) * sceneAlpha, 0);
        } else {
            float p = switching ? WarehouseMotion.ease((progress - .5f) * 2) : 1;
            g.pose().translate(switchDirection * 28 * (1 - p), 0, 0);
            int firstRow = Math.max(0, (int) scroll / layout.rowHeight());
            for (Tile t : tiles) if (t.visible) {
                float enter = reducedMotion ? 1 : WarehouseMotion.reveal(time, opened + 100, Math.max(0, t.index - firstRow * layout.columns()));
                t.paint(g, detail == null && !transitioning() ? mx : -1, detail == null && !transitioning() ? my : -1,
                        open * p * enter * sceneAlpha, Math.round((1 - enter) * Math.max(4, layout.pitchY() * .3F)));
            }
        }
        if (tiles.isEmpty() && known && !loading) {
            g.drawCenteredString(font, text("empty"), width / 2, layout.top() + 24, WarehouseTheme.alpha(WarehouseTheme.TEXT, sceneAlpha));
            g.drawCenteredString(font, text(query.isBlank() ? "empty_hint" : "search_empty"), width / 2, layout.top() + 40,
                    WarehouseTheme.alpha(WarehouseTheme.MUTED, sceneAlpha));
        }
        if (!known && loading) {
            for (int i = 0; i < layout.columns(); i++) {
                int x = layout.tileX(i), y = layout.top();
                g.fill(x, y, x + layout.tileWidth(), y + layout.iconHeight(), WarehouseTheme.alpha(0xFF33372F, sceneAlpha));
                g.fill(x, y + layout.iconHeight(), x + layout.tileWidth(), y + layout.iconHeight() + layout.captionHeight(),
                        WarehouseTheme.alpha(WarehouseTheme.CAPTION_BAR, sceneAlpha * .8F));
            }
            g.drawCenteredString(font, text("loading"), width / 2, layout.top() + 24, WarehouseTheme.alpha(WarehouseTheme.TEXT, sceneAlpha));
        }
        g.pose().popPose();
        g.disableScissor();
        drawScrollbar(g);
        drawStatusBar(g, open);
        // Widgets are drawn once. Drawer controls are drawn separately in their translated layer.
        detailBack.visible = detailAction.visible = false;
        super.render(g, detail == null ? mx : -1, detail == null ? my : -1, partialTick);
        detailBack.visible = detailAction.visible = detail != null;
        if (detail != null) drawDetail(g, mx, my, partialTick);
        else if (!transitioning() && layout.contains(mx, my)) {
            for (Tile t : tiles) if (t.visible && t.isMouseOver(mx, my)) {
                var tooltip = new ArrayList<>(font.split(t.getMessage(), Math.min(240, width - 24)));
                tooltip.add(t.captionText().getVisualOrderText());
                if (t.entry.kind().equals("skin")) tooltip.add(SkinQualityStyle.label(t.entry.quality()).getVisualOrderText());
                tooltip.add(text("inspect").getVisualOrderText());
                g.renderTooltip(font, tooltip, mx, my); break;
            }
        }
        // 交接给开箱终端：参考视频此处是 0ms 硬切，ScreenSwap.bridge 在该方向返回 0，不画任何压暗。
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

    /** 顶部三行 chrome：主导航分隔符、搜索框、账号名。控件本体由控件通道绘制。 */
    private void drawChrome(GuiGraphics g, float open) {
        g.hLine(0, width, layout.chromeBottom(), WarehouseTheme.alpha(0x802E332F, open));
        for (int i = 1; i < navControls.size(); i++) {
            Control previous = navControls.get(i - 1), current = navControls.get(i);
            int cx = (previous.getX() + previous.getWidth() + current.getX()) / 2;
            String bar = "|";
            g.drawString(font, bar, cx - font.width(bar) / 2, layout.navY() + (layout.navHeight() - 8) / 2,
                    WarehouseTheme.alpha(0xFF4A5450, open), false);
        }
        int glyph = WarehouseTheme.iconSize(layout.tabHeight());
        WarehouseTheme.field(g, searchX, searchY, searchX + searchW, searchY + searchH, 4, open);
        WarehouseTheme.glyphSearch(g, searchX + 4, searchY + (searchH - glyph) / 2, glyph,
                WarehouseTheme.alpha(WarehouseTheme.TAB_OFF, open));
        String account = minecraft == null || minecraft.player == null ? "" : minecraft.player.getGameProfile().getName();
        if (!account.isEmpty() && width > 420) {
            drawTrim(g, Component.literal(account), width - layout.margin() - 110,
                    layout.navY() + (layout.navHeight() - 8) / 2, 110, WarehouseTheme.alpha(WarehouseTheme.TAB_OFF, open));
        }
    }

    private void drawScrollbar(GuiGraphics g) {
        int max = layout.maxScroll(tiles.size());
        if (max <= 0) return;
        int h = Math.max(16, layout.viewportHeight() * layout.viewportHeight() / (layout.viewportHeight() + max));
        int x = layout.x() + layout.width() - 3;
        int y = layout.top() + (int) ((layout.viewportHeight() - h) * scroll / max);
        g.fill(x, layout.top(), x + 2, layout.bottom(), WarehouseTheme.alpha(0x802E332F, 1));
        g.fill(x, y, x + 2, y + h, WarehouseTheme.alpha(0xFF7A8480, 1));
    }

    /**
     * 底部状态条：借用参考视频结尾那条底部导航的语汇（{@code rgba(0,0,0,0.45)} 遮罩 +
     * 1px 白色发丝线 + 20px {@code #EAEAEA}）；它是现有功能（同步状态 / 错误 / 提示）的落点。
     */
    private void drawStatusBar(GuiGraphics g, float open) {
        int barTop = height - layout.statusHeight();
        g.fill(0, barTop, width, height, WarehouseTheme.alpha(0x73000000, open));
        g.hLine(0, width, barTop, WarehouseTheme.alpha(0x2EFFFFFF, open));
        Component status = !error.isEmpty() ? text(error) : !notice.isEmpty() ? text(notice) : loading ? text("loading")
                : page == Page.WAREHOUSE ? text("asset_count", tiles.size())
                : cardsKnown ? text("quotas", Math.max(0, ClientLotteryState.cardUseRemainingUses), Math.max(0, ClientLotteryState.cardUseRemainingSelfUses)) : text("card_sync");
        int ty = barTop + (layout.statusHeight() - 8) / 2;
        int right = width < 520 ? 0 : Math.min(150, font.width(text("inspect")) + 4);
        drawTrim(g, status, layout.margin(), ty, Math.max(40, width - layout.margin() * 2 - right - 6),
                error.isEmpty() ? WarehouseTheme.alpha(WarehouseTheme.BODY, open) : WarehouseTheme.alpha(WarehouseTheme.WARN, open));
        if (right > 0) {
            drawTrim(g, text("inspect"), width - layout.margin() - right, ty, right,
                    WarehouseTheme.alpha(WarehouseTheme.TAB_OFF, open));
        }
    }

    private int drawerWidth() { return Math.min(280, Math.max(226, width * 2 / 5)); }
    private int detailArtTop() { return Math.max(36, Math.max(20, Math.round(30 * layout.scale())) + 8); }

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
    private void drawSkinModel(GuiGraphics g, ItemStack skin, int x1, int y1, int x2, int y2) {
        if (skin.isEmpty()) return;
        BakedModel model = SkinClient.model(skin, false,
                minecraft.getItemRenderer().getModel(skin, minecraft.level, minecraft.player, 0));
        PreviewFit fit = previewFit(model);
        float size = Math.min((x2 - x1 - 20) / (2 * fit.radius),
                (y2 - y1 - 16) / (2 * fit.radius));
        g.enableScissor(x1, y1 - detailScroll, x2, y2 - detailScroll);
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

    /** 详情抽屉：沿用参考视频确认弹窗的语汇（全屏 ~18% 黑洗 + 实心面板 + 稀有度条 + 绿色主按钮）。 */
    private void drawDetail(GuiGraphics g, int mx, int my, float partialTick) {
        float p = reducedMotion ? 1 : WarehouseMotion.ease(WarehouseMotion.progress(now(), detailAt, detailClosing ? 180 : 240));
        if (detailClosing) p = 1 - p;
        int w = drawerWidth(), x = width - w;
        int rule = Math.max(2, Math.round(3 * layout.scale()));
        // Item rendering adds 150/200 to Z: both the scrim and drawer must cover those items.
        g.pose().pushPose(); g.pose().translate(0, 0, 350);
        g.fill(0, 0, width, height, WarehouseTheme.alpha(0x40000000, p));
        g.pose().popPose();
        g.pose().pushPose(); g.pose().translate((1 - p) * w, 0, 400);
        g.fill(x, 0, width, height, WarehouseTheme.alpha(WarehouseTheme.SURFACE, p));
        g.fill(x, 0, x + 1, height, WarehouseTheme.alpha(WarehouseTheme.BORDER, p));
        g.fill(x + 1, 0, width, rule, WarehouseTheme.alpha(detail.accent, p));
        int artH = Math.min(128, Math.max(44, height / 4));
        int artTop = detailArtTop();
        boolean clip = height - 56 > artTop + 2;
        if (clip) g.enableScissor(x + 2, artTop, width, height - 56);
        g.pose().pushPose(); g.pose().translate(0, -detailScroll, 0);
        if (detail.entry.kind().equals("skin") && !detail.icon.isEmpty()) {
            g.fillGradient(x + 8, artTop, width - 8, artTop + artH,
                    WarehouseTheme.alpha(SkinQualityStyle.top(detail.entry.quality(), 0), p),
                    WarehouseTheme.alpha(SkinQualityStyle.bottom(detail.entry.quality()), p));
            drawSkinModel(g, detail.icon, x + 8, artTop, width - 8, artTop + artH);
        } else {
            detail.drawIcon(g, x + w / 2, artTop + artH / 2, artH - 10, p);
        }
        int y = artTop + artH + 4;
        drawTrim(g, detail.getMessage(), x + 14, y, w - 28, WarehouseTheme.alpha(WarehouseTheme.TITLE, p));
        g.drawString(font, text("quantity", detail.entry.count()), x + 14, y + 14,
                WarehouseTheme.alpha(WarehouseTheme.BODY, p), false);
        String description = translated(detail.entry.description()).getString();
        if (detail.entry.kind().equals("skin")) description = SkinQualityStyle.label(detail.entry.quality()).getString()
                + "\n" + description;
        if (detail.entry.equipped()) description += "\n" + text("equipped").getString();
        if (detail.entry.kind().equals("card")) {
            description += "\n" + text(!cardsKnown ? "card_sync" : CardGuiGameState.gameActiveOrStarting() ? "lobby_only"
                    : detail.entry.count() <= 0 ? "not_owned" : !canUse(detail.entry) && !busy() ? "no_uses" : "cost_one").getString();
        }
        if (detail.role != null && detail.role.taken) description += "\n" + text("taken").getString();
        var wrapped = font.split(Component.literal(description), w - 28);
        for (int i = 0; i < wrapped.size(); i++) g.drawString(font, wrapped.get(i), x + 14, y + 30 + i * 11,
                WarehouseTheme.alpha(WarehouseTheme.BODY, p), false);
        detailMaxScroll = Math.max(0, y + 30 + wrapped.size() * 11 - (height - 76));
        detailScroll = Math.min(detailScroll, detailMaxScroll);
        g.pose().popPose(); if (clip) g.disableScissor();
        if (detailMaxScroll > 0) {
            int sy = artTop + (height - 168) * detailScroll / detailMaxScroll;
            g.fill(width - 4, sy, width - 2, sy + 18, WarehouseTheme.alpha(WarehouseTheme.TEAL, p));
        }
        g.hLine(x + 2, width, height - 52, WarehouseTheme.alpha(WarehouseTheme.BORDER, p));
        if (!notice.isEmpty()) drawTrim(g, text(notice), x + 14, height - 68, w - 28, WarehouseTheme.alpha(WarehouseTheme.WARN, p));
        if (submitAt >= 0) g.fill(x + 12, height - 44, x + 12 + Math.round((w - 24) * WarehouseMotion.progress(now(), submitAt, 180)),
                height - 42, WarehouseTheme.alpha(WarehouseTheme.GREEN, p));
        detailBack.render(g, p == 1 ? mx : -1, p == 1 ? my : -1, partialTick);
        detailAction.render(g, p == 1 ? mx : -1, p == 1 ? my : -1, partialTick);
        g.pose().popPose();
    }

    private void drawTrim(GuiGraphics g, Component value, int x, int y, int w, int color) {
        String raw = value.getString();
        String trimmed = font.width(raw) <= w ? raw : font.plainSubstrByWidth(raw, Math.max(0, w - font.width("…"))) + "…";
        g.drawString(font, trimmed, x, y, color, false);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        if (detail != null) {
            if (busy() || detailClosing || !reducedMotion && now() - detailAt < 240) return true;
            if (detailBack.mouseClicked(x, y, button)) { setFocused(detailBack); return true; }
            if (detailAction.mouseClicked(x, y, button)) { setFocused(detailAction); return true; }
            int artTop = detailArtTop();
            int artH = Math.min(128, Math.max(44, height / 4));
            if (detail.entry.kind().equals("skin") && !detail.icon.isEmpty()
                    && x >= width - drawerWidth() + 8 && x < width - 8
                    && y >= artTop - detailScroll && y < artTop + artH - detailScroll) {
                draggingPreview = true;
                return true;
            }
            if (x < width - drawerWidth()) closeDetail();
            return true;
        }
        if (transitioning() || busy()) return true;
        if (layout.contains(x, y)) {
            if (x >= layout.x() + layout.width() - 6 && layout.maxScroll(tiles.size()) > 0) { dragScroll(y); return true; }
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
        if (button == 0 && detail == null && !busy() && x >= layout.x() + layout.width() - 8 && x <= layout.x() + layout.width()) {
            dragScroll(y); return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && draggingPreview) { draggingPreview = false; return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (detail != null && x >= width - drawerWidth()) {
            detailScroll = Mth.clamp(detailScroll - (int) (vertical * 22), 0, detailMaxScroll); return true;
        }
        if (detail != null || busy() || transitioning() || !layout.contains(x, y)) return false;
        scroll = Mth.clamp(scroll - vertical * layout.rowHeight() * .48, 0, layout.maxScroll(tiles.size()));
        positionTiles(); return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) {
            if (detail != null) { closeDetail(); return true; }
            if (page == Page.ROLES) { switchPage(Page.CARDS); return true; }
            onClose(); return true;
        }
        if (busy() || transitioning()) return true;
        if (detail != null) {
            if (key == 264 || key == 265 || key == 266 || key == 267) {
                detailScroll = Mth.clamp(detailScroll + (key == 264 || key == 267 ? 1 : -1) * (key >= 266 ? 66 : 22), 0, detailMaxScroll); return true;
            }
            if (key == 258) { setFocused(getFocused() == detailBack && detailAction.active ? detailAction : detailBack); return true; }
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

    /** chrome 控件：四种绘制语言（图标 / 主导航 / 药丸标签 / 下拉框 / 主按钮 / 幽灵按钮）。 */
    private final class Control extends AbstractWidget {
        private final Runnable action;
        private final Kind kind;
        private final Page page;
        private final String value;
        private final Icon icon;
        private boolean on, glyphOn;

        Control(int x, int y, int w, int h, Kind kind, Component message, Runnable action,
                Page page, String value, Icon icon) {
            super(x, y, w, h, message);
            this.kind = kind; this.action = action; this.page = page; this.value = value; this.icon = icon;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = width, h = height;
            boolean hover = isHoveredOrFocused() && active;
            switch (kind) {
                case ICON -> {
                    if (hover) GuiFx.roundRect(g, x, y, x + w, y + h, 3, 0x22FFFFFF);
                    int s = Math.max(6, Math.min(w, h) - 4);
                    int color = active ? WarehouseTheme.NAV_OFF : WarehouseTheme.alpha(WarehouseTheme.TAB_OFF, .6F);
                    if (icon != null) icon.paint(g, x + (w - s) / 2, y + (h - s) / 2, s, color, glyphOn);
                }
                case NAV -> {
                    centered(g, on ? WarehouseTheme.TEAL : WarehouseTheme.NAV_OFF, x, w, y, h, hover);
                    if (on) g.fill(x + 1, y + h - 1, x + w - 1, y + h, WarehouseTheme.TEAL);
                }
                case TAB, PILL -> {
                    if (on) WarehouseTheme.pill(g, x, y, x + w, y + h, 1F);
                    else if (hover) GuiFx.roundRect(g, x, y, x + w, y + h, 3, 0x1AFFFFFF);
                    centered(g, on ? 0xFF101410 : WarehouseTheme.TAB_OFF, x, w, y, h, hover);
                }
                case SORT -> {
                    WarehouseTheme.field(g, x, y, x + w, y + h, 4, active ? 1F : .6F);
                    int s = Math.max(6, h - 8);
                    int gy = y + (h - s) / 2;
                    WarehouseTheme.glyphSort(g, x + 6, gy, s, WarehouseTheme.TAB_OFF);
                    WarehouseTheme.glyphCaretDown(g, x + w - 6 - s / 2, gy + 1, s, WarehouseTheme.TAB_OFF);
                    drawTrim(g, getMessage(), x + 6 + s + 4, y + (h - 8) / 2, Math.max(8, w - s * 2 - 20),
                            WarehouseTheme.NAV_OFF);
                }
                case PRIMARY -> {
                    GuiFx.roundRect(g, x, y, x + w, y + h, 3, active ? WarehouseTheme.GREEN : 0xFF2E332F);
                    if (hover) GuiFx.roundRect(g, x, y, x + w, y + h, 3, 0x24FFFFFF);
                    centered(g, active ? 0xFFFFFFFF : WarehouseTheme.MUTED, x, w, y, h, false);
                }
                case GHOST -> {
                    if (hover) GuiFx.roundRect(g, x, y, x + w, y + h, 3, 0x1AFFFFFF);
                    GuiFx.roundOutline(g, x, y, x + w, y + h, 3, active ? 0x59FFFFFF : 0x33FFFFFF);
                    centered(g, active ? 0xFFE0E0E0 : WarehouseTheme.MUTED, x, w, y, h, false);
                }
            }
            if (isFocused() && kind != Kind.PRIMARY) {
                GuiFx.roundOutline(g, x, y, x + w, y + h, 3, WarehouseTheme.alpha(WarehouseTheme.TEAL, .55F));
            }
        }

        private void centered(GuiGraphics g, int color, int x, int w, int y, int h, boolean hover) {
            int tint = hover && kind != Kind.PRIMARY ? GuiFx.mix(color, 0xFFFFFFFF, .25F) : color;
            String s = font.plainSubstrByWidth(getMessage().getString(), Math.max(1, w - 4));
            g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, tint, false);
        }

        @Override public void onClick(double x, double y) { if (active) action.run(); }
        @Override public boolean keyPressed(int key, int scan, int mods) {
            if (active && (key == 257 || key == 335 || key == 32)) { action.run(); return true; } return false;
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    private final class Tile extends AbstractWidget {
        final WarehouseEntry entry;
        final WarehouseRole role;
        final ItemStack icon;
        final ResourceLocation art;
        final int accent;
        int index;
        float hover;
        Tile(WarehouseEntry entry, WarehouseRole role) {
            super(0, 0, layout.tileWidth(), layout.tileHeight(), entryName(entry));
            this.entry = entry; this.role = role;
            accent = 0xFF000000 | (entry.kind().equals("skin") ? entry.quality().color()
                    : entry.kind().equals("card") ? WarehouseTheme.cardColor(entry.id()) : entry.color());
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

        /**
         * 说明条的文案。参考视频这里是「锁形图标 + 可租赁」；仓库内容异构，于是换成有意义的
         * 等价状态：卡牌「可领取 / 已拥有」、箱子「可开启 / 已消耗」、角色「可自选 / 已被占用」。
         */
        Component captionText() {
            if (role != null) return text(role.taken ? "taken" : "caption.selectable");
            return switch (entry.kind()) {
                case "card" -> text(canUse(entry) ? "caption.claimable"
                        : entry.count() > 0 ? "caption.owned" : "caption.locked");
                case "special" -> CrateCatalog.isCrateItem(entry.id())
                        ? text(entry.count() > 0 ? "caption.openable" : "caption.spent")
                        : text(entry.count() > 0 ? "caption.owned" : "caption.locked");
                case "skin", "title" -> text(entry.equipped() ? "equipped" : "caption.owned");
                default -> text(entry.count() > 0 ? "caption.owned" : "caption.locked");
            };
        }

        /** 锁形图标：灰色 = 不可操作，浅灰 = 可操作（§1 的 locked / openable 语义）。 */
        boolean captionOpen() {
            if (role != null) return !role.taken;
            return switch (entry.kind()) {
                case "card" -> canUse(entry);
                case "special" -> CrateCatalog.isCrateItem(entry.id()) && entry.count() > 0;
                case "skin", "title" -> true;
                default -> false;
            };
        }

        Component quantityText() {
            if (role != null) return Component.empty();
            return entry.kind().equals("currency") || entry.count() > 1
                    ? text("quantity_compact", entry.count()) : Component.empty();
        }

        void paint(GuiGraphics g, int mx, int my, float opacity, int drop) {
            if (opacity < .01) return;
            hover = GuiFx.approach(hover, isMouseOver(mx, my) || isFocused() && detail == null ? 1 : 0, delta, 90);
            int x = getX(), y = getY() + drop;
            int wellH = layout.iconHeight(), capH = layout.captionHeight();
            float scale = layout.scale();
            // Skin quality colors the whole item well; ungraded rewards retain the neutral palette.
            if (entry.kind().equals("skin")) {
                g.fillGradient(x, y, x + width, y + wellH,
                        WarehouseTheme.alpha(SkinQualityStyle.top(entry.quality(), hover), opacity),
                        WarehouseTheme.alpha(SkinQualityStyle.bottom(entry.quality()), opacity));
                g.hLine(x, x + width, y, WarehouseTheme.alpha(entry.quality().color(), opacity));
                g.fill(x, y + Math.max(2, wellH - 2), x + width, y + wellH,
                        WarehouseTheme.alpha(GuiFx.mix(WarehouseTheme.WELL_EDGE, entry.quality().color(), .48F), opacity));
            } else {
                g.fill(x, y, x + width, y + wellH, WarehouseTheme.alpha(WarehouseTheme.WELL, opacity));
                g.hLine(x, x + width, y, WarehouseTheme.alpha(WarehouseTheme.WELL_TOP, opacity));
                g.fill(x, y + Math.max(2, wellH - 2), x + width, y + wellH, WarehouseTheme.alpha(WarehouseTheme.WELL_EDGE, opacity));
            }
            if (hover > .02F) g.fill(x, y, x + width, y + wellH, WarehouseTheme.alpha(0x1FFFFFFF, opacity * hover));
            int margin = Math.max(2, Math.round(10 * scale));
            drawIcon(g, x + width / 2, y + wellH / 2,
                    Math.max(8, Math.min(width, wellH) - margin * 2 + Math.round(hover * 2)), opacity);
            // 说明条：12×12 锁形图标 + 文案 + 右端数量
            int capY = y + wellH;
            g.fill(x, capY, x + width, capY + capH, WarehouseTheme.alpha(WarehouseTheme.CAPTION_BAR, opacity));
            int glyph = Math.max(6, Math.min(capH - 2, Math.round(12 * scale)));
            int gx = x + Math.max(2, Math.round(8 * scale));
            boolean open = captionOpen();
            WarehouseTheme.glyphLock(g, gx, capY + (capH - glyph) / 2, glyph,
                    WarehouseTheme.alpha(open ? 0xFFC8CFCB : 0xFF8A9490, opacity), open);
            int textX = gx + glyph + 3;
            int pad = Math.max(2, Math.round(8 * scale));
            int textY = capY + (capH - 8) / 2;
            drawTrim(g, captionText(), textX, textY, Math.max(8, width - (textX - x) - pad),
                    WarehouseTheme.alpha(WarehouseTheme.CAPTION_TEXT, opacity));
            // 数量徽标落在图标井右下角：参考视频的武器箱没有堆叠数量，这是为异构内容补的信息。
            Component quantity = quantityText();
            if (!quantity.getString().isEmpty()) {
                int quantityWidth = font.width(quantity) + 4;
                g.fill(x + width - quantityWidth - 1, y + wellH - 10, x + width - 1, y + wellH - 1,
                        WarehouseTheme.alpha(0xCC101410, opacity));
                g.drawString(font, quantity.getString(), x + width - quantityWidth + 1, y + wellH - 9,
                        WarehouseTheme.alpha(WarehouseTheme.TEXT, opacity), false);
            }
            // 物品名 18px #959492
            drawTrim(g, getMessage(), x + Math.max(1, Math.round(4 * scale)),
                    capY + capH + Math.max(0, (layout.nameHeight() - 9) / 2), width - 2,
                    WarehouseTheme.alpha(WarehouseTheme.NAME, opacity));
            if (hover > .05F || isFocused()) {
                GuiFx.roundOutline(g, x, y, x + width, y + wellH + capH, 0,
                        WarehouseTheme.alpha(accent, opacity * Math.max(.5F, hover)));
            }
        }

        void drawIcon(GuiGraphics g, int cx, int cy, int size, float opacity) {
            g.pose().pushPose();
            if (art != null) {
                g.setColor(1, 1, 1, opacity);
                int pixels = entry.kind().equals("currency") ? 32 : 128;
                g.blit(art, cx - size / 2, cy - size / 2, size, size, 0, 0, pixels, pixels, pixels, pixels);
                g.setColor(1, 1, 1, 1);
            } else {
                float scale = size / 16f;
                g.pose().translate(cx - size / 2f, cy - size / 2f, 0); g.pose().scale(scale, scale, 1);
                g.renderFakeItem(icon, 0, 0);
            }
            g.pose().popPose();
        }
        @Override protected void renderWidget(GuiGraphics g, int x, int y, float pt) {
            // 格栅由 render 的统一绘制通道处理（它要带入场/切换/交接动画），
            // 控件通道若再画一次会把动画整体盖掉，因此这里保持空实现。
        }
        @Override public void onClick(double x, double y) { openDetail(this); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            return layout.contains(x, y) && detail == null && !transitioning() && super.mouseClicked(x, y, button);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, getMessage());
            out.add(NarratedElementType.POSITION, text("quantity", entry.count()));
            out.add(NarratedElementType.HINT, entry.kind().equals("skin")
                    ? SkinQualityStyle.label(entry.quality()).copy().append(" · ").append(text("inspect")) : captionText());
        }
    }
}
