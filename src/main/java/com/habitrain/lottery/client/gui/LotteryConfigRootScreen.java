package com.habitrain.lottery.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.config.GrantsConfig;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.config.PoolConfigModels;
import com.habitrain.lottery.config.RatesConfig;
import com.habitrain.lottery.config.ThemeConfig;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.client.gui.config.ConfigSectionId;
import com.habitrain.lottery.client.gui.config.PlayerAssetFilter;
import com.habitrain.lottery.client.gui.config.PlayerAssetsViewState;
import com.habitrain.lottery.client.gui.config.PlayerCardScrollLayout;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.network.PlayerAdminModels;
import com.habitrain.lottery.skin.SkinContentBootstrap;
import com.habitrain.lottery.title.TitleCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OP-gated config UI: visual editors, player chance admin, multiline JSON, server world backup.
 */
public class LotteryConfigRootScreen extends Screen {
    private static final String[] TABS = {
            "奖池配置", "经济倍率", "奖励规则", "画面主题", "玩家资产",
            "皮肤内容", "撰写邮件", "称号管理", "高级 JSON"
    };
    private static final ConfigSectionId[] SECTION_IDS = {
            ConfigSectionId.POOLS, ConfigSectionId.RATES, ConfigSectionId.GRANTS,
            ConfigSectionId.THEME, ConfigSectionId.PLAYERS, ConfigSectionId.SKINS,
            ConfigSectionId.MAIL, ConfigSectionId.TITLES, ConfigSectionId.JSON
    };
    /** Visual order in the grouped navigation rail. */
    private static final int[] NAV_ORDER = {0, 1, 2, 3, 4, 7, 5, 6, 8};
    private static final int ROW_H = 22;
    /** Tab indices after inserting 「称号」 before JSON. */
    private static final int TAB_PLAYERS = 4;
    private static final int TAB_SKINS = 5;
    private static final int TAB_MAIL = 6;
    private static final int TAB_TITLES = 7;
    private static final int TAB_JSON = 8;

    private final Screen parent;
    private int selectedTab;
    private String status = "";
    private int[] tabX;
    private int[] tabY;
    private int[] tabW;
    private Button applyButton;
    private Button saveButton;

    private MultilineTextArea jsonArea;
    private int jsonSection; // 0 all, 1 pools, 2 rates, 3 grants, 4 theme

    // pools
    private int selectedPoolIndex;
    private int selectedBandIndex;
    private EditBox poolNameBox;
    private EditBox poolTypeBox;
    private EditBox poolIdBox;
    private EditBox poolCoverBox;
    private EditBox bandProbBox;
    private EditBox bandItemsBox;

    // rates
    private final ScrollablePanel ratesPanel = new ScrollablePanel();
    private EditBox drawCostBox;
    private EditBox dupCoinFlatBox;
    private EditBox dupCoinBox;
    private EditBox coinPerDrawBox;
    private EditBox loginCapBox;
    private EditBox blackoutMulBox;
    private EditBox murderMulBox;
    private EditBox repairMulBox;
    private EditBox opLevelBox;

    // grants
    private int selectedGrantIndex;
    private EditBox grantIdBox;
    private EditBox grantAmountBox;
    private EditBox grantModesBox;

    // theme
    private final ScrollablePanel themePanel = new ScrollablePanel();
    private final List<EditBox> themeBgBoxes = new ArrayList<>();

    // players
    private int selectedPlayerIndex;
    /** UUID of last selected player; used to preserve selection across search filter rebuilds. */
    private String selectedPlayerUuid = "";
    private EditBox bulkDeltaBox;
    private EditBox singleDeltaBox;
    private EditBox singleSetBox;
    private EditBox playerSearchBox;
    private String playerSearchQuery = "";
    private boolean playerCardsPage;
    private boolean cardMutationPending;
    private int cardMutationPendingTicks;
    private final PlayerAssetsViewState playerAssetsViewState = new PlayerAssetsViewState();
    private final Map<String, EditBox> cardStepBoxes = new LinkedHashMap<>();
    private final Map<String, EditBox> cardSetBoxes = new LinkedHashMap<>();
    private final List<AbstractWidget> cardEditWidgets = new ArrayList<>();
    private final ScrollablePanel playerCardPanel = new ScrollablePanel();
    private PlayerCardScrollLayout playerCardScrollLayout;
    private int lastSeenPlayerListVersion = -1;
    private int lastSeenConfigVersion = -1;
    private int lastSeenTitleVersion = -1;

    // titles (catalog + per-player owned)
    private TitleCatalog workingTitleCatalog = new TitleCatalog();
    private int selectedTitleCatalogIndex;
    private int selectedTitleOwnedIndex;
    private int selectedTitlePlayerIndex;
    private String selectedTitlePlayerUuid = "";
    private EditBox titleIdBox;
    private EditBox titleDisplayBox;
    private EditBox titleCustomBox;
    private EditBox titlePlayerSearchBox;
    private String titlePlayerSearchQuery = "";

    // windowed side-lists (scroll survives rebuildTabContent)
    private final ScrollableButtonList poolList = new ScrollableButtonList();
    private final ScrollableButtonList bandList = new ScrollableButtonList();
    private final ScrollableButtonList grantList = new ScrollableButtonList();
    private final ScrollableButtonList playerList = new ScrollableButtonList();
    private final ScrollableButtonList titleCatalogList = new ScrollableButtonList();
    private final ScrollableButtonList titlePlayerList = new ScrollableButtonList();
    private final ScrollableButtonList titleOwnedList = new ScrollableButtonList();

    private Button poolMoveUpBtn;
    private Button poolMoveDownBtn;

    private final List<AbstractWidget> tabWidgets = new ArrayList<>();

    /**
     * 1.21 {@link Screen#render} always calls {@link #renderBackground} again.
     * We paint tabs/labels before {@code super.render}, so a second blur pass would
     * cover them while widgets (drawn after that pass) stay sharp. Skip the nested one.
     */
    private boolean suppressNestedBackground;

    public LotteryConfigRootScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_lottery.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        layoutTabs();
        LotteryConfigService.get().ensureClientDefaults();
        tabWidgets.clear();
        themeBgBoxes.clear();
        jsonArea = null;

        rebuildTabContent();
        buildFooter();

        if (LotteryClientNetwork.clientRequestSnapshot()) {
            status = LotteryNetwork.ClientLotteryState.op ? "已连接：OP 可保存/管理" : "已连接：非 OP 只读";
            if (LotteryNetwork.ClientLotteryState.op) {
                LotteryClientNetwork.clientRequestPlayerList();
            }
        } else {
            status = "离线浏览本地默认配置；进服后可同步/保存";
            LotteryNetwork.ClientLotteryState.op = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (cardMutationPending && ++cardMutationPendingTicks >= 200) {
            cardMutationPending = false;
            cardMutationPendingTicks = 0;
            status = Component.translatable("screen.habitrain_lottery.config.cards.timeout").getString();
            rebuildTabContent();
        }
        // Server snapshot arrived (save/reload/join) — rebuild editors from memory config.
        if (LotteryNetwork.ClientLotteryState.configVersion != lastSeenConfigVersion) {
            lastSeenConfigVersion = LotteryNetwork.ClientLotteryState.configVersion;
            if (LotteryNetwork.ClientLotteryState.hasSnapshot) {
                rebuildTabContent();
                if (status == null || status.isBlank() || status.startsWith("已提交") || status.startsWith("已请求")) {
                    status = "已同步服务器配置到界面";
                }
            }
        }
        // refresh player / titles tab when list arrives
        if ((selectedTab == TAB_PLAYERS || selectedTab == TAB_TITLES)
                && LotteryNetwork.ClientLotteryState.playerListVersion != lastSeenPlayerListVersion) {
            lastSeenPlayerListVersion = LotteryNetwork.ClientLotteryState.playerListVersion;
            cardMutationPending = false;
            cardMutationPendingTicks = 0;
            rebuildTabContent();
        }
        // title snapshot arrived — refresh working catalog + lists on titles tab
        if (LotteryNetwork.ClientLotteryState.titleVersion != lastSeenTitleVersion) {
            lastSeenTitleVersion = LotteryNetwork.ClientLotteryState.titleVersion;
            loadWorkingTitleCatalogFromState();
            if (selectedTab == TAB_TITLES) {
                rebuildTabContent();
            }
        }
        // status comes from AdminActionResultS2C → lastAdminMessage
        if (LotteryNetwork.ClientLotteryState.lastAdminMessage != null
                && !LotteryNetwork.ClientLotteryState.lastAdminMessage.isBlank()) {
            status = LotteryNetwork.ClientLotteryState.lastAdminMessage;
            LotteryNetwork.ClientLotteryState.lastAdminMessage = "";
            if (cardMutationPending && selectedTab == TAB_PLAYERS) {
                cardMutationPending = false;
                cardMutationPendingTicks = 0;
                rebuildTabContent();
            }
        }
    }

    private ConfigConsoleLayout consoleLayout() {
        return ConfigConsoleLayout.calculate(width, height);
    }

    private int pageLeft() {
        return consoleLayout().content().x();
    }

    private int pageRight() {
        return consoleLayout().content().right();
    }

    private int pageWidth() {
        return Math.max(120, pageRight() - pageLeft());
    }

    private int pageTop() {
        ConfigConsoleLayout.Rect content = consoleLayout().content();
        return Math.min(content.bottom(), content.y() + 30);
    }

    private int pageBottom() {
        return consoleLayout().content().bottom();
    }

    private boolean isNarrowPlayerDetail() {
        return selectedTab == TAB_PLAYERS
                && consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW
                && playerAssetsViewState.narrowPage() == PlayerAssetsViewState.NarrowPage.DETAIL;
    }

    private boolean pageHasSaveAction() {
        return selectedTab == 0 || selectedTab == 1 || selectedTab == 2 || selectedTab == 3
                || selectedTab == TAB_TITLES || selectedTab == TAB_JSON;
    }

