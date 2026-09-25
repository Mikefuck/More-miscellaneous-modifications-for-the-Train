package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.client.gui.config.ConfigSectionId;
import com.habitrain.lottery.client.gui.config.PlayerAssetFilter;
import com.habitrain.lottery.client.gui.config.PlayerAssetsViewState;
import com.habitrain.lottery.client.gui.config.PlayerCardScrollLayout;
import com.habitrain.lottery.crate.CrateService;
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
 * OP-gated operations console for player assets, titles, skins and mail.
 */
public class LotteryConfigRootScreen extends Screen {
    private static final String[] TABS = {"玩家资产", "称号管理", "皮肤内容", "开箱管理", "撰写邮件"};
    private static final ConfigSectionId[] SECTION_IDS = {
            ConfigSectionId.PLAYERS, ConfigSectionId.TITLES, ConfigSectionId.SKINS,
            ConfigSectionId.CRATES, ConfigSectionId.MAIL
    };
    private static final int[] NAV_ORDER = {0, 1, 2, 3, 4};
    private static final int ROW_H = 22;
    private static final int TAB_PLAYERS = 0;
    private static final int TAB_TITLES = 1;
    private static final int TAB_SKINS = 2;
    private static final int TAB_CRATES = 3;
    private static final int TAB_MAIL = 4;
    private static final Gson GSON = new Gson();

    private final Screen parent;
    private int selectedTab;
    private String status = "";
    private int[] tabX;
    private int[] tabY;
    private int[] tabW;
    private Button saveButton;

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
    private int lastSeenTitleVersion = -1;
    private int lastSeenCrateConfigVersion = -1;
    private int selectedCrateIndex;
    private JsonObject crateConfig = new JsonObject();

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
    private final ScrollableButtonList playerList = new ScrollableButtonList();
    private final ScrollableButtonList titleCatalogList = new ScrollableButtonList();
    private final ScrollableButtonList titlePlayerList = new ScrollableButtonList();
    private final ScrollableButtonList titleOwnedList = new ScrollableButtonList();
    private final ScrollableButtonList crateList = new ScrollableButtonList();

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
        tabWidgets.clear();

        rebuildTabContent();
        buildFooter();

        if (LotteryClientNetwork.canSendPlay()) {
            status = LotteryNetwork.ClientLotteryState.op ? "已连接：OP 可管理" : "已连接：非 OP 只读";
            LotteryClientNetwork.clientRequestPlayerList();
            LotteryClientNetwork.clientRequestTitleSnapshot();
        } else {
            status = "离线浏览；进服后可同步玩家数据";
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
        if (selectedTab == TAB_CRATES && CrateClientNetwork.STATE.configVersion != lastSeenCrateConfigVersion) {
            lastSeenCrateConfigVersion = CrateClientNetwork.STATE.configVersion;
            try {
                crateConfig = JsonParser.parseString(CrateClientNetwork.STATE.configJson).getAsJsonObject();
            } catch (RuntimeException ignored) {
                crateConfig = new JsonObject();
            }
            rebuildTabContent();
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
        return selectedTab == TAB_TITLES;
    }

    private void buildFooter() {
        ConfigConsoleLayout layout = consoleLayout();
        int by = layout.footer().y() + Math.max(2, (layout.footer().height() - 20) / 2);
        int x = pageLeft();
        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.refresh"), b -> {
            if (LotteryClientNetwork.canSendPlay()) {
                LotteryClientNetwork.clientRequestPlayerList();
                LotteryClientNetwork.clientRequestTitleSnapshot();
                status = "已请求服务器管理数据";
            } else {
                status = "未连接服务器";
            }
            rebuildTabContent();
        }).bounds(x, by, 42, 20)
                .tooltip(Tooltip.create(Component.literal("重新读取玩家与称号数据")))
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
            if (!applyTitlesFields()) {
                return;
            }
            if (LotteryClientNetwork.clientSaveTitleCatalog(GSON.toJson(workingTitleCatalog))) {
                status = "已提交称号模板库保存（OP）";
            } else {
                status = "称号模板保存发送失败";
            }
        }).bounds(x + 45, by, 90, 20)
                .tooltip(Tooltip.create(Component.literal("保存称号模板库；仅服务器 OP 可用")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.back"), b -> onClose())
                .bounds(pageRight() - 48, by, 48, 20).build());
        updateFooterState();
    }

    private void updateFooterState() {
        boolean visible = pageHasSaveAction();
        if (saveButton != null) {
            saveButton.visible = visible;
        }
    }

    private void clearTabWidgets() {
        for (AbstractWidget w : tabWidgets) {
            removeWidget(w);
        }
        tabWidgets.clear();
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
        switch (selectedTab) {
            case TAB_PLAYERS -> buildPlayersTab(contentY, contentH);
            case TAB_TITLES -> buildTitlesTab(contentY, contentH);
            case TAB_SKINS -> buildSkinsTab(contentY, contentH);
            case TAB_CRATES -> buildCratesTab(contentY, contentH);
            case TAB_MAIL -> buildMailTab(contentY, contentH);
            default -> {
            }
        }
        applyOpWidgetLocks();
        refreshPlayerCardLocks();
        updateFooterState();
    }

    private void applyOpWidgetLocks() {
        if (selectedTab == TAB_SKINS || selectedTab == TAB_CRATES) {
            return;
        }
        boolean onlineNonOp = LotteryClientNetwork.canSendPlay() && !LotteryNetwork.ClientLotteryState.op;
        if (!onlineNonOp) {
            for (AbstractWidget w : tabWidgets) {
                w.active = true;
            }
            return;
        }
        for (AbstractWidget w : tabWidgets) {
            if (w instanceof Button b) {
                String msg = b.getMessage().getString();
                if (msg.startsWith("§a") || msg.startsWith("§7") || msg.startsWith("·")
                        || msg.equals("刷新列表")
                        || msg.startsWith("● ") || msg.startsWith("○ ")
                        || msg.startsWith("模板 ") || msg.startsWith("拥有 ")
                        || msg.equals("刷新称号") || msg.equals("刷新玩家")
                        || msg.equals(Component.translatable(
                        "screen.habitrain_lottery.config.players.back_to_list").getString())) {
                    w.active = true;
                    continue;
                }
            }
            // Search boxes are read-only navigation aids — keep usable for non-OP viewers.
            if (w == playerSearchBox || w == titlePlayerSearchBox) {
                w.active = true;
                continue;
            }
            w.active = false;
        }
    }

    // ---------------- players ----------------
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
            if (!LotteryClientNetwork.clientModifyGreenApples("add_all_online", "", n)) {
                status = "需要进服且为 OP";
            }
        }).bounds(actionsX, y, actionW, 20).build());
        addTab(Button.builder(Component.literal("全员-N"), b -> {
            int n = (int) parseD(bulkDeltaBox, 1);
            if (!LotteryClientNetwork.clientModifyGreenApples("add_all_online", "", -Math.abs(n))) {
                status = "需要进服且为 OP";
            }
        }).bounds(actionsX + actionW + 4, y, actionW, 20).build());
        addTab(Button.builder(Component.literal("全员= N"), b -> {
            int n = (int) parseD(bulkDeltaBox, 0);
            if (!LotteryClientNetwork.clientModifyGreenApples("set_all_online", "", n)) {
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
                    + "  绿苹果:" + row.greenApples);
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
                    LotteryClientNetwork.clientModifyGreenApples("add_one", sel.uuid, Math.abs(n));
                }).bounds(px + 54, baseY, 40, 20).build());
                addTab(Button.builder(Component.literal("此人-"), b -> {
                    int n = (int) parseD(singleDeltaBox, 1);
                    LotteryClientNetwork.clientModifyGreenApples("add_one", sel.uuid, -Math.abs(n));
                }).bounds(px + 98, baseY, 40, 20).build());

                singleSetBox = addTab(new EditBox(font, px, baseY + 26, 50, 20, Component.literal("set")));
                singleSetBox.setValue(String.valueOf(sel.greenApples));
                singleSetBox.setHint(Component.literal("="));
                addTab(Button.builder(Component.literal("设为"), b -> {
                    int n = (int) parseD(singleSetBox, sel.greenApples);
                    LotteryClientNetwork.clientModifyGreenApples("set_one", sel.uuid, n);
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

    // ---------------- crates ----------------
    private void buildCratesTab(int y, int h) {
        int x = pageLeft();
        int editWidth = Math.min(126, Math.max(82, (pageWidth() - 8) / 2));
        addTab(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.crates"), b -> {
            if (minecraft != null) minecraft.setScreen(new CrateAdminScreen(this, selectedCrateIndex));
        }).bounds(x, y, editWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.habitrain_lottery.config.action.crates_hint")))
                .build());
        addTab(Button.builder(Component.translatable("screen.habitrain_lottery.config.action.crates_refresh"), b -> {
            if (CrateClientNetwork.connected()) {
                CrateClientNetwork.requestConfig();
                status = Component.translatable("screen.habitrain_lottery.config.action.crates_requested").getString();
            } else {
                status = Component.translatable("screen.habitrain_lottery.crate_admin.offline").getString();
            }
        }).bounds(x + editWidth + 8, y, Math.min(100, pageWidth() - editWidth - 8), 20).build());
        List<CrateService.Definition> crates = CrateService.definitions();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < crates.size(); i++) {
            labels.add((i == selectedCrateIndex ? "§a● " : "§7○ ")
                    + Component.translatable(crates.get(i).nameKey()).getString());
        }
        int listW = consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW
                ? pageWidth() : Math.min(200, Math.max(110, pageWidth() / 2));
        crateList.setBounds(x, y + 26, listW, Math.max(ROW_H, h - 26));
        crateList.setItems(labels, selectedCrateIndex);
        crateList.setOnSelect(index -> {
            selectedCrateIndex = index;
            rebuildTabContent();
        });
        crateList.rebuildWidgets(this::addTab, null);
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
                TitleCatalog parsed = GSON.fromJson(json, TitleCatalog.class);
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
        ConfigSectionId.Group previousGroup = null;
        for (int position = 0; position < NAV_ORDER.length; position++) {
            int tab = NAV_ORDER[position];
            ConfigSectionId.Group group = SECTION_IDS[tab].group();
            if (previousGroup == null || previousGroup != group) {
                y += 12;
            }
            tabX[tab] = nav.x() + 4;
            tabY[tab] = y;
            tabW[tab] = Math.max(0, nav.width() - 8);
            y += 18;
            previousGroup = group;
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
        if (selectedTab == TAB_PLAYERS) {
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
                    g.drawString(font, "绿苹果: " + sel.greenApples + "   已解锁皮肤: " + sel.unlockCount,
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
                    g.drawString(font, "玩家数据备份目录: <world>/lottery_backup/<时间戳>/", px, py + 126, 0xFF8A92A0, false);
                }
            } else if (!narrow) {
                String emptyHint = (playerSearchQuery != null && !playerSearchQuery.isBlank())
                        ? "无匹配玩家"
                        : "进服后点「刷新列表」查看玩家资产";
                g.drawString(font, emptyHint, px, py, 0xFF8A92A0, false);
            }
        } else if (selectedTab == TAB_SKINS) {
            int yy = contentY + 28;
            g.drawString(font, "已注册皮肤: " + SkinContentBootstrap.getRegisteredCount(), pageLeft(), yy, 0xFFFFFFFF, false);
            g.drawString(font, "皮肤由扩展模组提供，本模组不内置皮肤", pageLeft(), yy + 14, 0xFF8A92A0, false);
            g.drawString(font, "命令: /hlt skins", pageLeft(), yy + 28, 0xFF8A92A0, false);
        } else if (selectedTab == TAB_CRATES) {
            List<CrateService.Definition> crates = CrateService.definitions();
            if (!crates.isEmpty() && layout.mode() != ConfigConsoleLayout.Mode.NARROW) {
                CrateService.Definition crate = crates.get(Mth.clamp(selectedCrateIndex, 0, crates.size() - 1));
                int detailX = pageLeft() + Math.min(200, Math.max(110, pageWidth() / 2)) + 10;
                int detailW = Math.max(50, pageRight() - detailX);
                JsonObject pools = crateConfig.has("pools") && crateConfig.get("pools").isJsonObject()
                        ? crateConfig.getAsJsonObject("pools") : new JsonObject();
                JsonObject pool = pools.has(crate.id()) && pools.get(crate.id()).isJsonObject()
                        ? pools.getAsJsonObject(crate.id()) : new JsonObject();
                boolean enabled = !pool.has("enabled") || pool.get("enabled").getAsBoolean();
                boolean custom = pool.has("customPool") && pool.get("customPool").getAsBoolean();
                boolean protect = pool.has("duplicateProtection") && pool.get("duplicateProtection").getAsBoolean();
                String[] lines = {
                        Component.translatable(crate.nameKey()).getString(),
                        Component.translatable("screen.habitrain_lottery.crate_admin.key_label", CrateService.keyItemId(crate.id())).getString(),
                        Component.translatable(enabled ? "screen.habitrain_lottery.crate_admin.enabled" : "screen.habitrain_lottery.crate_admin.disabled").getString(),
                        Component.translatable(custom ? "screen.habitrain_lottery.crate_admin.custom_pool" : "screen.habitrain_lottery.crate_admin.default_pool").getString(),
                        Component.translatable(protect ? "screen.habitrain_lottery.crate_admin.no_duplicates" : "screen.habitrain_lottery.crate_admin.allow_duplicates").getString()
                };
                for (int i = 0; i < lines.length; i++) {
                    g.drawString(font, font.plainSubstrByWidth(lines[i], detailW), detailX, contentY + 28 + i * 16,
                            i == 0 ? crate.color() : 0xFFB7C1C6, false);
                }
            }
        } else if (selectedTab == TAB_MAIL) {
            int yy = contentY + 28;
            g.drawString(font, "OP 可撰写系统邮件，发放绿苹果 / 阵营卡 / 自选卡。", pageLeft(), yy, 0xFFFFFFFF, false);
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
        if (selectedTab == TAB_PLAYERS) {
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
        } else if (selectedTab == TAB_CRATES) {
            crateList.renderScrollbar(g);
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
            ConfigSectionId.Group previousGroup = null;
            for (int tab : NAV_ORDER) {
                ConfigSectionId.Group group = SECTION_IDS[tab].group();
                if (previousGroup == null || previousGroup != group) {
                    g.drawString(font, Component.translatable(group.translationKey()),
                            nav.x() + 6, tabY[tab] - 10, 0xFF57C6D6, false);
                }
                previousGroup = group;
            }
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
        if (selectedTab == TAB_TITLES) {
            applyTitlesFields();
        }
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
        if (tab == TAB_CRATES) {
            CrateClientNetwork.requestConfig();
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
        if (selectedTab == TAB_PLAYERS) {
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
        } else if (selectedTab == TAB_CRATES) {
            int before = crateList.getScroll();
            if (crateList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (crateList.getScroll() != before) rebuildTabContent();
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