    private void buildFooter() {
        ConfigConsoleLayout layout = consoleLayout();
        int by = layout.footer().y() + Math.max(2, (layout.footer().height() - 20) / 2);
        int x = pageLeft();
        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.refresh"), b -> {
            if (LotteryClientNetwork.clientRequestSnapshot()) {
                status = "已请求服务器快照";
                if (LotteryNetwork.ClientLotteryState.op) {
                    LotteryClientNetwork.clientRequestPlayerList();
                }
            } else {
                status = "未连接服务器，显示本地配置";
            }
            rebuildTabContent();
        }).bounds(x, by, 42, 20)
                .tooltip(Tooltip.create(Component.literal("重新读取服务器配置与管理数据")))
                .build());

        applyButton = addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.apply"), b -> {
            if (!applyCurrentTab()) {
                return;
            }
            status = "本页已应用到内存";
            rebuildTabContent();
        }).bounds(x + 45, by, 42, 20)
                .tooltip(Tooltip.create(Component.literal("只应用到客户端内存，不写入服务器磁盘")))
                .build());

        saveButton = addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.save"), b -> {
            if (!LotteryClientNetwork.canSendPlay()) {
                status = "未连接服务器，无法保存";
                return;
            }
            if (!LotteryNetwork.ClientLotteryState.op) {
                status = "需要 OP";
                return;
            }
            if (!applyCurrentTab()) {
                return;
            }
            if (selectedTab == TAB_TITLES) {
                if (LotteryClientNetwork.clientSaveTitleCatalog(
                        LotteryConfigService.GSON.toJson(workingTitleCatalog))) {
                    status = "已提交称号模板库保存（OP）";
                } else {
                    status = "称号模板保存发送失败";
                }
                return;
            }
            if (LotteryClientNetwork.clientSave(LotteryConfigService.get().exportAllJson())) {
                status = "已提交保存（OP）";
            } else {
                status = "保存发送失败";
            }
        }).bounds(x + 90, by, 90, 20)
                .tooltip(Tooltip.create(Component.literal("应用当前页并保存完整配置；仅服务器 OP 可用")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.reload"), b -> {
            if (!LotteryClientNetwork.canSendPlay()) {
                status = "未连接服务器";
                return;
            }
            if (!LotteryNetwork.ClientLotteryState.op) {
                status = "需要 OP";
                return;
            }
            if (LotteryClientNetwork.clientReload()) {
                status = "已请求重载";
            } else {
                status = "重载发送失败";
            }
        }).bounds(x + 183, by, 58, 20)
                .tooltip(Tooltip.create(Component.literal("放弃内存修改，重新载入服务器磁盘配置")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.back"), b -> onClose())
                .bounds(pageRight() - 48, by, 48, 20).build());
        updateFooterState();
    }

    private void updateFooterState() {
        boolean visible = pageHasSaveAction();
        if (applyButton != null) {
            applyButton.visible = visible;
        }
        if (saveButton != null) {
            saveButton.visible = visible;
        }
    }

    private void clearTabWidgets() {
        for (AbstractWidget w : tabWidgets) {
            removeWidget(w);
        }
        tabWidgets.clear();
        themeBgBoxes.clear();
        jsonArea = null;
        poolNameBox = poolTypeBox = poolIdBox = poolCoverBox = bandProbBox = bandItemsBox = null;
        poolMoveUpBtn = poolMoveDownBtn = null;
        drawCostBox = dupCoinFlatBox = dupCoinBox = coinPerDrawBox = loginCapBox = blackoutMulBox = murderMulBox = repairMulBox = opLevelBox = null;
        grantIdBox = grantAmountBox = grantModesBox = null;
        bulkDeltaBox = singleDeltaBox = singleSetBox = playerSearchBox = null;
        cardStepBoxes.clear();
        cardSetBoxes.clear();
        cardEditWidgets.clear();
        playerCardScrollLayout = null;
        titleIdBox = titleDisplayBox = titleCustomBox = null;
        titlePlayerSearchBox = null;
    }

    private <T extends AbstractWidget> T addTab(T w) {
        tabWidgets.add(w);
        addRenderableWidget(w);
        return w;
    }

    private void rebuildTabContent() {
        clearTabWidgets();
        int contentY = pageTop();
        int contentH = Math.max(ROW_H, pageBottom() - contentY);
        boolean editable = canEdit();

        switch (selectedTab) {
            case 0 -> buildPoolsTab(contentY, contentH);
            case 1 -> buildRatesTab(contentY, contentH);
            case 2 -> buildGrantsTab(contentY, contentH);
            case 3 -> buildThemeTab(contentY, contentH);
            case 4 -> buildPlayersTab(contentY, contentH);
            case 5 -> buildSkinsTab(contentY, contentH);
            case 6 -> buildMailTab(contentY, contentH);
            case 7 -> buildTitlesTab(contentY, contentH);
            case 8 -> buildJsonTab(contentY, contentH, editable);
            default -> {
            }
        }
        applyOpWidgetLocks();
        refreshPlayerCardLocks();
        refreshDuplicateCoinFieldState();
        refreshPoolMoveButtonStates();
        updateFooterState();
    }

    private boolean canEdit() {
        if (!LotteryClientNetwork.canSendPlay()) {
            return true;
        }
        return LotteryNetwork.ClientLotteryState.op;
    }

    private void applyOpWidgetLocks() {
        if (selectedTab == TAB_SKINS) {
            return;
        }
        boolean onlineNonOp = LotteryClientNetwork.canSendPlay() && !LotteryNetwork.ClientLotteryState.op;
        if (!onlineNonOp) {
            for (AbstractWidget w : tabWidgets) {
                w.active = true;
            }
            if (jsonArea != null) {
                jsonArea.setEditable(true);
            }
            return;
        }
        for (AbstractWidget w : tabWidgets) {
            if (w instanceof Button b) {
                String msg = b.getMessage().getString();
                if (msg.startsWith("池#") || msg.startsWith("带#") || msg.startsWith("事件#")
                        || msg.startsWith("§a") || msg.startsWith("§7") || msg.startsWith("·")
                        || msg.equals("浏览") || msg.startsWith("全部") || msg.equals("奖池")
                        || msg.equals("倍率") || msg.equals("发次") || msg.equals("画面")
                        || msg.equals("刷新列表") || msg.startsWith("JSON")
                        || msg.startsWith("● ") || msg.startsWith("○ ")
                        || msg.startsWith("模板 ") || msg.startsWith("拥有 ")
                        || msg.equals("刷新称号") || msg.equals("刷新玩家")
                        || msg.equals(Component.translatable(
                        "screen.habitrain_lottery.config.players.back_to_list").getString())) {
                    w.active = true;
                    continue;
                }
            }
            if (w instanceof MultilineTextArea) {
                w.active = true;
                ((MultilineTextArea) w).setEditable(false);
                continue;
            }
            // Search boxes are read-only navigation aids — keep usable for non-OP viewers.
            if (w == playerSearchBox || w == titlePlayerSearchBox) {
                w.active = true;
                continue;
            }
            w.active = false;
        }
    }

    // ---------------- pools ----------------
    private void buildPoolsTab(int y, int h) {
        int pageX = pageLeft();
        int pageR = pageRight();
        PoolConfigModels.Root root = LotteryConfigService.get().getPools();
        if (root.Pools == null) {
            root.Pools = new ArrayList<>();
        }
        if (root.Pools.isEmpty()) {
            root.Pools.add(newDefaultPool(0));
        }
        selectedPoolIndex = Mth.clamp(selectedPoolIndex, 0, root.Pools.size() - 1);
        PoolConfigModels.Pool pool = root.Pools.get(selectedPoolIndex);
        if (pool.QualityListGroup == null) {
            pool.QualityListGroup = new ArrayList<>();
        }
        if (pool.QualityListGroup.isEmpty()) {
            pool.QualityListGroup.add(newDefaultBand(1.0, List.of("coin")));
        }
        selectedBandIndex = Mth.clamp(selectedBandIndex, 0, pool.QualityListGroup.size() - 1);

        int leftW = 120;
        int poolViewportH = Math.max(ROW_H, h - 48);
        List<String> poolLabels = new ArrayList<>(root.Pools.size());
        for (int i = 0; i < root.Pools.size(); i++) {
            PoolConfigModels.Pool p = root.Pools.get(i);
            poolLabels.add((i == selectedPoolIndex ? "§a" : "") + "池#" + p.PoolID + " " + shortName(p.PoolName, 8));
        }
        poolList.setBounds(pageX, y, leftW, poolViewportH);
        poolList.setItems(poolLabels, selectedPoolIndex);
        poolList.setOnSelect(idx -> {
            selectedPoolIndex = idx;
            selectedBandIndex = 0;
            rebuildTabContent();
        });
        poolList.rebuildWidgets(this::addTab, this::applyPoolFieldsToModel);

        int by = poolList.viewportBottom() + 4;
        int poolBtnW = 26;
        int poolBtnGap = 4;
        int poolBtnX = pageX;
        addTab(Button.builder(Component.literal("+池"), b -> {
            applyPoolFieldsToModel();
            PoolConfigModels.Pool np = newDefaultPool(nextPoolId(root));
            root.Pools.add(np);
            selectedPoolIndex = root.Pools.size() - 1;
            selectedBandIndex = 0;
            status = "已添加奖池 #" + np.PoolID;
            poolList.setItems(buildPoolLabels(root), selectedPoolIndex);
            poolList.ensureSelectedVisible();
            rebuildTabContent();
        }).bounds(poolBtnX, by, poolBtnW, 20).build());
        poolBtnX += poolBtnW + poolBtnGap;
        addTab(Button.builder(Component.literal("-池"), b -> {
            if (root.Pools.size() <= 1) {
                status = "至少保留 1 个奖池";
                return;
            }
            root.Pools.remove(selectedPoolIndex);
            selectedPoolIndex = Mth.clamp(selectedPoolIndex - 1, 0, root.Pools.size() - 1);
            selectedBandIndex = 0;
            status = "已删除奖池";
            rebuildTabContent();
        }).bounds(poolBtnX, by, poolBtnW, 20).build());
        poolBtnX += poolBtnW + poolBtnGap;
        poolMoveUpBtn = addTab(Button.builder(Component.literal("↑"), b -> moveSelectedPool(-1))
                .bounds(poolBtnX, by, poolBtnW, 20)
                .tooltip(Tooltip.create(Component.literal("上移当前奖池（交换位置与 PoolID）")))
                .build());
        poolBtnX += poolBtnW + poolBtnGap;
        poolMoveDownBtn = addTab(Button.builder(Component.literal("↓"), b -> moveSelectedPool(1))
                .bounds(poolBtnX, by, poolBtnW, 20)
                .tooltip(Tooltip.create(Component.literal("下移当前奖池（交换位置与 PoolID）")))
                .build());

        int formX = pageX + leftW + 10;
        int formW = pageR - formX;
        boolean compactForm = formW < 300;
        int fy = y;
        addTab(Button.builder(Component.literal(pool.Enable ? "启用:开" : "启用:关"), b -> {
            pool.Enable = !pool.Enable;
            b.setMessage(Component.literal(pool.Enable ? "启用:开" : "启用:关"));
        }).bounds(formX, fy, 70, 20).build());

        poolIdBox = addTab(new EditBox(font, formX + 76, fy, 44, 20, Component.literal("id")));
        poolIdBox.setValue(String.valueOf(pool.PoolID));
        poolIdBox.setHint(Component.literal("ID"));
        poolIdBox.setTooltip(Tooltip.create(Component.literal("排序用 PoolID（侧边栏按此升序）")));

        poolCoverBox = addTab(new EditBox(font, formX + 124, fy, 44, 20, Component.literal("cover")));
        poolCoverBox.setValue(String.valueOf(pool.resolvedCoverId()));
        poolCoverBox.setHint(Component.literal("封面"));
        poolCoverBox.setTooltip(Tooltip.create(Component.literal(
                "封面 CoverID → pool_bg{N}.png；调序时不随 PoolID 交换")));

        int nameX = compactForm ? formX : formX + 172;
        int nameY = compactForm ? fy + 24 : fy;
        int nameW = compactForm ? formW : Math.max(80, formW - 172);
        poolNameBox = addTab(new EditBox(font, nameX, nameY, nameW, 20, Component.literal("name")));
        poolNameBox.setValue(nullToEmpty(pool.PoolName));
        poolNameBox.setHint(Component.literal("名称"));

        fy += compactForm ? 48 : 24;
        poolTypeBox = addTab(new EditBox(font, formX, fy, formW, 20, Component.literal("type")));
        poolTypeBox.setValue(nullToEmpty(pool.PoolType));
        poolTypeBox.setHint(Component.literal("类型 PoolType"));

        fy += 26;
        int bandListW = compactForm ? 72 : 90;
        int bandViewportH = Math.max(ROW_H, y + h - fy - 28);
        List<String> bandLabels = new ArrayList<>(pool.QualityListGroup.size());
        for (int i = 0; i < pool.QualityListGroup.size(); i++) {
            PoolConfigModels.QualityBand bandRow = pool.QualityListGroup.get(i);
            bandLabels.add((i == selectedBandIndex ? "§a" : "") + "带#" + i + " " +
                    String.format(Locale.ROOT, "%.0f%%", (bandRow.Probability == null ? 0 : bandRow.Probability) * 100));
        }
        bandList.setBounds(formX, fy, bandListW, bandViewportH);
        bandList.setItems(bandLabels, selectedBandIndex);
        bandList.setOnSelect(bi -> {
            selectedBandIndex = bi;
            rebuildTabContent();
        });
        bandList.rebuildWidgets(this::addTab, this::applyPoolFieldsToModel);

        int bby = bandList.viewportBottom() + 2;
        addTab(Button.builder(Component.literal("+带"), b -> {
            applyPoolFieldsToModel();
            pool.QualityListGroup.add(newDefaultBand(0.1, List.of("knife/anubis")));
            selectedBandIndex = pool.QualityListGroup.size() - 1;
            bandList.setItems(buildBandLabels(pool), selectedBandIndex);
            bandList.ensureSelectedVisible();
            rebuildTabContent();
        }).bounds(formX, bby, 42, 20).build());
        addTab(Button.builder(Component.literal("-带"), b -> {
            if (pool.QualityListGroup.size() <= 1) {
                status = "至少保留 1 个品质带";
                return;
            }
            applyPoolFieldsToModel();
            pool.QualityListGroup.remove(selectedBandIndex);
            selectedBandIndex = Mth.clamp(selectedBandIndex - 1, 0, pool.QualityListGroup.size() - 1);
            rebuildTabContent();
        }).bounds(formX + 46, bby, 42, 20).build());

        PoolConfigModels.QualityBand band = pool.QualityListGroup.get(selectedBandIndex);
        int detailX = formX + bandListW + 8;
        int detailW = pageR - detailX;
        bandProbBox = addTab(new EditBox(font, detailX, fy, compactForm ? detailW : 70, 20, Component.literal("prob")));
        bandProbBox.setValue(band.Probability == null ? "0.1" : String.valueOf(band.Probability));
        bandProbBox.setHint(Component.literal("概率 0-1"));
        bandProbBox.setTooltip(Tooltip.create(Component.literal("该品质带概率，启用奖池品质带之和需≈1.0")));

        addTab(Button.builder(Component.literal("均分概率"), b -> {
            applyPoolFieldsToModel();
            int n = pool.QualityListGroup.size();
            double each = 1.0 / n;
            for (PoolConfigModels.QualityBand qb : pool.QualityListGroup) {
                qb.Probability = each;
            }
            status = "已均分概率";
            rebuildTabContent();
        }).bounds(compactForm ? detailX : detailX + 76, compactForm ? fy + 24 : fy,
                compactForm ? detailW : 70, 20).build());

        bandItemsBox = addTab(new EditBox(font, detailX, fy + (compactForm ? 48 : 24),
                detailW, 20, Component.literal("items")));
        bandItemsBox.setMaxLength(20000);
        bandItemsBox.setValue(String.join(",", band.ItemList == null ? List.of() : band.ItemList));
        bandItemsBox.setHint(Component.literal("条目 type/id,逗号分隔 或 coin"));
    }

    private List<String> buildPoolLabels(PoolConfigModels.Root root) {
        List<String> labels = new ArrayList<>();
        if (root.Pools == null) {
            return labels;
        }
        for (int i = 0; i < root.Pools.size(); i++) {
            PoolConfigModels.Pool p = root.Pools.get(i);
            labels.add((i == selectedPoolIndex ? "§a" : "") + "池#" + p.PoolID + " " + shortName(p.PoolName, 8));
        }
        return labels;
    }

    /**
     * Swap selected pool with neighbor: list order + PoolID (player UI sorts by PoolID).
     * CoverID stays on the pool object so sketch art follows content, not the slot.
     */
    private void moveSelectedPool(int delta) {
        if (delta != -1 && delta != 1) {
            return;
        }
        applyPoolFieldsToModel();
        PoolConfigModels.Root root = LotteryConfigService.get().getPools();
        if (root.Pools == null || root.Pools.size() < 2) {
            status = "至少需要 2 个奖池才能排序";
            return;
        }
        // Pin covers before PoolID swap; otherwise null CoverID would re-bind to the new ID.
        PoolConfigModels.ensureCoverIds(root);
        selectedPoolIndex = Mth.clamp(selectedPoolIndex, 0, root.Pools.size() - 1);
        int from = selectedPoolIndex;
        int to = from + delta;
        if (to < 0 || to >= root.Pools.size()) {
            status = delta < 0 ? "已到顶" : "已到底";
            refreshPoolMoveButtonStates();
            return;
        }
        PoolConfigModels.Pool a = root.Pools.get(from);
        PoolConfigModels.Pool b = root.Pools.get(to);
        root.Pools.set(from, b);
        root.Pools.set(to, a);
        int tmpId = a.PoolID;
        a.PoolID = b.PoolID;
        b.PoolID = tmpId;
        // CoverID intentionally not swapped — art follows pool content.
        selectedPoolIndex = to;
        status = delta < 0 ? "已上移奖池（PoolID 已换，封面保留）" : "已下移奖池（PoolID 已换，封面保留）";
        poolList.setItems(buildPoolLabels(root), selectedPoolIndex);
        poolList.ensureSelectedVisible();
        rebuildTabContent();
    }

    private void refreshPoolMoveButtonStates() {
        if (poolMoveUpBtn == null && poolMoveDownBtn == null) {
            return;
        }
        boolean editable = canEdit();
        PoolConfigModels.Root root = LotteryConfigService.get().getPools();
        int n = root.Pools == null ? 0 : root.Pools.size();
        int idx = n == 0 ? 0 : Mth.clamp(selectedPoolIndex, 0, n - 1);
        if (poolMoveUpBtn != null) {
            poolMoveUpBtn.active = editable && idx > 0;
        }
        if (poolMoveDownBtn != null) {
            poolMoveDownBtn.active = editable && n > 1 && idx < n - 1;
        }
    }

    private List<String> buildBandLabels(PoolConfigModels.Pool pool) {
        List<String> labels = new ArrayList<>();
        if (pool.QualityListGroup == null) {
            return labels;
        }
        for (int i = 0; i < pool.QualityListGroup.size(); i++) {
            PoolConfigModels.QualityBand bandRow = pool.QualityListGroup.get(i);
            labels.add((i == selectedBandIndex ? "§a" : "") + "带#" + i + " " +
                    String.format(Locale.ROOT, "%.0f%%", (bandRow.Probability == null ? 0 : bandRow.Probability) * 100));
        }
        return labels;
    }

    private PoolConfigModels.Pool newDefaultPool(int id) {
        PoolConfigModels.Pool p = new PoolConfigModels.Pool();
        p.PoolID = id;
        p.CoverID = id;
        p.Enable = true;
        p.PoolName = "新奖池" + id;
        p.PoolType = "weapon";
        p.QualityListGroup = new ArrayList<>();
        p.QualityListGroup.add(newDefaultBand(1.0, List.of("coin")));
        return p;
    }

    private PoolConfigModels.QualityBand newDefaultBand(double prob, List<String> items) {
        PoolConfigModels.QualityBand band = new PoolConfigModels.QualityBand();
        band.Probability = prob;
        band.ItemList = new ArrayList<>(items);
        return band;
    }

    private int nextPoolId(PoolConfigModels.Root root) {
        int max = -1;
        for (PoolConfigModels.Pool p : root.Pools) {
            max = Math.max(max, p.PoolID);
        }
        return max + 1;
    }

    private void applyPoolFieldsToModel() {
        PoolConfigModels.Root root = LotteryConfigService.get().getPools();
        if (root.Pools == null || root.Pools.isEmpty()) {
            return;
        }
        selectedPoolIndex = Mth.clamp(selectedPoolIndex, 0, root.Pools.size() - 1);
        PoolConfigModels.Pool pool = root.Pools.get(selectedPoolIndex);
        if (poolIdBox != null) {
            try {
                pool.PoolID = Integer.parseInt(poolIdBox.getValue().trim());
            } catch (Exception ignored) {
            }
        }
        if (poolCoverBox != null) {
            try {
                pool.CoverID = Integer.parseInt(poolCoverBox.getValue().trim());
            } catch (Exception ignored) {
            }
        } else if (pool.CoverID == null) {
            pool.CoverID = pool.PoolID;
        }
        if (poolNameBox != null) {
            pool.PoolName = poolNameBox.getValue();
        }
        if (poolTypeBox != null) {
            pool.PoolType = poolTypeBox.getValue();
        }
        if (pool.QualityListGroup == null || pool.QualityListGroup.isEmpty()) {
            return;
        }
        selectedBandIndex = Mth.clamp(selectedBandIndex, 0, pool.QualityListGroup.size() - 1);
        PoolConfigModels.QualityBand band = pool.QualityListGroup.get(selectedBandIndex);
        if (bandProbBox != null) {
            try {
                band.Probability = Double.parseDouble(bandProbBox.getValue().trim());
            } catch (Exception ignored) {
            }
        }
        if (bandItemsBox != null) {
            List<String> items = new ArrayList<>();
            for (String part : bandItemsBox.getValue().split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    items.add(part.trim());
                }
            }
            band.ItemList = items;
        }
    }

    // ---------------- rates ----------------
    private void buildRatesTab(int y, int h) {
        RatesConfig rates = LotteryConfigService.get().getRates();
        String[][] rows = {
                {"抽次消耗倍率", String.valueOf(rates.drawCostMultiplier)},
                {"重复转币固定值", String.valueOf(rates.duplicateCoinFlat)},
                {"重复转币倍率", String.valueOf(rates.duplicateCoinMultiplier)},
                {"金币换抽价格", String.valueOf(rates.coinPerDraw)},
                {"连登奖励上限", String.valueOf(rates.loginRewardCap)},
                {"blackout 发次倍率", String.valueOf(rates.modeMultiplier("blackout"))},
                {"murder 发次倍率", String.valueOf(rates.modeMultiplier("murder"))},
                {"repair 发次倍率", String.valueOf(rates.modeMultiplier("repair"))},
                {"OP 权限等级", String.valueOf(rates.opPermissionLevel)},
        };
        int rowH = 36;
        int contentH = rows.length * rowH;
        ratesPanel.setBounds(pageLeft(), y, pageWidth(), h);
        ratesPanel.setContentHeight(contentH);

        int x = pageLeft();
        int w = Math.min(260, pageWidth());
        EditBox[] boxes = new EditBox[rows.length];
        for (int i = 0; i < rows.length; i++) {
            int cy = i * rowH;
            int sy = ratesPanel.applyY(cy);
            EditBox box = labeledField(x, sy, w, rows[i][0], rows[i][1]);
            box.visible = sy + 20 > y && sy < y + h;
            boxes[i] = box;
        }
        drawCostBox = boxes[0];
        dupCoinFlatBox = boxes[1];
        dupCoinBox = boxes[2];
        coinPerDrawBox = boxes[3];
        loginCapBox = boxes[4];
        blackoutMulBox = boxes[5];
        murderMulBox = boxes[6];
        repairMulBox = boxes[7];
        opLevelBox = boxes[8];
        dupCoinFlatBox.setTooltip(Tooltip.create(Component.literal(
                "重复皮肤返还的固定金币。>0 时覆盖倍率，倍率输入会被禁用")));
        dupCoinFlatBox.setResponder(s -> refreshDuplicateCoinFieldState());
        refreshDuplicateCoinFieldState();
    }

    private void refreshDuplicateCoinFieldState() {
        if (dupCoinBox == null) {
            return;
        }
        int flat = 0;
        if (dupCoinFlatBox != null) {
            try {
                flat = (int) Math.round(Double.parseDouble(dupCoinFlatBox.getValue().trim()));
            } catch (Exception e) {
                flat = LotteryConfigService.get().getRates().duplicateCoinFlat();
            }
        }
        boolean flatWins = flat > 0;
        boolean editable = canEdit();
        dupCoinBox.active = editable && !flatWins;
        dupCoinBox.setEditable(editable && !flatWins);
        dupCoinBox.setTooltip(Tooltip.create(Component.literal(flatWins
                ? "duplicateCoinFlat>0：固定值优先，倍率不生效"
                : "flat=0 时按 SRE 基础金币 × 该倍率返还")));
    }

    private EditBox labeledField(int x, int y, int w, String label, String value) {
        EditBox box = addTab(new EditBox(font, x, y + 12, w, 20, Component.literal(label)));
        box.setValue(value);
        box.setHint(Component.literal(label));
        return box;
    }

    private void applyRatesFields() {
        RatesConfig rates = LotteryConfigService.get().getRates();
        rates.drawCostMultiplier = parseD(drawCostBox, rates.drawCostMultiplier);
        rates.duplicateCoinFlat = Math.max(0, (int) Math.round(parseD(dupCoinFlatBox, rates.duplicateCoinFlat)));
        rates.duplicateCoinMultiplier = parseD(dupCoinBox, rates.duplicateCoinMultiplier);
        rates.coinPerDraw = Math.max(1, (int) Math.round(parseD(coinPerDrawBox, rates.coinPerDraw)));
        rates.loginRewardCap = Math.max(1, (int) Math.round(parseD(loginCapBox, rates.loginRewardCap)));
        if (rates.modeMultipliers == null) {
            rates.modeMultipliers = new java.util.HashMap<>();
        }
        rates.modeMultipliers.put("blackout", parseD(blackoutMulBox, 1.0));
        rates.modeMultipliers.put("murder", parseD(murderMulBox, 1.0));
        rates.modeMultipliers.put("repair", parseD(repairMulBox, 1.0));
        rates.opPermissionLevel = (int) parseD(opLevelBox, rates.opPermissionLevel);
    }

    // ---------------- grants ----------------
    private void buildGrantsTab(int y, int h) {
        int pageX = pageLeft();
        int pageR = pageRight();
        GrantsConfig grants = LotteryConfigService.get().getGrants();
        if (grants.events == null) {
            grants.events = new ArrayList<>();
        }
        if (grants.events.isEmpty()) {
            GrantsConfig.GrantEvent e = new GrantsConfig.GrantEvent();
            e.id = "sre_participate";
            e.amount = 1;
            e.enabled = true;
            e.modes = new ArrayList<>(List.of("*"));
            grants.events.add(e);
        }
        selectedGrantIndex = Mth.clamp(selectedGrantIndex, 0, grants.events.size() - 1);

        int leftW = 140;
        int grantViewportH = Math.max(ROW_H, h - 48);
        List<String> grantLabels = new ArrayList<>(grants.events.size());
        for (int i = 0; i < grants.events.size(); i++) {
            GrantsConfig.GrantEvent e = grants.events.get(i);
            grantLabels.add((i == selectedGrantIndex ? "§a" : "") + (e.enabled ? "" : "§7")
                    + shortName(e.id, 14) + " +" + e.amount);
        }
        grantList.setBounds(pageX, y, leftW, grantViewportH);
        grantList.setItems(grantLabels, selectedGrantIndex);
        grantList.setOnSelect(idx -> {
            selectedGrantIndex = idx;
            rebuildTabContent();
        });
        grantList.rebuildWidgets(this::addTab, this::applyGrantFields);

        int by = grantList.viewportBottom() + 4;
        addTab(Button.builder(Component.literal("+事件"), b -> {
            applyGrantFields();
            GrantsConfig.GrantEvent e = new GrantsConfig.GrantEvent();
            e.id = "custom_event";
            e.amount = 1;
            e.enabled = true;
            e.modes = new ArrayList<>(List.of("*"));
            grants.events.add(e);
            selectedGrantIndex = grants.events.size() - 1;
            grantList.setItems(buildGrantLabels(grants), selectedGrantIndex);
            grantList.ensureSelectedVisible();
            rebuildTabContent();
        }).bounds(pageX, by, 50, 20).build());
        addTab(Button.builder(Component.literal("-事件"), b -> {
            if (grants.events.size() <= 1) {
                status = "至少保留 1 个事件";
                return;
            }
            grants.events.remove(selectedGrantIndex);
            selectedGrantIndex = Mth.clamp(selectedGrantIndex - 1, 0, grants.events.size() - 1);
            rebuildTabContent();
        }).bounds(pageX + 54, by, 50, 20).build());

        GrantsConfig.GrantEvent e = grants.events.get(selectedGrantIndex);
        int fx = pageX + leftW + 10;
        int fw = pageR - fx;
        int fy = y;
        addTab(Button.builder(Component.literal(e.enabled ? "启用:开" : "启用:关"), b -> {
            e.enabled = !e.enabled;
            b.setMessage(Component.literal(e.enabled ? "启用:开" : "启用:关"));
        }).bounds(fx, fy, 70, 20).build());
        fy += 24;
        grantIdBox = addTab(new EditBox(font, fx, fy, fw, 20, Component.literal("id")));
        grantIdBox.setValue(nullToEmpty(e.id));
        grantIdBox.setHint(Component.literal("事件 ID"));
        fy += 24;
        grantAmountBox = addTab(new EditBox(font, fx, fy, 80, 20, Component.literal("amount")));
        grantAmountBox.setValue(String.valueOf(e.amount));
        grantAmountBox.setHint(Component.literal("次数"));
        fy += 24;
        grantModesBox = addTab(new EditBox(font, fx, fy, fw, 20, Component.literal("modes")));
        grantModesBox.setValue(e.modes == null ? "*" : String.join(",", e.modes));
        grantModesBox.setHint(Component.literal("模式 blackout,murder,repair 或 *"));
    }

    private List<String> buildGrantLabels(GrantsConfig grants) {
        List<String> labels = new ArrayList<>();
        if (grants.events == null) {
            return labels;
        }
        for (int i = 0; i < grants.events.size(); i++) {
            GrantsConfig.GrantEvent e = grants.events.get(i);
            labels.add((i == selectedGrantIndex ? "§a" : "") + (e.enabled ? "" : "§7")
                    + shortName(e.id, 14) + " +" + e.amount);
        }
        return labels;
    }

    private void applyGrantFields() {
        GrantsConfig grants = LotteryConfigService.get().getGrants();
        if (grants.events == null || grants.events.isEmpty()) {
            return;
        }
        selectedGrantIndex = Mth.clamp(selectedGrantIndex, 0, grants.events.size() - 1);
        GrantsConfig.GrantEvent e = grants.events.get(selectedGrantIndex);
        if (grantIdBox != null) {
            e.id = grantIdBox.getValue().trim();
        }
        if (grantAmountBox != null) {
            try {
                e.amount = Integer.parseInt(grantAmountBox.getValue().trim());
            } catch (Exception ignored) {
            }
        }
        if (grantModesBox != null) {
            List<String> modes = new ArrayList<>();
            for (String p : grantModesBox.getValue().split("[,;\\s]+")) {
                if (!p.isBlank()) {
                    modes.add(p.trim());
                }
            }
            e.modes = modes.isEmpty() ? new ArrayList<>(List.of("*")) : modes;
        }
    }

    // ---------------- theme ----------------
    private void buildThemeTab(int y, int h) {
        ThemeConfig theme = LotteryConfigService.get().getTheme();
        if (theme.qualityBackgrounds == null) {
            theme.qualityBackgrounds = new ArrayList<>();
        }
        while (theme.qualityBackgrounds.size() < 6) {
            theme.qualityBackgrounds.add("noellesroles:textures/gui/loot/common_skin.png");
        }
        String[] labels = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "UNBELIEVABLE"};
        int rowH = 34;
        themePanel.setBounds(pageLeft(), y, pageWidth(), h);
        themePanel.setContentHeight(labels.length * rowH);
        for (int i = 0; i < 6; i++) {
            int sy = themePanel.applyY(i * rowH);
            EditBox box = addTab(new EditBox(font, pageLeft(), sy + 12, pageWidth(), 20, Component.literal(labels[i])));
            box.setMaxLength(256);
            box.setValue(theme.qualityBackgrounds.get(i));
            box.setHint(Component.literal(labels[i] + " 背景 RL"));
            box.visible = sy + 32 > y && sy < y + h;
            themeBgBoxes.add(box);
        }
    }

    private void applyThemeFields() {
        ThemeConfig theme = LotteryConfigService.get().getTheme();
        if (theme.qualityBackgrounds == null) {
            theme.qualityBackgrounds = new ArrayList<>();
        }
        theme.qualityBackgrounds.clear();
        for (EditBox box : themeBgBoxes) {
            theme.qualityBackgrounds.add(box.getValue().trim());
        }
    }

    // ---------------- players ----------------
    /** Filtered player rows for the admin list (name contains query, case-insensitive). */
    private List<PlayerAdminModels.PlayerRow> currentPlayerRows() {
        List<PlayerAdminModels.PlayerRow> players = LotteryNetwork.ClientLotteryState.playerList == null
                ? List.of()
                : LotteryNetwork.ClientLotteryState.playerList.players;
        return PlayerAssetFilter.filter(players, playerSearchQuery);
    }

    private void buildPlayersTab(int y, int h) {
        int pageX = pageLeft();
        int pageR = pageRight();
        boolean narrow = consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW;
        // Remember selection by UUID so search/filter rebuilds don't re-point at a different player.
        // selectedPlayerIndex is relative to the *filtered* list, so resolve UUID from that list.
        // Note: when the search responder updates playerSearchQuery then rebuilds, selectedPlayerUuid
        // was already captured from the pre-filter list in the responder.
        String keepUuid = selectedPlayerUuid;
        List<PlayerAdminModels.PlayerRow> players = currentPlayerRows();
        boolean narrowDetail = narrow
                && playerAssetsViewState.narrowPage() == PlayerAssetsViewState.NarrowPage.DETAIL
                && !players.isEmpty();
        if ((keepUuid == null || keepUuid.isBlank())
                && selectedPlayerIndex >= 0
                && selectedPlayerIndex < players.size()) {
            PlayerAdminModels.PlayerRow prev = players.get(selectedPlayerIndex);
            if (prev != null && prev.uuid != null && !prev.uuid.isBlank()) {
                keepUuid = prev.uuid;
            }
        }

        int topWidgetsStart = tabWidgets.size();
        int refreshW = 64;
        int backupX = pageX + refreshW + 4;
        int backupW = 64;
        int bulkX = backupX + backupW + 8;
        int bulkW = 36;
        int actionsX = bulkX + bulkW + 4;
        int actionW = Math.max(32, Math.min(52, (pageR - actionsX - 8) / 3));

        addTab(Button.builder(Component.literal("刷新列表"), b -> {
            if (!LotteryClientNetwork.clientRequestPlayerList()) {
                status = "未连接或无权限";
            } else {
                status = "已请求玩家列表";
            }
        }).bounds(pageX, y, refreshW, 20).build());

        addTab(Button.builder(Component.literal("新建备份"), b -> {
            if (!LotteryNetwork.ClientLotteryState.op) {
                status = "需要 OP";
                return;
            }
            if (!LotteryClientNetwork.clientRequestBackup()) {
                status = "备份请求失败/未连接";
            } else {
                status = "已请求服务端新建备份…";
            }
        }).bounds(backupX, y, backupW, 20).build());

        bulkDeltaBox = addTab(new EditBox(font, bulkX, y, bulkW, 20, Component.literal("bulk")));
        bulkDeltaBox.setValue("1");
        bulkDeltaBox.setHint(Component.literal("±N"));

        addTab(Button.builder(Component.literal("全员+N"), b -> {
            int n = (int) parseD(bulkDeltaBox, 1);
            if (!LotteryClientNetwork.clientModifyChance("add_all_online", "", n)) {
                status = "需要进服且为 OP";
            }
        }).bounds(actionsX, y, actionW, 20).build());
        addTab(Button.builder(Component.literal("全员-N"), b -> {
            int n = (int) parseD(bulkDeltaBox, 1);
            if (!LotteryClientNetwork.clientModifyChance("add_all_online", "", -Math.abs(n))) {
                status = "需要进服且为 OP";
            }
        }).bounds(actionsX + actionW + 4, y, actionW, 20).build());
        addTab(Button.builder(Component.literal("全员= N"), b -> {
            int n = (int) parseD(bulkDeltaBox, 0);
            if (!LotteryClientNetwork.clientModifyChance("set_all_online", "", n)) {
                status = "需要进服且为 OP";
            }
        }).bounds(actionsX + (actionW + 4) * 2, y, actionW, 20).build());

        playerSearchBox = addTab(new EditBox(font, pageX, y + 24, Math.min(180, pageWidth() / 3), 18, Component.literal("搜索")));
        playerSearchBox.setMaxLength(64);
        playerSearchBox.setValue(playerSearchQuery == null ? "" : playerSearchQuery);
        playerSearchBox.setHint(Component.translatable("screen.habitrain_lottery.config.players.search"));
        playerSearchBox.setResponder(s -> {
            String next = s == null ? "" : s;
            if (next.equals(playerSearchQuery)) {
                return;
            }
            // Capture currently selected player's UUID from the pre-filter list before rebuild.
            List<PlayerAdminModels.PlayerRow> before = currentPlayerRows();
            if (selectedPlayerIndex >= 0 && selectedPlayerIndex < before.size()) {
                PlayerAdminModels.PlayerRow row = before.get(selectedPlayerIndex);
                if (row != null && row.uuid != null) {
                    selectedPlayerUuid = row.uuid;
                }
            }
            playerSearchQuery = next;
            if (narrow) {
                playerAssetsViewState.setNarrowPage(PlayerAssetsViewState.NarrowPage.LIST);
            }
            rebuildTabContent();
            if (playerSearchBox != null) {
                playerSearchBox.setFocused(true);
                setFocused(playerSearchBox);
                playerSearchBox.setCursorPosition(playerSearchQuery.length());
            }
        });

        if (narrowDetail) {
            for (int i = topWidgetsStart; i < tabWidgets.size(); i++) {
                tabWidgets.get(i).visible = false;
            }
        }

        int listTop = narrowDetail ? consoleLayout().content().y() + 4 : y + 46;
        int listH = Math.max(ROW_H, pageBottom() - listTop);
        final int playerCount = players.size();
        if (playerCount > 0) {
            int restored = PlayerAssetFilter.indexOfUuid(players, keepUuid);
            selectedPlayerIndex = restored >= 0 ? restored : 0;
            PlayerAdminModels.PlayerRow selRow = players.get(selectedPlayerIndex);
            selectedPlayerUuid = selRow != null && selRow.uuid != null ? selRow.uuid : "";
        } else {
            selectedPlayerIndex = 0;
        }

        int listW = narrow ? pageWidth() : Math.min(260, pageWidth() / 2);
        List<String> playerLabels = new ArrayList<>(playerCount);
        for (int i = 0; i < playerCount; i++) {
            PlayerAdminModels.PlayerRow row = players.get(i);
            String mark = i == selectedPlayerIndex ? "§a" : (row.online ? "§f" : "§7");
            playerLabels.add(mark + (row.online ? "● " : "○ ") + shortName(row.name, 12)
                    + "  抽:" + row.lootChance + " 币:" + row.coinNum);
        }
        playerList.setBounds(pageX, listTop, listW, listH);
        playerList.setItems(playerLabels, selectedPlayerIndex);
        playerList.setOnSelect(fi -> {
            selectedPlayerIndex = fi;
            List<PlayerAdminModels.PlayerRow> rows = currentPlayerRows();
            if (fi >= 0 && fi < rows.size()) {
                PlayerAdminModels.PlayerRow row = rows.get(fi);
                selectedPlayerUuid = row != null && row.uuid != null ? row.uuid : "";
                if (narrow) {
                    playerAssetsViewState.setNarrowPage(PlayerAssetsViewState.NarrowPage.DETAIL);
                }
            }
            rebuildTabContent();
        });
        boolean showNarrowDetail = narrowDetail;
        if (!showNarrowDetail) {
            playerList.rebuildWidgets(this::addTab, null);
        }

        if (narrow && !showNarrowDetail) {
            if (players.isEmpty()) {
                List<PlayerAdminModels.PlayerRow> allPlayers = LotteryNetwork.ClientLotteryState.playerList == null
                        ? List.of()
                        : LotteryNetwork.ClientLotteryState.playerList.players;
                boolean hasAny = allPlayers != null && !allPlayers.isEmpty();
                Component empty = Component.translatable(hasAny
                        ? "screen.habitrain_lottery.config.players.no_match"
                        : "screen.habitrain_lottery.config.players.no_data");
                addTab(Button.builder(empty, b -> {
                            if (!hasAny) {
                                LotteryClientNetwork.clientRequestPlayerList();
                            }
                        }).bounds(pageX, listTop, Math.min(pageWidth(), 220), 20).build());
            }
            return;
        }

        int px = narrow ? pageX : pageX + listW + 20;
        int pw = pageR - px;
        int py = listTop;
        if (!players.isEmpty()) {
            PlayerAdminModels.PlayerRow sel = players.get(selectedPlayerIndex);
            playerAssetsViewState.beginCardPlayer(sel.uuid);
            int tabX = px;
            int tabY = py + 42;
            int tabW = Math.max(64, Math.min(92, (pw - 4) / 2));
            if (narrow) {
                int backW = Math.min(92, Math.max(72, pw / 4));
                Component backLabel = Component.literal("‹ " + shortName(sel.name, 8));
                addTab(Button.builder(backLabel, b -> {
                            playerAssetsViewState.setNarrowPage(PlayerAssetsViewState.NarrowPage.LIST);
                            rebuildTabContent();
                        }).bounds(px, py, backW, 20)
                        .tooltip(Tooltip.create(Component.translatable(
                                "screen.habitrain_lottery.config.players.back_to_list"))).build());
                tabX = px + backW + 4;
                tabY = py;
                tabW = Math.max(48, (Math.max(0, pw - backW - 8)) / 2);
            }
            addTab(Button.builder(Component.literal((playerCardsPage ? "○ " : "● ")
                            + Component.translatable("screen.habitrain_lottery.config.players.base_assets").getString()), b -> {
                        playerCardsPage = false;
                        rebuildTabContent();
                    }).bounds(tabX, tabY, tabW, 20).build());
            addTab(Button.builder(Component.literal((playerCardsPage ? "● " : "○ ")
                            + Component.translatable("screen.habitrain_lottery.config.players.role_cards").getString()), b -> {
                        playerCardsPage = true;
                        playerAssetsViewState.setCardScroll(0);
                        rebuildTabContent();
                    }).bounds(tabX + tabW + 4, tabY, tabW, 20).build());

            if (playerCardsPage) {
                buildPlayerCardControls(sel, px, narrow ? py + 24 : py + 70, pw);
            } else {
                int baseY = narrow ? py + 24 : py + 72;
                singleDeltaBox = addTab(new EditBox(font, px, baseY, 50, 20, Component.literal("d")));
                singleDeltaBox.setValue("1");
                singleDeltaBox.setHint(Component.literal("±"));

                addTab(Button.builder(Component.literal("此人+"), b -> {
                    int n = (int) parseD(singleDeltaBox, 1);
                    LotteryClientNetwork.clientModifyChance("add_one", sel.uuid, Math.abs(n));
                }).bounds(px + 54, baseY, 40, 20).build());
                addTab(Button.builder(Component.literal("此人-"), b -> {
                    int n = (int) parseD(singleDeltaBox, 1);
                    LotteryClientNetwork.clientModifyChance("add_one", sel.uuid, -Math.abs(n));
                }).bounds(px + 98, baseY, 40, 20).build());

                singleSetBox = addTab(new EditBox(font, px, baseY + 26, 50, 20, Component.literal("set")));
                singleSetBox.setValue(String.valueOf(sel.lootChance));
                singleSetBox.setHint(Component.literal("="));
                addTab(Button.builder(Component.literal("设为"), b -> {
                    int n = (int) parseD(singleSetBox, sel.lootChance);
                    LotteryClientNetwork.clientModifyChance("set_one", sel.uuid, n);
                }).bounds(px + 54, baseY + 26, 50, 20).build());
            }
        } else {
            List<PlayerAdminModels.PlayerRow> allPlayers = LotteryNetwork.ClientLotteryState.playerList == null
                    ? List.of()
                    : LotteryNetwork.ClientLotteryState.playerList.players;
            boolean hasAny = allPlayers != null && !allPlayers.isEmpty();
            if (hasAny) {
                addTab(Button.builder(Component.literal("无匹配玩家"), b -> {})
                        .bounds(px, py, Math.min(180, pw), 20).build());
            } else {
                addTab(Button.builder(Component.literal("无数据 — 点刷新列表"), b -> LotteryClientNetwork.clientRequestPlayerList())
                        .bounds(px, py, Math.min(180, pw), 20).build());
            }
        }
    }

    private void buildPlayerCardControls(PlayerAdminModels.PlayerRow player, int x, int y, int width) {
        String[] keys = {"civilian", "neutral", "neutral_for_killer", "killer", "self_select", "limit_break"};
        int controlWidth = Math.max(0, width - 6);
        playerCardScrollLayout = PlayerCardScrollLayout.calculate(
                y, pageBottom() - 2, controlWidth, keys.length);
        playerCardPanel.setBounds(
                x, playerCardScrollLayout.viewportTop(), width,
                Math.max(1, playerCardScrollLayout.viewportHeight()));
        playerCardPanel.setContentHeight(playerCardScrollLayout.contentHeight());
        playerCardPanel.setScroll(playerAssetsViewState.cardScroll());
        playerAssetsViewState.setCardScroll(playerCardPanel.getScroll());

        int scroll = playerCardPanel.getScroll();
        boolean stacked = playerCardScrollLayout.stacked();
        int labelWidth = stacked ? controlWidth : Math.max(78, controlWidth - 202);
        int stepX = stacked ? x : x + labelWidth;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            int rowY = playerCardScrollLayout.controlY(i, scroll);
            boolean rowVisible = playerCardScrollLayout.isWidgetFullyVisible(rowY, 20);
            int stepWidth = stacked ? 34 : 36;
            int minusX = stepX + stepWidth + 4;
            int plusX = minusX + 28;
            int setX = plusX + 28;
            int setWidth = stacked ? 38 : 44;
            int setButtonX = setX + setWidth + 4;
            int setButtonWidth = stacked ? 42 : 48;
            EditBox step = addTab(new EditBox(font, stepX, rowY, stepWidth, 20,
                    Component.translatable("screen.habitrain_lottery.config.cards.step")));
            step.setValue(playerAssetsViewState.cardStepDraft(key, "1"));
            step.setResponder(value -> playerAssetsViewState.setCardStepDraft(key, value));
            step.setHint(Component.literal("±N"));
            step.visible = rowVisible;
            cardStepBoxes.put(key, step);
            cardEditWidgets.add(step);

            Button decrease = addTab(Button.builder(Component.literal("−"), b ->
                            sendCardMutation(player, key, "ADD", -cardStep(key)))
                    .bounds(minusX, rowY, 24, 20)
                    .tooltip(Tooltip.create(Component.translatable(
                            "screen.habitrain_lottery.config.cards.decrease"))).build());
            decrease.visible = rowVisible;
            Button increase = addTab(Button.builder(Component.literal("+"), b ->
                            sendCardMutation(player, key, "ADD", cardStep(key)))
                    .bounds(plusX, rowY, 24, 20)
                    .tooltip(Tooltip.create(Component.translatable(
                            "screen.habitrain_lottery.config.cards.increase"))).build());
            increase.visible = rowVisible;
            cardEditWidgets.add(decrease);
            cardEditWidgets.add(increase);

            int current = cardCount(player, key);
            EditBox set = addTab(new EditBox(font, setX, rowY, setWidth, 20,
                    Component.translatable("screen.habitrain_lottery.config.cards.set")));
            set.setValue(playerAssetsViewState.cardSetDraft(key, String.valueOf(current)));
            set.setResponder(value -> playerAssetsViewState.setCardSetDraft(key, value));
            set.setMaxLength(6);
            set.visible = rowVisible;
            cardSetBoxes.put(key, set);
            cardEditWidgets.add(set);
            Button setButton = addTab(Button.builder(Component.translatable(
                            "screen.habitrain_lottery.config.cards.set"), b ->
                            sendCardMutation(player, key, "SET", cardSetValue(key, current)))
                    .bounds(setButtonX, rowY, setButtonWidth, 20).build());
            setButton.visible = rowVisible;
            cardEditWidgets.add(setButton);
        }
    }

    private int cardStep(String key) {
        return Mth.clamp((int) parseD(cardStepBoxes.get(key), 1), 1, 1000);
    }

    private int cardSetValue(String key, int fallback) {
        return Mth.clamp((int) parseD(cardSetBoxes.get(key), fallback), 0, 100000);
    }

    private int cardCount(PlayerAdminModels.PlayerRow player, String key) {
        if (player == null || player.cards == null) {
            return 0;
        }
        return Math.max(0, player.cards.getOrDefault(key, 0));
    }

    private void sendCardMutation(
            PlayerAdminModels.PlayerRow player,
            String key,
            String operation,
            int value
    ) {
        if (cardMutationPending) {
            status = Component.translatable("screen.habitrain_lottery.config.cards.pending").getString();
            return;
        }
        if (player == null || "CORRUPT".equalsIgnoreCase(player.cardStatus)) {
            status = Component.translatable("screen.habitrain_lottery.config.cards.corrupt").getString();
            return;
        }
        if (!LotteryNetwork.ClientLotteryState.op || !LotteryClientNetwork.canSendPlay()) {
            status = Component.translatable("screen.habitrain_lottery.config.cards.readonly").getString();
            return;
        }
        if (LotteryClientNetwork.clientModifyPlayerCard(player.uuid, key, operation, value)) {
            cardMutationPending = true;
            cardMutationPendingTicks = 0;
            status = Component.translatable("screen.habitrain_lottery.config.cards.pending").getString();
            rebuildTabContent();
        } else {
            status = "角色卡修改请求发送失败";
        }
    }

    private void refreshPlayerCardLocks() {
        if (selectedTab != TAB_PLAYERS || !playerCardsPage || cardEditWidgets.isEmpty()) {
            return;
        }
        List<PlayerAdminModels.PlayerRow> rows = currentPlayerRows();
        PlayerAdminModels.PlayerRow player = selectedPlayerIndex >= 0 && selectedPlayerIndex < rows.size()
                ? rows.get(selectedPlayerIndex) : null;
        boolean editable = player != null
                && !"CORRUPT".equalsIgnoreCase(player.cardStatus)
                && LotteryClientNetwork.canSendPlay()
                && LotteryNetwork.ClientLotteryState.op
                && !cardMutationPending;
        for (AbstractWidget widget : cardEditWidgets) {
            widget.active = editable;
        }
    }

    // ---------------- skins ----------------
    private void buildSkinsTab(int y, int h) {
        addTab(Button.builder(Component.literal("打开皮肤衣柜"), b -> {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new com.habitrain.lottery.skin.SkinNetwork.Request("", ""));
        }).bounds(pageLeft(), y, 120, 20).build());
    }

    // ---------------- mail ----------------
    private void buildMailTab(int y, int h) {
        addTab(Button.builder(Component.literal("撰写邮件"), b -> {
            if (minecraft != null) {
                minecraft.setScreen(new MailComposeScreen(this));
            }
        }).bounds(pageLeft(), y, 120, 20).build());
        addTab(Button.builder(Component.literal("命令: /hlt mail"), b -> {
            status = "也可在游戏内执行 /hlt mail（需 OP）";
        }).bounds(pageLeft() + 128, y, 140, 20).build());
    }

    // ---------------- titles ----------------
    private void loadWorkingTitleCatalogFromState() {
        String json = LotteryNetwork.ClientLotteryState.titleCatalogJson;
        try {
            if (json != null && !json.isBlank()) {
                TitleCatalog parsed = LotteryConfigService.GSON.fromJson(json, TitleCatalog.class);
                if (parsed != null) {
                    workingTitleCatalog = parsed;
                }
            }
        } catch (Exception ignored) {
        }
        if (workingTitleCatalog == null) {
            workingTitleCatalog = new TitleCatalog();
        }
        if (workingTitleCatalog.titles == null) {
            workingTitleCatalog.titles = new ArrayList<>();
        }
    }

    private void ensureWorkingTitleCatalog() {
        if (workingTitleCatalog == null) {
            workingTitleCatalog = new TitleCatalog();
        }
        if (workingTitleCatalog.titles == null) {
            workingTitleCatalog.titles = new ArrayList<>();
        }
    }

    private List<PlayerAdminModels.PlayerRow> titlePlayerRows() {
        List<PlayerAdminModels.PlayerRow> players = LotteryNetwork.ClientLotteryState.playerList == null
                ? List.of()
                : LotteryNetwork.ClientLotteryState.playerList.players;
        if (players == null) {
            return List.of();
        }
        String q = titlePlayerSearchQuery == null ? "" : titlePlayerSearchQuery.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) {
            return players;
        }
        List<PlayerAdminModels.PlayerRow> filtered = new ArrayList<>();
        for (PlayerAdminModels.PlayerRow row : players) {
            if (row.name != null && row.name.toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private String titlePlayerCurrent(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "";
        }
        try {
            String json = LotteryNetwork.ClientLotteryState.titlePlayersJson;
            if (json == null || json.isBlank()) {
                return "";
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has(uuid) || !root.get(uuid).isJsonObject()) {
                return "";
            }
            JsonObject entry = root.getAsJsonObject(uuid);
            if (!entry.has("current") || entry.get("current").isJsonNull()) {
                return "";
            }
            return entry.get("current").getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> titlePlayerOwned(String uuid) {
        List<String> out = new ArrayList<>();
        if (uuid == null || uuid.isBlank()) {
            return out;
        }
        try {
            String json = LotteryNetwork.ClientLotteryState.titlePlayersJson;
            if (json == null || json.isBlank()) {
                return out;
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has(uuid) || !root.get(uuid).isJsonObject()) {
                return out;
            }
            JsonObject entry = root.getAsJsonObject(uuid);
            if (!entry.has("owned") || !entry.get("owned").isJsonArray()) {
                return out;
            }
            JsonArray arr = entry.getAsJsonArray("owned");
            for (JsonElement el : arr) {
                if (el != null && el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void applyTitleCatalogFieldsToModel() {
        ensureWorkingTitleCatalog();
        if (workingTitleCatalog.titles.isEmpty()) {
            return;
        }
        selectedTitleCatalogIndex = Mth.clamp(selectedTitleCatalogIndex, 0, workingTitleCatalog.titles.size() - 1);
        TitleCatalog.TitleEntry e = workingTitleCatalog.titles.get(selectedTitleCatalogIndex);
        if (e == null) {
            e = new TitleCatalog.TitleEntry();
            workingTitleCatalog.titles.set(selectedTitleCatalogIndex, e);
        }
        if (titleIdBox != null) {
            e.id = titleIdBox.getValue() == null ? "" : titleIdBox.getValue().trim();
        }
        if (titleDisplayBox != null) {
            e.display = titleDisplayBox.getValue() == null ? "" : titleDisplayBox.getValue();
        }
    }

    private boolean applyTitlesFields() {
        ensureWorkingTitleCatalog();
        applyTitleCatalogFieldsToModel();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (TitleCatalog.TitleEntry e : workingTitleCatalog.titles) {
            if (e == null) {
                continue;
            }
            String id = e.id == null ? "" : e.id.trim();
            if (id.isEmpty()) {
                status = "称号模板 id 不能为空";
                return false;
            }
            if (!seen.add(id)) {
                status = "称号模板 id 重复: " + id;
                return false;
            }
            e.id = id;
            if (e.display == null) {
                e.display = "";
            }
        }
        if (workingTitleCatalog.version <= 0) {
            workingTitleCatalog.version = 1;
        }
        return true;
    }

    private void sendTitlePlayerOp(String mode, String payload) {
        if (!LotteryClientNetwork.canSendPlay()) {
            status = "未连接服务器";
            return;
        }
        if (!LotteryNetwork.ClientLotteryState.op) {
            status = "需要 OP";
            return;
        }
        String uuid = selectedTitlePlayerUuid;
        if (uuid == null || uuid.isBlank()) {
            List<PlayerAdminModels.PlayerRow> rows = titlePlayerRows();
            if (selectedTitlePlayerIndex >= 0 && selectedTitlePlayerIndex < rows.size()) {
                PlayerAdminModels.PlayerRow row = rows.get(selectedTitlePlayerIndex);
                uuid = row != null ? row.uuid : "";
            }
        }
        if (uuid == null || uuid.isBlank()) {
            status = "请先选择玩家";
            return;
        }
        if (LotteryClientNetwork.clientTitleModify(mode, uuid, payload == null ? "" : payload)) {
            status = "已提交称号操作…";
        } else {
            status = "称号操作发送失败";
        }
    }

    private void buildTitlesTab(int y, int h) {
        int pageX = pageLeft();
        int pageR = pageRight();
        ensureWorkingTitleCatalog();
        // Prefer latest snapshot catalog if working is empty
        if (workingTitleCatalog.titles.isEmpty()
                && LotteryNetwork.ClientLotteryState.titleCatalogJson != null
                && !LotteryNetwork.ClientLotteryState.titleCatalogJson.isBlank()) {
            loadWorkingTitleCatalogFromState();
        }

        addTab(Button.builder(Component.literal("刷新称号"), b -> {
            if (!LotteryClientNetwork.clientRequestTitleSnapshot()) {
                status = "未连接或无权限";
            } else {
                status = "已请求称号快照";
            }
            if (LotteryNetwork.ClientLotteryState.op) {
                LotteryClientNetwork.clientRequestPlayerList();
            }
        }).bounds(pageX, y, 70, 20).build());

        addTab(Button.builder(Component.literal("刷新玩家"), b -> {
            if (!LotteryClientNetwork.clientRequestPlayerList()) {
                status = "未连接或无权限";
            } else {
                status = "已请求玩家列表";
            }
        }).bounds(pageX + 76, y, 70, 20).build());

        int leftW = Math.min(210, Math.max(150, pageWidth() / 3));
        int midGap = 10;
        int listTop = y + 38;
        int listH = Math.max(ROW_H * 3, h - 112);

        // ---- left: catalog ----
        List<TitleCatalog.TitleEntry> titles = workingTitleCatalog.titles;
        if (!titles.isEmpty()) {
            selectedTitleCatalogIndex = Mth.clamp(selectedTitleCatalogIndex, 0, titles.size() - 1);
        } else {
            selectedTitleCatalogIndex = 0;
        }
        List<String> catalogLabels = new ArrayList<>(titles.size());
        for (int i = 0; i < titles.size(); i++) {
            TitleCatalog.TitleEntry e = titles.get(i);
            String id = e == null || e.id == null ? "?" : e.id;
            String disp = e == null || e.display == null ? "" : e.display;
            String mark = i == selectedTitleCatalogIndex ? "§a" : "§7";
            catalogLabels.add(mark + "模板 " + shortName(id, 8) + " " + shortName(disp, 10));
        }
        titleCatalogList.setBounds(pageX, listTop, leftW, listH);
        titleCatalogList.setItems(catalogLabels, selectedTitleCatalogIndex);
        titleCatalogList.setOnSelect(fi -> {
            applyTitleCatalogFieldsToModel();
            selectedTitleCatalogIndex = fi;
            rebuildTabContent();
        });
        titleCatalogList.rebuildWidgets(this::addTab, null);

        int editY = titleCatalogList.viewportBottom() + 4;
        titleIdBox = addTab(new EditBox(font, pageX, editY, leftW / 2 - 2, 18, Component.literal("id")));
        titleIdBox.setMaxLength(64);
        titleIdBox.setHint(Component.literal("模板 id"));
        titleDisplayBox = addTab(new EditBox(font, pageX + leftW / 2 + 2, editY, leftW / 2 - 2, 18, Component.literal("display")));
        titleDisplayBox.setMaxLength(128);
        titleDisplayBox.setHint(Component.literal("显示 §码"));
        if (!titles.isEmpty() && selectedTitleCatalogIndex < titles.size()) {
            TitleCatalog.TitleEntry sel = titles.get(selectedTitleCatalogIndex);
            titleIdBox.setValue(sel != null && sel.id != null ? sel.id : "");
            titleDisplayBox.setValue(sel != null && sel.display != null ? sel.display : "");
        } else {
            titleIdBox.setValue("");
            titleDisplayBox.setValue("");
        }

        int btnY = editY + 22;
        int templateButtonW = Math.min(48, Math.max(40, (leftW - 8) / 3));
        int grantTemplateX = pageX + (templateButtonW + 4) * 2;
        int grantTemplateW = Math.max(44, leftW - (templateButtonW + 4) * 2);
        addTab(Button.builder(Component.literal("+模板"), b -> {
            applyTitleCatalogFieldsToModel();
            ensureWorkingTitleCatalog();
            int n = workingTitleCatalog.titles.size() + 1;
            workingTitleCatalog.titles.add(new TitleCatalog.TitleEntry("title_" + n, "§e[新称号]", true));
            selectedTitleCatalogIndex = workingTitleCatalog.titles.size() - 1;
            titleCatalogList.ensureSelectedVisible();
            rebuildTabContent();
            status = "已添加模板（请用页面底部按钮保存到服务器）";
        }).bounds(pageX, btnY, templateButtonW, 18).build());
        addTab(Button.builder(Component.literal("-模板"), b -> {
            ensureWorkingTitleCatalog();
            if (workingTitleCatalog.titles.isEmpty()) {
                status = "无模板可删";
                return;
            }
            applyTitleCatalogFieldsToModel();
            selectedTitleCatalogIndex = Mth.clamp(selectedTitleCatalogIndex, 0, workingTitleCatalog.titles.size() - 1);
            workingTitleCatalog.titles.remove(selectedTitleCatalogIndex);
            selectedTitleCatalogIndex = Math.max(0, selectedTitleCatalogIndex - 1);
            rebuildTabContent();
            status = "已删除模板（需保存到服务器）";
        }).bounds(pageX + templateButtonW + 4, btnY, templateButtonW, 18).build());
        addTab(Button.builder(Component.literal("授予给选中玩家"), b -> {
            applyTitleCatalogFieldsToModel();
            ensureWorkingTitleCatalog();
            if (workingTitleCatalog.titles.isEmpty()) {
                status = "无模板";
                return;
            }
            selectedTitleCatalogIndex = Mth.clamp(selectedTitleCatalogIndex, 0, workingTitleCatalog.titles.size() - 1);
            TitleCatalog.TitleEntry e = workingTitleCatalog.titles.get(selectedTitleCatalogIndex);
            String display = e == null || e.display == null ? "" : e.display;
            if (display.isBlank()) {
                status = "模板 display 为空";
                return;
            }
            sendTitlePlayerOp("grant", display);
        }).bounds(grantTemplateX, btnY, grantTemplateW, 18)
                .tooltip(Tooltip.create(Component.literal("把当前模板授予右侧选中的玩家")))
                .build());

        // ---- right: players + owned ----
        int rightX = pageX + leftW + midGap;
        int rightW = Math.max(120, pageR - rightX);
        int playerColW = Math.min(150, Math.max(64, rightW / 2 - 4));
        int ownedColX = rightX + playerColW + 8;
        int ownedColW = Math.max(60, pageR - ownedColX);

        // Search box sits under the "玩家称号（即时下发）" header (drawn at contentY+26) and
        // pushes the right-column lists down 28px; rightListH shrinks by the same amount so the
        // OP buttons below the owned column keep their absolute position.
        int listTopRight = y + 66;
        int rightListH = Math.max(ROW_H * 3, h - 140);
        titlePlayerSearchBox = addTab(new EditBox(font, rightX, y + 42, playerColW, 18, Component.literal("搜索")));
        titlePlayerSearchBox.setMaxLength(64);
        titlePlayerSearchBox.setValue(titlePlayerSearchQuery == null ? "" : titlePlayerSearchQuery);
        titlePlayerSearchBox.setHint(Component.literal("搜索玩家名"));
        titlePlayerSearchBox.setResponder(s -> {
            String next = s == null ? "" : s;
            if (next.equals(titlePlayerSearchQuery)) {
                return;
            }
            // Capture the selected player's UUID from the pre-filter list before rebuild.
            List<PlayerAdminModels.PlayerRow> before = titlePlayerRows();
            if (selectedTitlePlayerIndex >= 0 && selectedTitlePlayerIndex < before.size()) {
                PlayerAdminModels.PlayerRow row = before.get(selectedTitlePlayerIndex);
                if (row != null && row.uuid != null) {
                    selectedTitlePlayerUuid = row.uuid;
                }
            }
            titlePlayerSearchQuery = next;
            rebuildTabContent();
            if (titlePlayerSearchBox != null) {
                titlePlayerSearchBox.setFocused(true);
                setFocused(titlePlayerSearchBox);
                titlePlayerSearchBox.setCursorPosition(titlePlayerSearchQuery.length());
            }
        });

        List<PlayerAdminModels.PlayerRow> players = titlePlayerRows();
        String keepUuid = selectedTitlePlayerUuid;
        if ((keepUuid == null || keepUuid.isBlank())
                && selectedTitlePlayerIndex >= 0
                && selectedTitlePlayerIndex < players.size()) {
            PlayerAdminModels.PlayerRow prev = players.get(selectedTitlePlayerIndex);
            if (prev != null && prev.uuid != null) {
                keepUuid = prev.uuid;
            }
        }
        if (!players.isEmpty()) {
            int restored = -1;
            if (keepUuid != null && !keepUuid.isBlank()) {
                for (int i = 0; i < players.size(); i++) {
                    PlayerAdminModels.PlayerRow row = players.get(i);
                    if (row != null && keepUuid.equals(row.uuid)) {
                        restored = i;
                        break;
                    }
                }
            }
            selectedTitlePlayerIndex = restored >= 0 ? restored : 0;
            PlayerAdminModels.PlayerRow selRow = players.get(selectedTitlePlayerIndex);
            selectedTitlePlayerUuid = selRow != null && selRow.uuid != null ? selRow.uuid : "";
        } else {
            selectedTitlePlayerIndex = 0;
            selectedTitlePlayerUuid = "";
        }

        List<String> playerLabels = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            PlayerAdminModels.PlayerRow row = players.get(i);
            String mark = i == selectedTitlePlayerIndex ? "§a" : (row.online ? "§f" : "§7");
            playerLabels.add(mark + (row.online ? "● " : "○ ") + shortName(row.name, 12));
        }
        titlePlayerList.setBounds(rightX, listTopRight, playerColW, rightListH);
        titlePlayerList.setItems(playerLabels, selectedTitlePlayerIndex);
        titlePlayerList.setOnSelect(fi -> {
            selectedTitlePlayerIndex = fi;
            List<PlayerAdminModels.PlayerRow> rows = titlePlayerRows();
            if (fi >= 0 && fi < rows.size()) {
                PlayerAdminModels.PlayerRow row = rows.get(fi);
                selectedTitlePlayerUuid = row != null && row.uuid != null ? row.uuid : "";
            }
            selectedTitleOwnedIndex = 0;
            rebuildTabContent();
        });
        titlePlayerList.rebuildWidgets(this::addTab, null);

        List<String> owned = titlePlayerOwned(selectedTitlePlayerUuid);
        String current = titlePlayerCurrent(selectedTitlePlayerUuid);
        if (!owned.isEmpty()) {
            selectedTitleOwnedIndex = Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
        } else {
            selectedTitleOwnedIndex = 0;
        }
        List<String> ownedLabels = new ArrayList<>(owned.size());
        for (int i = 0; i < owned.size(); i++) {
            String t = owned.get(i);
            boolean isCur = t != null && t.equals(current);
            String mark = i == selectedTitleOwnedIndex ? "§a" : (isCur ? "§e" : "§7");
            ownedLabels.add(mark + (isCur ? "★ " : "拥有 ") + shortName(t, 14));
        }
        titleOwnedList.setBounds(ownedColX, listTopRight, ownedColW, Math.max(ROW_H * 2, rightListH - 46));
        titleOwnedList.setItems(ownedLabels, selectedTitleOwnedIndex);
        titleOwnedList.setOnSelect(fi -> {
            selectedTitleOwnedIndex = fi;
            rebuildTabContent();
        });
        titleOwnedList.rebuildWidgets(this::addTab, null);

        int opY = titleOwnedList.viewportBottom() + 4;
        int customButtonW = Math.min(76, Math.max(32, ownedColW / 2));
        int customFieldW = Math.max(20, ownedColW - customButtonW - 2);
        titleCustomBox = addTab(new EditBox(font, ownedColX, opY, customFieldW, 18, Component.literal("custom")));
        titleCustomBox.setMaxLength(128);
        titleCustomBox.setHint(Component.literal("自定义 display"));
        addTab(Button.builder(Component.literal("授予自定义"), b -> {
            String text = titleCustomBox == null ? "" : titleCustomBox.getValue();
            if (text == null || text.isBlank()) {
                status = "自定义称号不能为空";
                return;
            }
            sendTitlePlayerOp("grant", text);
        }).bounds(ownedColX + customFieldW + 2, opY, customButtonW, 18).build());

        int opY2 = opY + 22;
        int ownedActionW = Math.max(16, (ownedColW - 8) / 3);
        addTab(Button.builder(Component.literal("收回"), b -> {
            if (owned.isEmpty()) {
                status = "无已拥有称号";
                return;
            }
            selectedTitleOwnedIndex = Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
            sendTitlePlayerOp("revoke", owned.get(selectedTitleOwnedIndex));
        }).bounds(ownedColX, opY2, ownedActionW, 18).build());
        addTab(Button.builder(Component.literal("设佩戴"), b -> {
            if (owned.isEmpty()) {
                status = "无已拥有称号";
                return;
            }
            selectedTitleOwnedIndex = Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
            sendTitlePlayerOp("set_current", owned.get(selectedTitleOwnedIndex));
        }).bounds(ownedColX + ownedActionW + 4, opY2, ownedActionW, 18).build());
        addTab(Button.builder(Component.literal("清空"), b -> sendTitlePlayerOp("clear", ""))
                .bounds(ownedColX + (ownedActionW + 4) * 2, opY2, ownedActionW, 18).build());
    }

    // ---------------- json ----------------
    private void buildJsonTab(int y, int h, boolean editable) {
        String[] parts = {"全部", "奖池", "倍率", "发次", "画面"};
        int buttonGap = 2;
        int buttonW = Math.max(42, Math.min(52, (pageWidth() - buttonGap * parts.length) / (parts.length + 1)));
        for (int i = 0; i < parts.length; i++) {
            final int idx = i;
            addTab(Button.builder(Component.literal(parts[i]), b -> {
                jsonSection = idx;
                if (jsonArea != null) {
                    jsonArea.setValue(jsonForSection(idx));
                }
                status = "JSON: " + parts[idx];
            }).bounds(pageLeft() + i * (buttonW + buttonGap), y, buttonW, 18).build());
        }
        addTab(Button.builder(Component.literal("格式化"), b -> {
            if (jsonArea == null) {
                return;
            }
            try {
                // re-pretty current memory export for section
                applyJsonEditor();
                jsonArea.setValue(jsonForSection(jsonSection));
                status = "已格式化";
            } catch (Exception e) {
                status = "格式化失败";
            }
        }).bounds(pageLeft() + 5 * (buttonW + buttonGap), y, buttonW, 18).build());

        jsonArea = addTab(new MultilineTextArea(font, pageLeft(), y + 22, pageWidth(), Math.max(80, h - 28)));
        jsonArea.setMaxLength(1_000_000);
        jsonArea.setEditable(editable || !LotteryClientNetwork.canSendPlay());
        jsonArea.setValue(jsonForSection(jsonSection));
    }

    private String jsonForSection(int idx) {
        LotteryConfigService cfg = LotteryConfigService.get();
        return switch (idx) {
            case 1 -> LotteryConfigService.GSON.toJson(cfg.getPools());
            case 2 -> LotteryConfigService.GSON.toJson(cfg.getRates());
            case 3 -> LotteryConfigService.GSON.toJson(cfg.getGrants());
            case 4 -> LotteryConfigService.GSON.toJson(cfg.getTheme());
            default -> cfg.exportAllJson();
        };
    }

    private boolean applyJsonEditor() {
        if (jsonArea == null) {
            return true;
        }
        try {
            String text = jsonArea.getValue();
            try {
                LotteryConfigService.get().importAllJson(text);
                return true;
            } catch (Exception ignored) {
            }
            PoolConfigModels.Root pools = LotteryConfigService.GSON.fromJson(text, PoolConfigModels.Root.class);
            if (pools != null && pools.Pools != null) {
                String err = validatePools(pools);
                if (err != null) {
                    status = err;
                    return false;
                }
                LotteryConfigService.get().setPools(pools);
                return true;
            }
            RatesConfig rates = LotteryConfigService.GSON.fromJson(text, RatesConfig.class);
            if (rates != null && rates.modeMultipliers != null) {
                LotteryConfigService.get().setRates(rates);
                return true;
            }
            GrantsConfig grants = LotteryConfigService.GSON.fromJson(text, GrantsConfig.class);
            if (grants != null && grants.events != null) {
                LotteryConfigService.get().setGrants(grants);
                return true;
            }
            ThemeConfig theme = LotteryConfigService.GSON.fromJson(text, ThemeConfig.class);
            if (theme != null && theme.qualityBackgrounds != null) {
                LotteryConfigService.get().setTheme(theme);
                return true;
            }
            status = "无法识别 JSON 结构";
            return false;
        } catch (Exception e) {
            status = e.getMessage() == null ? "JSON 解析失败" : e.getMessage();
            return false;
        }
    }

    private boolean applyCurrentTab() {
        try {
            switch (selectedTab) {
                case 0 -> {
                    applyPoolFieldsToModel();
                    String err = validatePools(LotteryConfigService.get().getPools());
                    if (err != null) {
                        status = err;
                        return false;
                    }
                }
                case 1 -> applyRatesFields();
                case 2 -> applyGrantFields();
                case 3 -> applyThemeFields();
                case 7 -> {
                    return applyTitlesFields();
                }
                case 8 -> {
                    return applyJsonEditor();
                }
                default -> {
                }
            }
            return true;
        } catch (Exception e) {
            status = e.getMessage() == null ? "应用失败" : e.getMessage();
            return false;
        }
    }

    private String validatePools(PoolConfigModels.Root root) {
        if (root == null || root.Pools == null || root.Pools.isEmpty()) {
            return "Pools 为空";
        }
        for (PoolConfigModels.Pool pool : root.Pools) {
            if (!pool.Enable) {
                continue;
            }
            if (pool.PoolName == null || pool.PoolName.isBlank()) {
                return "存在空 PoolName";
            }
            if (pool.QualityListGroup == null || pool.QualityListGroup.isEmpty()) {
                return "奖池 " + pool.PoolName + " 无品质带";
            }
            double sum = 0;
            for (PoolConfigModels.QualityBand band : pool.QualityListGroup) {
                if (band.Probability == null || band.Probability <= 0) {
                    return "奖池 " + pool.PoolName + " 概率非法";
                }
                if (band.ItemList == null || band.ItemList.isEmpty()) {
                    return "奖池 " + pool.PoolName + " 条目为空";
                }
                sum += band.Probability;
            }
            if (sum < 0.999 || sum > 1.001) {
                return String.format(Locale.ROOT, "奖池 %s 概率和=%.4f，需≈1.0", pool.PoolName, sum);
            }
        }
        return null;
    }

    private void layoutTabs() {
        tabX = new int[TABS.length];
        tabY = new int[TABS.length];
        tabW = new int[TABS.length];
        ConfigConsoleLayout layout = consoleLayout();
        ConfigConsoleLayout.Rect nav = layout.navigation();
        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            tabX[selectedTab] = nav.x() + 24;
            tabY[selectedTab] = nav.y() + Math.max(0, (nav.height() - 16) / 2);
            tabW[selectedTab] = Math.max(0, nav.width() - 48);
            return;
        }
        int y = nav.y() + 8;
        for (int position = 0; position < NAV_ORDER.length; position++) {
            if (position == 0 || position == 4 || position == 7) {
                y += 12;
            }
            int tab = NAV_ORDER[position];
            tabX[tab] = nav.x() + 4;
            tabY[tab] = y;
            tabW[tab] = Math.max(0, nav.width() - 8);
            y += 18;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (suppressNestedBackground) {
            return;
        }
        super.renderBackground(g, mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        // Mod 菜单门控未授权：整屏用「当前为未授权的访问」覆盖层锁住，禁止一切交互
        if (MenuAccessBridge.isLocked()) {
            renderBackground(g, mx, my, delta);
            renderLockedOverlay(g);
            return;
        }
        if (tabX == null || tabY == null || tabW == null) {
            layoutTabs();
        }
        // Single background pass (blur + dim). Nested super.render must not re-blur.
        renderBackground(g, mx, my, delta);
        ConfigConsoleLayout layout = consoleLayout();
        g.fill(0, 0, width, height, 0xD010141A);
        g.fill(0, 0, width, layout.header().bottom(), 0xF0111A22);
        g.fill(layout.content().x() - 4, layout.content().y(),
                layout.content().right(), layout.content().bottom(), 0xC0141D25);
        g.fill(layout.content().x() - 4, layout.content().y(),
                layout.content().right(), layout.content().y() + 1, 0x5057C6D6);
        g.fill(0, layout.footer().y(), width, height, 0xE011181F);
        g.fill(0, layout.footer().y(), width, layout.footer().y() + 1, 0x4057C6D6);
        drawTabs(g, mx, my);

        int contentY = pageTop();
        if (selectedTab == 1) {
            String[] labels = {
                    "抽次消耗倍率", "重复转币固定值", "重复转币倍率", "金币换抽价格", "连登奖励上限",
                    "blackout 发次倍率", "murder 发次倍率", "repair 发次倍率", "OP 权限等级"
            };
            int rowH = 36;
            int viewTop = contentY;
            int viewBottom = pageBottom();
            for (int i = 0; i < labels.length; i++) {
                int sy = ratesPanel.applyY(i * rowH);
                if (sy + 12 > viewTop && sy < viewBottom) {
                    g.drawString(font, labels[i], pageLeft(), sy, 0xFFB0B8C0, false);
                }
            }
        } else if (selectedTab == 3) {
            String[] labels = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "UNBELIEVABLE"};
            for (int i = 0; i < labels.length; i++) {
                int sy = themePanel.applyY(i * 34);
                if (sy + 12 > contentY && sy < pageBottom()) {
                    g.drawString(font, labels[i] + " 背景", pageLeft(), sy, 0xFFB0B8C0, false);
                }
            }
        } else if (selectedTab == TAB_PLAYERS) {
            List<PlayerAdminModels.PlayerRow> players = currentPlayerRows();
            boolean narrow = layout.mode() == ConfigConsoleLayout.Mode.NARROW;
            boolean showNarrowDetail = narrow
                    && playerAssetsViewState.narrowPage() == PlayerAssetsViewState.NarrowPage.DETAIL
                    && !players.isEmpty();
            if (narrow && !showNarrowDetail) {
                players = List.of();
            }
            int listW = narrow ? pageWidth() : Math.min(260, pageWidth() / 2);
            int px = narrow ? pageLeft() : pageLeft() + listW + 20;
            int py = showNarrowDetail ? layout.content().y() + 4 : contentY + 46;
            if (!players.isEmpty() && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
                PlayerAdminModels.PlayerRow sel = players.get(selectedPlayerIndex);
                if (!narrow) {
                    g.drawString(font, "选中: " + sel.name + (sel.online ? " §a在线" : " §7离线"), px, py, 0xFFFFFFFF, false);
                    g.drawString(font, "UUID: " + shortName(sel.uuid, 18), px, py + 12, 0xFF8A92A0, false);
                    g.drawString(font, "抽数: " + sel.lootChance + "   硬币: " + sel.coinNum + "   解锁: " + sel.unlockCount,
                            px, py + 26, 0xFFD4A55A, false);
                }
                if (playerCardsPage) {
                    String[] keys = {"civilian", "neutral", "neutral_for_killer", "killer", "self_select", "limit_break"};
                    PlayerCardScrollLayout cardLayout = playerCardScrollLayout;
                    if (cardLayout != null && cardLayout.viewportHeight() > 0) {
                        int scroll = playerCardPanel.getScroll();
                        int labelWidth = cardLayout.stacked()
                                ? cardLayout.width()
                                : Math.max(78, cardLayout.width() - 202);
                        g.enableScissor(px, cardLayout.viewportTop(), pageRight(), cardLayout.viewportBottom());
                        try {
                            for (int i = 0; i < keys.length; i++) {
                                String key = keys[i];
                                String label = Component.translatable(
                                        "screen.habitrain_lottery.config.cards." + key).getString()
                                        + "  ×" + cardCount(sel, key);
                                label = font.plainSubstrByWidth(label, Math.max(20, labelWidth - 4));
                                int labelY = cardLayout.labelY(i, scroll);
                                if (cardLayout.isTextVisible(labelY, font.lineHeight)) {
                                    g.drawString(font, label, px, labelY, 0xFFE4E9ED, false);
                                }
                            }
                            int messageY = cardLayout.statusY(scroll);
                            if ("CORRUPT".equalsIgnoreCase(sel.cardStatus)) {
                                g.drawString(font, Component.translatable("screen.habitrain_lottery.config.cards.corrupt"),
                                        px, messageY, 0xFFFF6B6B, false);
                            } else if ("MISSING".equalsIgnoreCase(sel.cardStatus)) {
                                g.drawString(font, Component.translatable("screen.habitrain_lottery.config.cards.missing"),
                                        px, messageY, 0xFFD4A55A, false);
                            } else if (cardMutationPending) {
                                g.drawString(font, Component.translatable("screen.habitrain_lottery.config.cards.pending"),
                                        px, messageY, 0xFF57C6D6, false);
                            }
                        } finally {
                            g.disableScissor();
                        }
                    }
                } else if (!narrow) {
                    g.drawString(font, "备份目录: <world>/lottery_backup/<时间戳>/", px, py + 126, 0xFF8A92A0, false);
                }
            } else if (!narrow) {
                String emptyHint = (playerSearchQuery != null && !playerSearchQuery.isBlank())
                        ? "无匹配玩家"
                        : "进服后点「刷新列表」查看玩家抽数";
                g.drawString(font, emptyHint, px, py, 0xFF8A92A0, false);
            }
        } else if (selectedTab == TAB_SKINS) {
            int yy = contentY + 28;
            g.drawString(font, "已注册皮肤: " + SkinContentBootstrap.getRegisteredCount(), pageLeft(), yy, 0xFFFFFFFF, false);
            g.drawString(font, "皮肤由扩展模组提供，本模组不内置皮肤", pageLeft(), yy + 14, 0xFF8A92A0, false);
            g.drawString(font, "命令: /hlt skins  |  /hlt open", pageLeft(), yy + 28, 0xFF8A92A0, false);
        } else if (selectedTab == TAB_MAIL) {
            int yy = contentY + 28;
            g.drawString(font, "OP 可撰写系统邮件，发放抽数 / 金币 / 阵营卡 / 自选卡。", pageLeft(), yy, 0xFFFFFFFF, false);
            g.drawString(font, "邮件存于 world/habitrain_lottery/mail/players/", pageLeft(), yy + 14, 0xFF8A92A0, false);
            g.drawString(font, "玩家在大厅「邮箱管理」领取。", pageLeft(), yy + 28, 0xFF8A92A0, false);
        } else if (selectedTab == TAB_TITLES) {
            int leftW = Math.min(210, Math.max(150, pageWidth() / 3));
            int rightX = pageLeft() + leftW + 10;
            g.drawString(font, "模板库（页面底部保存）", pageLeft(), contentY + 26, 0xFF8A92A0, false);
            g.drawString(font, "玩家称号（即时下发）", rightX, contentY + 26, 0xFF8A92A0, false);
            String uuid = selectedTitlePlayerUuid;
            String cur = titlePlayerCurrent(uuid);
            if (uuid != null && !uuid.isBlank()) {
                List<PlayerAdminModels.PlayerRow> rows = titlePlayerRows();
                String name = shortName(uuid, 10);
                for (PlayerAdminModels.PlayerRow r : rows) {
                    if (r != null && uuid.equals(r.uuid)) {
                        name = r.name == null ? name : r.name;
                        break;
                    }
                }
                g.drawString(font, "选中: " + name, rightX, height - 58, 0xFFB0B8C0, false);
                g.drawString(font, "佩戴: " + (cur == null || cur.isBlank() ? "（无）" : shortName(cur, 16)),
                        rightX + 100, height - 58, 0xFFD4A55A, false);
            } else {
                g.drawString(font, "进服后刷新玩家/称号快照", rightX, height - 58, 0xFF8A92A0, false);
            }
        }

        suppressNestedBackground = true;
        try {
            super.render(g, mx, my, delta);
        } finally {
            suppressNestedBackground = false;
        }
        // Tabs above widgets so footer/status never covers the bar; bar stays sharp.
        drawTabs(g, mx, my);
        if (selectedTab == 0) {
            poolList.renderScrollbar(g);
            bandList.renderScrollbar(g);
        } else if (selectedTab == 1) {
            ratesPanel.renderScrollbar(g);
        } else if (selectedTab == 3) {
            themePanel.renderScrollbar(g);
        } else if (selectedTab == 2) {
            grantList.renderScrollbar(g);
        } else if (selectedTab == TAB_PLAYERS) {
            if (!isNarrowPlayerDetail()) {
                playerList.renderScrollbar(g);
            }
            if (playerCardsPage && playerCardScrollLayout != null
                    && playerCardScrollLayout.viewportHeight() > 0) {
                playerCardPanel.renderScrollbar(g);
            }
        } else if (selectedTab == TAB_TITLES) {
            titleCatalogList.renderScrollbar(g);
            titlePlayerList.renderScrollbar(g);
            titleOwnedList.renderScrollbar(g);
        }
        int statusY = Math.max(layout.header().bottom(), layout.footer().y() - 11);
        g.drawString(font, Component.literal(status == null ? "" : status),
                pageLeft(), statusY, 0xFFD4A55A, false);
    }

    /** 门控未授权时覆盖整个场景的「当前为未授权的访问」提示。 */
    private void renderLockedOverlay(GuiGraphics g) {
        g.fill(0, 0, width, height, 0xC0000000);
        int boxW = Math.min(400, width - 40);
        int boxH = 100;
        int boxX = (width - boxW) / 2;
        int boxY = (height - boxH) / 2;
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A2230);
        g.fill(boxX, boxY, boxX + boxW, boxY + 2, 0xFF57C6D6);
        String title = "当前为未授权的访问";
        String sub = "服务器管理员未授予你 Mod 菜单编辑权限";
        String hint = "请联系管理员，或由后台控制台执行 /habi_api menugate add <你的名字>";
        g.drawString(font, title, boxX + (boxW - font.width(title)) / 2, boxY + 28, 0xFFFF6B6B, false);
        g.drawString(font, sub, boxX + (boxW - font.width(sub)) / 2, boxY + 50, 0xFFB0B8C0, false);
        g.drawString(font, hint, boxX + (boxW - font.width(hint)) / 2, boxY + 66, 0xFF8A92A0, false);
        String esc = "ESC  返回";
        g.drawString(font, esc, width / 2 - font.width(esc) / 2, height - 18, 0xFF8A92A0, false);
    }

    private void drawTabs(GuiGraphics g, int mx, int my) {
        if (tabX == null || tabY == null || tabW == null) {
            layoutTabs();
        }
        ConfigConsoleLayout layout = consoleLayout();
        ConfigConsoleLayout.Rect nav = layout.navigation();
        g.fill(nav.x(), nav.y(), nav.right(), nav.bottom(), 0xE512171E);
        g.fill(nav.right() - 1, nav.y(), nav.right(), nav.bottom(), 0x4057C6D6);

        Component consoleTitle = Component.translatable("screen.habitrain_lottery.config.title");
        g.drawString(font, consoleTitle, 12, 12, 0xFFFFFFFF, false);
        String access = !LotteryClientNetwork.canSendPlay()
                ? Component.translatable("screen.habitrain_lottery.config.status.offline").getString()
                : (LotteryNetwork.ClientLotteryState.op
                ? Component.translatable("screen.habitrain_lottery.config.status.connected_op").getString()
                : Component.translatable("screen.habitrain_lottery.config.status.connected_readonly").getString());
        int accessColor = !LotteryClientNetwork.canSendPlay()
                ? 0xFFD4A55A
                : (LotteryNetwork.ClientLotteryState.op ? 0xFF65D18A : 0xFFFF6B6B);
        int badgeW = font.width(access) + 14;
        int badgeX = Math.max(12, width - badgeW - 12);
        g.fill(badgeX, 8, width - 12, 27, 0x80212C35);
        g.fill(badgeX, 8, badgeX + 2, 27, accessColor);
        g.drawString(font, access, badgeX + 8, 13, accessColor, false);

        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            int cy = nav.y() + Math.max(4, (nav.height() - 8) / 2);
            g.drawString(font, "‹", nav.x() + 8, cy, 0xFFD4A55A, false);
            g.drawCenteredString(font, sectionTitle(selectedTab), nav.x() + nav.width() / 2, cy, 0xFFFFFFFF);
            g.drawString(font, "›", nav.right() - 14, cy, 0xFFD4A55A, false);
        } else {
            g.drawString(font, Component.translatable("screen.habitrain_lottery.config.group.lottery"),
                    nav.x() + 6, tabY[0] - 10, 0xFF57C6D6, false);
            g.drawString(font, Component.translatable("screen.habitrain_lottery.config.group.players_and_content"),
                    nav.x() + 6, tabY[TAB_PLAYERS] - 10, 0xFF57C6D6, false);
            g.drawString(font, Component.translatable("screen.habitrain_lottery.config.group.operations"),
                    nav.x() + 6, tabY[TAB_MAIL] - 10, 0xFF57C6D6, false);
            for (int i = 0; i < TABS.length; i++) {
            boolean selected = i == selectedTab;
            boolean hover = mx >= tabX[i] && mx < tabX[i] + tabW[i]
                    && my >= tabY[i] && my < tabY[i] + 16;
            int bg = selected ? 0xFF24313A : (hover ? 0xFF1C252E : 0x00141820);
            g.fill(tabX[i], tabY[i], tabX[i] + tabW[i], tabY[i] + 16, bg);
            g.fill(tabX[i], tabY[i], tabX[i] + 2, tabY[i] + 16,
                    selected ? 0xFFD4A55A : 0x204D5965);
            g.drawString(font, sectionTitle(i), tabX[i] + 8, tabY[i] + 4,
                    selected ? 0xFFFFFFFF : 0xFF8A92A0, false);
            }
        }
        if (!isNarrowPlayerDetail()) {
            g.drawString(font, sectionTitle(selectedTab), layout.content().x(), layout.content().y() + 4,
                    0xFFFFFFFF, false);
            g.drawString(font, sectionDescription(selectedTab), layout.content().x(), layout.content().y() + 17,
                    0xFF8A92A0, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (MenuAccessBridge.isLocked()) return false;
        if (tabX == null || tabY == null || tabW == null) {
            layoutTabs();
        }
        ConfigConsoleLayout layout = consoleLayout();
        if (layout.navigation().contains(mx, my)) {
            if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
                if (mx < layout.navigation().x() + 24) {
                    switchTab((selectedTab - 1 + TABS.length) % TABS.length);
                    return true;
                }
                if (mx >= layout.navigation().right() - 24) {
                    switchTab((selectedTab + 1) % TABS.length);
                    return true;
                }
            }
            for (int i = 0; i < TABS.length; i++) {
                if (mx >= tabX[i] && mx < tabX[i] + tabW[i]
                        && my >= tabY[i] && my < tabY[i] + 16) {
                    switchTab(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void switchTab(int tab) {
        if (tab < 0 || tab >= TABS.length || selectedTab == tab) {
            return;
        }
        applyCurrentTab();
        selectedTab = tab;
        layoutTabs();
        rebuildTabContent();
        status = "已切换至「" + sectionTitle(tab).getString() + "」";
        if (tab == TAB_PLAYERS && LotteryNetwork.ClientLotteryState.op) {
            LotteryClientNetwork.clientRequestPlayerList();
        }
        if (tab == TAB_TITLES && LotteryNetwork.ClientLotteryState.op) {
            LotteryClientNetwork.clientRequestTitleSnapshot();
            LotteryClientNetwork.clientRequestPlayerList();
        }
    }

    private static Component sectionTitle(int tab) {
        return Component.translatable(SECTION_IDS[Mth.clamp(tab, 0, SECTION_IDS.length - 1)].titleKey());
    }

    private static Component sectionDescription(int tab) {
        return Component.translatable(SECTION_IDS[Mth.clamp(tab, 0, SECTION_IDS.length - 1)].descriptionKey());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (MenuAccessBridge.isLocked()) return false;
        if (jsonArea != null && jsonArea.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        if (selectedTab == 0) {
            int beforePool = poolList.getScroll();
            int beforeBand = bandList.getScroll();
            if (poolList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (poolList.getScroll() != beforePool) {
                    applyPoolFieldsToModel();
                    rebuildTabContent();
                }
                return true;
            }
            if (bandList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (bandList.getScroll() != beforeBand) {
                    applyPoolFieldsToModel();
                    rebuildTabContent();
                }
                return true;
            }
        } else if (selectedTab == 1) {
            int before = ratesPanel.getScroll();
            if (ratesPanel.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (ratesPanel.getScroll() != before) {
                    applyRatesFields();
                    rebuildTabContent();
                }
                return true;
            }
        } else if (selectedTab == 3) {
            int before = themePanel.getScroll();
            if (themePanel.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (themePanel.getScroll() != before) {
                    applyThemeFields();
                    rebuildTabContent();
                }
                return true;
            }
        } else if (selectedTab == 2) {
            int before = grantList.getScroll();
            if (grantList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (grantList.getScroll() != before) {
                    applyGrantFields();
                    rebuildTabContent();
                }
                return true;
            }
        } else if (selectedTab == TAB_PLAYERS) {
            if (playerCardsPage && playerCardScrollLayout != null
                    && playerCardScrollLayout.viewportHeight() > 0) {
                int beforeCards = playerCardPanel.getScroll();
                int rows = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
                if (rows != 0 && playerCardPanel.isMouseOver(mouseX, mouseY)
                        && playerCardScrollLayout.maxScroll() > 0) {
                    int next = playerCardScrollLayout.scrollByRows(beforeCards, rows);
                    playerCardPanel.setScroll(next);
                    if (next != beforeCards) {
                        playerAssetsViewState.setCardScroll(next);
                        rebuildTabContent();
                    }
                    return true;
                }
            }
            if (isNarrowPlayerDetail()) {
                return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            }
            int before = playerList.getScroll();
            if (playerList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (playerList.getScroll() != before) {
                    rebuildTabContent();
                }
                return true;
            }
        } else if (selectedTab == TAB_TITLES) {
            int beforeCat = titleCatalogList.getScroll();
            if (titleCatalogList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (titleCatalogList.getScroll() != beforeCat) {
                    applyTitleCatalogFieldsToModel();
                    rebuildTabContent();
                }
                return true;
            }
            int beforePl = titlePlayerList.getScroll();
            if (titlePlayerList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (titlePlayerList.getScroll() != beforePl) {
                    rebuildTabContent();
                }
                return true;
            }
            int beforeOwn = titleOwnedList.getScroll();
            if (titleOwnedList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (titleOwnedList.getScroll() != beforeOwn) {
                    rebuildTabContent();
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void onClose() {
        applyCurrentTab();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String shortName(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double parseD(EditBox box, double def) {
        if (box == null) {
            return def;
        }
        try {
            return Double.parseDouble(box.getValue().trim());
        } catch (Exception e) {
            return def;
        }
    }
}
