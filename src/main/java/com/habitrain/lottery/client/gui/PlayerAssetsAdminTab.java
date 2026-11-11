package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.client.gui.config.PlayerAssetFilter;
import com.habitrain.lottery.client.gui.config.PlayerAssetsViewState;
import com.habitrain.lottery.client.gui.config.PlayerCardScrollLayout;
import com.habitrain.lottery.network.ClientLotteryState;
import com.habitrain.lottery.network.PlayerAdminModels;
import com.habitrain.lottery.network.PlayerListRequestC2S;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extracted player assets & role-card management tab for {@link LotteryConfigRootScreen}
 * ({@code N-01}, {@code WP-05}, {@code I-24.1}, {@code I-24.3}, {@code N-02}).
 */
final class PlayerAssetsAdminTab {
    private static final int ROW_H = 22;
    private static final int SEARCH_DEBOUNCE_TICKS = 7;
    private static final String[] CARD_KEYS = {
            "civilian", "neutral", "neutral_for_killer", "killer", "self_select", "limit_break"
    };

    private final LotteryConfigRootScreen host;
    private final PlayerAssetsViewState viewState = new PlayerAssetsViewState();
    private final ScrollableButtonList playerList = new ScrollableButtonList();
    private final ScrollablePanel playerCardPanel = new ScrollablePanel();

    private final List<AbstractWidget> topBarWidgets = new ArrayList<>();
    private final List<AbstractWidget> dynamicWidgets = new ArrayList<>();
    private final List<AbstractWidget> cardEditWidgets = new ArrayList<>();
    private final Map<String, EditBox> cardStepBoxes = new LinkedHashMap<>();
    private final Map<String, EditBox> cardSetBoxes = new LinkedHashMap<>();

    private int selectedPlayerIndex;
    private String selectedPlayerUuid = "";
    private String playerSearchQuery = "";
    private String bulkDeltaDraft = "1";
    private String singleDeltaDraft = "1";
    private String singleSetDraft = "0";
    private String singleSetDraftUuid = "";
    private boolean playerCardsPage;
    private boolean cardMutationPending;
    private int cardMutationPendingTicks;
    private int searchDebounceTicks = -1;

    private boolean topBarBuilt;
    private int cachedY;

    private EditBox bulkDeltaBox;
    private EditBox playerSearchBox;
    private EditBox singleDeltaBox;
    private EditBox singleSetBox;
    private Button prevPageButton;
    private Button nextPageButton;
    private PlayerCardScrollLayout playerCardScrollLayout;

    PlayerAssetsAdminTab(LotteryConfigRootScreen host) {
        this.host = host;
    }

    private static Component tr(String suffix, Object... args) {
        return Component.translatable("screen.habitrain_lottery.config." + suffix, args);
    }

    void detach() {
        topBarBuilt = false;
        topBarWidgets.clear();
        dynamicWidgets.clear();
        cardEditWidgets.clear();
        prevPageButton = null;
        nextPageButton = null;
        playerCardScrollLayout = null;
    }

    boolean isNarrowDetail() {
        return host.consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW
                && viewState.narrowPage() == PlayerAssetsViewState.NarrowPage.DETAIL;
    }

    void tick() {
        if (cardMutationPending && ++cardMutationPendingTicks >= 200) {
            cardMutationPending = false;
            cardMutationPendingTicks = 0;
            host.setStatus(tr("cards.timeout").getString());
            refreshDynamicIfActive();
        }
        if (searchDebounceTicks >= 0 && --searchDebounceTicks < 0) {
            if (LotteryClientNetwork.canSendPlay() && ClientLotteryState.op) {
                requestPageSilent(0);
            }
        }
    }

    void onPlayerListUpdated() {
        cardMutationPending = false;
        cardMutationPendingTicks = 0;
        refreshDynamicIfActive();
    }

    void onAdminMessageArrived() {
        if (cardMutationPending) {
            cardMutationPending = false;
            cardMutationPendingTicks = 0;
            refreshDynamicIfActive();
        }
    }

    boolean requestCurrentPage() {
        return requestPageSilent(currentPage());
    }

    private boolean requestPageSilent(int page) {
        if (!LotteryClientNetwork.canSendPlay() || !ClientLotteryState.op) {
            return false;
        }
        return LotteryClientNetwork.clientRequestPlayerList(
                playerSearchQuery,
                Math.max(0, page),
                PlayerListRequestC2S.DEFAULT_PAGE_SIZE);
    }

    private void requestPageInteractive(int page) {
        if (!LotteryClientNetwork.canSendPlay()) {
            host.setStatus(tr("status.not_connected").getString());
            return;
        }
        if (!ClientLotteryState.op) {
            host.setStatus(tr("status.need_op").getString());
            return;
        }
        searchDebounceTicks = -1;
        if (requestPageSilent(page)) {
            host.setStatus(tr("players.status.requested_list").getString());
        } else {
            host.setStatus(tr("players.status.request_failed").getString());
        }
    }

    private int currentPage() {
        return Math.max(0, ClientLotteryState.playerListPage);
    }

    private int totalPages() {
        return Math.max(1, ClientLotteryState.playerListTotalPages);
    }

    private int totalMatched() {
        PlayerAdminModels.PlayerListSnapshot snap = ClientLotteryState.playerList;
        if (ClientLotteryState.playerListTotalMatched > 0) {
            return ClientLotteryState.playerListTotalMatched;
        }
        return snap == null || snap.players == null ? 0 : snap.players.size();
    }

    private List<PlayerAdminModels.PlayerRow> currentPlayerRows() {
        List<PlayerAdminModels.PlayerRow> players = ClientLotteryState.playerList == null
                ? List.of()
                : ClientLotteryState.playerList.players;
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        String q = playerSearchQuery == null ? "" : playerSearchQuery.trim();
        String serverQuery = ClientLotteryState.playerListQuery == null
                ? ""
                : ClientLotteryState.playerListQuery.trim();
        if (q.equalsIgnoreCase(serverQuery)) {
            return List.copyOf(players);
        }
        return PlayerAssetFilter.filter(players, q);
    }

    void build(int y) {
        this.cachedY = y;
        topBarWidgets.clear();
        dynamicWidgets.clear();
        cardEditWidgets.clear();

        Font font = host.font();
        int pageX = host.pageLeft();
        int pageR = host.pageRight();
        boolean narrow = host.consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW;

        int refreshW = 64;
        int backupX = pageX + refreshW + 4;
        int backupW = 64;
        int bulkX = backupX + backupW + 8;
        int bulkW = 36;
        int actionsX = bulkX + bulkW + 4;
        int actionW = Math.max(32, Math.min(52, (pageR - actionsX - 8) / 3));

        Button refreshListBtn = Button.builder(tr("players.refresh_list"),
                b -> requestPageInteractive(currentPage()))
                .bounds(pageX, y, refreshW, 20)
                .build();
        refreshListBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;
        topBarWidgets.add(host.addNavigationTabWidget(refreshListBtn));

        topBarWidgets.add(host.addTabWidget(Button.builder(tr("players.new_backup"), b -> {
            if (!ClientLotteryState.op) {
                host.setStatus(tr("status.need_op").getString());
                return;
            }
            if (!LotteryClientNetwork.clientRequestBackup()) {
                host.setStatus(tr("players.status.backup_failed").getString());
            } else {
                host.setStatus(tr("players.status.backup_requested").getString());
            }
        }).bounds(backupX, y, backupW, 20)
                .tooltip(Tooltip.create(tr("players.backup_hint")))
                .build()));

        if (bulkDeltaBox == null) {
            bulkDeltaBox = new EditBox(font, bulkX, y, bulkW, 20, tr("players.bulk_amount"));
            bulkDeltaBox.setMaxLength(10);
            bulkDeltaBox.setHint(Component.literal("N"));
            bulkDeltaBox.setValue(bulkDeltaDraft);
            bulkDeltaBox.setResponder(v -> bulkDeltaDraft = v == null ? "" : v);
        } else {
            placeBox(bulkDeltaBox, bulkX, y, bulkW, 20);
            syncUnfocusedValue(bulkDeltaBox, bulkDeltaDraft);
        }
        topBarWidgets.add(host.addTabWidget(bulkDeltaBox));

        topBarWidgets.add(host.addTabWidget(Button.builder(tr("players.online_add"), b ->
                modifyGreenApples("add_all_online", "", bulkDeltaBox, false, false))
                .bounds(actionsX, y, actionW, 20)
                .tooltip(Tooltip.create(tr("players.online_hint"))).build()));
        topBarWidgets.add(host.addTabWidget(Button.builder(tr("players.online_subtract"), b ->
                modifyGreenApples("add_all_online", "", bulkDeltaBox, true, false))
                .bounds(actionsX + actionW + 4, y, actionW, 20)
                .tooltip(Tooltip.create(tr("players.online_hint"))).build()));
        topBarWidgets.add(host.addTabWidget(Button.builder(tr("players.online_set"), b ->
                modifyGreenApples("set_all_online", "", bulkDeltaBox, false, true))
                .bounds(actionsX + (actionW + 4) * 2, y, actionW, 20)
                .tooltip(Tooltip.create(tr("players.online_hint"))).build()));

        int listW = narrow ? host.pageWidth() : Math.min(260, host.pageWidth() / 2);
        int searchW = searchBoxWidth(listW);
        if (playerSearchBox == null) {
            playerSearchBox = new EditBox(font, pageX, y + 24, searchW, 18, tr("players.search"));
            playerSearchBox.setMaxLength(64);
            playerSearchBox.setHint(tr("players.search"));
            playerSearchBox.setValue(playerSearchQuery == null ? "" : playerSearchQuery);
            playerSearchBox.setResponder(this::onSearchChanged);
        } else {
            placeBox(playerSearchBox, pageX, y + 24, searchW, 18);
            syncUnfocusedValue(playerSearchBox, playerSearchQuery == null ? "" : playerSearchQuery);
        }
        topBarWidgets.add(host.addNavigationTabWidget(playerSearchBox));

        prevPageButton = Button.builder(Component.literal("‹"),
                b -> requestPageInteractive(Math.max(0, currentPage() - 1)))
                .bounds(pageX + searchW + 4, y + 24, 18, 18)
                .build();
        nextPageButton = Button.builder(Component.literal("›"),
                b -> requestPageInteractive(currentPage() + 1))
                .bounds(pageX + searchW + 24, y + 24, 18, 18)
                .build();
        topBarWidgets.add(host.addNavigationTabWidget(prevPageButton));
        topBarWidgets.add(host.addNavigationTabWidget(nextPageButton));

        topBarBuilt = true;
        rebuildDynamicListAndDetail();
    }

    private int searchBoxWidth(int listW) {
        return Math.max(72, listW - 114);
    }

    private void onSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(playerSearchQuery)) {
            return;
        }
        List<PlayerAdminModels.PlayerRow> before = currentPlayerRows();
        if (selectedPlayerIndex >= 0 && selectedPlayerIndex < before.size()) {
            PlayerAdminModels.PlayerRow row = before.get(selectedPlayerIndex);
            if (row != null && row.uuid != null) {
                selectedPlayerUuid = row.uuid;
            }
        }
        playerSearchQuery = next;
        playerList.setScroll(0);
        if (host.consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW) {
            viewState.setNarrowPage(PlayerAssetsViewState.NarrowPage.LIST);
        }
        if (LotteryClientNetwork.canSendPlay() && ClientLotteryState.op) {
            searchDebounceTicks = SEARCH_DEBOUNCE_TICKS;
        }
        refreshDynamicIfActive();
    }

    private void refreshDynamicIfActive() {
        if (!topBarBuilt) {
            host.rebuildActiveTab();
            return;
        }
        rebuildDynamicListAndDetail();
        host.applyOpWidgetLocks();
        refreshCardLocks();
    }

    private void clearDynamicWidgets() {
        for (AbstractWidget w : dynamicWidgets) {
            host.removeTabWidget(w);
        }
        dynamicWidgets.clear();
        cardEditWidgets.clear();
        playerCardScrollLayout = null;
    }

    private <T extends AbstractWidget> T addDynamic(T widget) {
        dynamicWidgets.add(widget);
        return host.addTabWidget(widget);
    }

    private <T extends AbstractWidget> T addDynamicNav(T widget) {
        dynamicWidgets.add(widget);
        return host.addNavigationTabWidget(widget);
    }

    private void updatePaginationButtons(boolean visible) {
        boolean canPage = visible && LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;
        Component tip = tr("players.page_info", currentPage() + 1, totalPages(), totalMatched());
        if (prevPageButton != null) {
            prevPageButton.visible = visible;
            prevPageButton.active = canPage && currentPage() > 0;
            prevPageButton.setTooltip(Tooltip.create(tip));
        }
        if (nextPageButton != null) {
            nextPageButton.visible = visible;
            nextPageButton.active = canPage && currentPage() + 1 < totalPages();
            nextPageButton.setTooltip(Tooltip.create(tip));
        }
    }

    private void rebuildDynamicListAndDetail() {
        clearDynamicWidgets();

        int y = cachedY;
        int pageX = host.pageLeft();
        int pageR = host.pageRight();
        boolean narrow = host.consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW;

        String keepUuid = selectedPlayerUuid;
        List<PlayerAdminModels.PlayerRow> players = currentPlayerRows();
        boolean narrowDetail = narrow
                && viewState.narrowPage() == PlayerAssetsViewState.NarrowPage.DETAIL
                && !players.isEmpty();
        if ((keepUuid == null || keepUuid.isBlank())
                && selectedPlayerIndex >= 0
                && selectedPlayerIndex < players.size()) {
            PlayerAdminModels.PlayerRow prev = players.get(selectedPlayerIndex);
            if (prev != null && prev.uuid != null && !prev.uuid.isBlank()) {
                keepUuid = prev.uuid;
            }
        }

        for (AbstractWidget w : topBarWidgets) {
            w.visible = !narrowDetail;
        }
        updatePaginationButtons(!narrowDetail);

        int listTop = narrowDetail ? host.consoleLayout().content().y() + 4 : y + 46;
        int listH = Math.max(ROW_H, host.pageBottom() - listTop);
        int playerCount = players.size();
        if (playerCount > 0) {
            int restored = PlayerAssetFilter.indexOfUuid(players, keepUuid);
            selectedPlayerIndex = restored >= 0 ? restored : 0;
            PlayerAdminModels.PlayerRow selRow = players.get(selectedPlayerIndex);
            selectedPlayerUuid = selRow != null && selRow.uuid != null ? selRow.uuid : "";
        } else {
            selectedPlayerIndex = 0;
        }

        int listW = narrow ? host.pageWidth() : Math.min(260, host.pageWidth() / 2);
        List<String> playerLabels = new ArrayList<>(playerCount);
        for (int i = 0; i < playerCount; i++) {
            PlayerAdminModels.PlayerRow row = players.get(i);
            String mark = i == selectedPlayerIndex ? "§a" : (row.online ? "§f" : "§7");
            playerLabels.add(mark + (row.online ? "● " : "○ ")
                    + tr("players.row_label",
                    LotteryConfigRootScreen.shortName(row.name, 12),
                    row.greenApples).getString());
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
                    viewState.setNarrowPage(PlayerAssetsViewState.NarrowPage.DETAIL);
                }
            }
            refreshDynamicIfActive();
        });
        if (!narrowDetail) {
            playerList.rebuildWidgets(this::addDynamicNav, null);
        }

        if (narrow && !narrowDetail) {
            if (players.isEmpty()) {
                List<PlayerAdminModels.PlayerRow> allPlayers = ClientLotteryState.playerList == null
                        ? List.of()
                        : ClientLotteryState.playerList.players;
                boolean hasAny = allPlayers != null && !allPlayers.isEmpty();
                if (!hasAny) {
                    Button emptyBtn = addDynamicNav(Button.builder(tr("players.no_data"),
                            b -> requestPageInteractive(0))
                            .bounds(pageX, listTop, Math.min(host.pageWidth(), 220), 20).build());
                    emptyBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;
                }
            }
            return;
        }

        int px = narrow ? pageX : pageX + listW + 20;
        int pw = pageR - px;
        int py = listTop;
        if (!players.isEmpty()) {
            PlayerAdminModels.PlayerRow sel = players.get(selectedPlayerIndex);
            viewState.beginCardPlayer(sel.uuid);
            int tabX = px;
            int tabY = py + 42;
            int tabW = Math.max(64, Math.min(92, (pw - 4) / 2));
            if (narrow) {
                int backW = Math.min(92, Math.max(72, pw / 4));
                Component backLabel = Component.literal("‹ " + LotteryConfigRootScreen.shortName(sel.name, 8));
                addDynamicNav(Button.builder(backLabel, b -> {
                            viewState.setNarrowPage(PlayerAssetsViewState.NarrowPage.LIST);
                            refreshDynamicIfActive();
                        }).bounds(px, py, backW, 20)
                        .tooltip(Tooltip.create(tr("players.back_to_list"))).build());
                tabX = px + backW + 4;
                tabY = py;
                tabW = Math.max(48, (Math.max(0, pw - backW - 8)) / 2);
            }
            addDynamicNav(Button.builder(Component.literal((playerCardsPage ? "○ " : "● ")
                            + tr("players.base_assets").getString()), b -> {
                        playerCardsPage = false;
                        refreshDynamicIfActive();
                    }).bounds(tabX, tabY, tabW, 20).build());
            addDynamicNav(Button.builder(Component.literal((playerCardsPage ? "● " : "○ ")
                            + tr("players.role_cards").getString()), b -> {
                        playerCardsPage = true;
                        viewState.setCardScroll(0);
                        refreshDynamicIfActive();
                    }).bounds(tabX + tabW + 4, tabY, tabW, 20).build());

            if (playerCardsPage) {
                buildPlayerCardControls(sel, px, narrow ? py + 24 : py + 70, pw);
            } else {
                int baseY = narrow ? py + 24 : py + 72;
                Font font = host.font();
                if (singleDeltaBox == null) {
                    singleDeltaBox = new EditBox(font, px, baseY, 50, 20, tr("players.single_delta"));
                    singleDeltaBox.setMaxLength(10);
                    singleDeltaBox.setHint(Component.literal("N"));
                    singleDeltaBox.setValue(singleDeltaDraft);
                    singleDeltaBox.setResponder(v -> singleDeltaDraft = v == null ? "" : v);
                } else {
                    placeBox(singleDeltaBox, px, baseY, 50, 20);
                    syncUnfocusedValue(singleDeltaBox, singleDeltaDraft);
                }
                addDynamic(singleDeltaBox);

                addDynamic(Button.builder(tr("players.single_add"), b ->
                        modifyGreenApples("add_one", sel.uuid, singleDeltaBox, false, false))
                        .bounds(px + 54, baseY, 44, 20).build());
                addDynamic(Button.builder(tr("players.single_subtract"), b ->
                        modifyGreenApples("add_one", sel.uuid, singleDeltaBox, true, false))
                        .bounds(px + 102, baseY, 44, 20).build());

                boolean playerChanged = !Objects.equals(singleSetDraftUuid, sel.uuid);
                if (playerChanged) {
                    singleSetDraftUuid = sel.uuid == null ? "" : sel.uuid;
                    singleSetDraft = String.valueOf(sel.greenApples);
                }
                if (singleSetBox == null) {
                    singleSetBox = new EditBox(font, px, baseY + 26, 50, 20, tr("cards.set"));
                    singleSetBox.setMaxLength(10);
                    singleSetBox.setHint(Component.literal("="));
                    singleSetBox.setValue(singleSetDraft);
                    singleSetBox.setResponder(v -> singleSetDraft = v == null ? "" : v);
                } else {
                    placeBox(singleSetBox, px, baseY + 26, 50, 20);
                    if (playerChanged) {
                        singleSetBox.setValue(singleSetDraft);
                    } else if (!singleSetBox.isFocused()) {
                        singleSetDraft = String.valueOf(sel.greenApples);
                        syncUnfocusedValue(singleSetBox, singleSetDraft);
                    }
                }
                addDynamic(singleSetBox);

                addDynamic(Button.builder(tr("cards.set"), b ->
                        modifyGreenApples("set_one", sel.uuid, singleSetBox, false, true))
                        .bounds(px + 54, baseY + 26, 50, 20).build());
            }
        } else {
            List<PlayerAdminModels.PlayerRow> allPlayers = ClientLotteryState.playerList == null
                    ? List.of()
                    : ClientLotteryState.playerList.players;
            boolean hasAny = allPlayers != null && !allPlayers.isEmpty();
            if (!hasAny) {
                Button emptyBtn = addDynamicNav(Button.builder(tr("players.no_data"),
                        b -> requestPageInteractive(0))
                        .bounds(px, py, Math.min(190, pw), 20).build());
                emptyBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;
            }
        }
    }

    private void buildPlayerCardControls(PlayerAdminModels.PlayerRow player, int x, int y, int width) {
        Font font = host.font();
        int controlWidth = Math.max(0, width - 6);
        playerCardScrollLayout = PlayerCardScrollLayout.calculate(
                y, host.pageBottom() - 14, controlWidth, CARD_KEYS.length);
        playerCardPanel.setBounds(
                x, playerCardScrollLayout.viewportTop(), width,
                Math.max(1, playerCardScrollLayout.viewportHeight()));
        playerCardPanel.setContentHeight(playerCardScrollLayout.contentHeight());
        playerCardPanel.setScroll(viewState.cardScroll());
        viewState.setCardScroll(playerCardPanel.getScroll());

        int scroll = playerCardPanel.getScroll();
        boolean stacked = playerCardScrollLayout.stacked();
        int labelWidth = stacked ? controlWidth : Math.max(78, controlWidth - 202);
        int stepX = stacked ? x : x + labelWidth;
        for (int i = 0; i < CARD_KEYS.length; i++) {
            String key = CARD_KEYS[i];
            int rowY = playerCardScrollLayout.controlY(i, scroll);
            boolean rowVisible = playerCardScrollLayout.isWidgetFullyVisible(rowY, 20);
            int stepWidth = stacked ? 34 : 36;
            int minusX = stepX + stepWidth + 4;
            int plusX = minusX + 28;
            int setX = plusX + 28;
            int setWidth = stacked ? 38 : 44;
            int setButtonX = setX + setWidth + 4;
            int setButtonWidth = stacked ? 42 : 48;

            EditBox step = cardStepBoxes.get(key);
            if (step == null) {
                step = new EditBox(font, stepX, rowY, stepWidth, 20, tr("cards.step"));
                step.setMaxLength(6);
                step.setHint(Component.literal("±N"));
                step.setValue(viewState.cardStepDraft(key, "1"));
                step.setResponder(value -> viewState.setCardStepDraft(key, value));
                cardStepBoxes.put(key, step);
            } else {
                placeBox(step, stepX, rowY, stepWidth, 20);
                syncUnfocusedValue(step, viewState.cardStepDraft(key, "1"));
            }
            step.visible = rowVisible;
            addDynamic(step);
            cardEditWidgets.add(step);

            Button decrease = addDynamic(Button.builder(Component.literal("−"), b ->
                            sendCardStepMutation(player, key, -1))
                    .bounds(minusX, rowY, 24, 20)
                    .tooltip(Tooltip.create(tr("cards.decrease"))).build());
            decrease.visible = rowVisible;
            Button increase = addDynamic(Button.builder(Component.literal("+"), b ->
                            sendCardStepMutation(player, key, 1))
                    .bounds(plusX, rowY, 24, 20)
                    .tooltip(Tooltip.create(tr("cards.increase"))).build());
            increase.visible = rowVisible;
            cardEditWidgets.add(decrease);
            cardEditWidgets.add(increase);

            int current = cardCount(player, key);
            EditBox set = cardSetBoxes.get(key);
            String desiredSet = viewState.cardSetDraft(key, String.valueOf(current));
            if (set == null) {
                set = new EditBox(font, setX, rowY, setWidth, 20, tr("cards.set"));
                set.setMaxLength(6);
                set.setValue(desiredSet);
                set.setResponder(value -> viewState.setCardSetDraft(key, value));
                cardSetBoxes.put(key, set);
            } else {
                placeBox(set, setX, rowY, setWidth, 20);
                syncUnfocusedValue(set, desiredSet);
            }
            set.visible = rowVisible;
            addDynamic(set);
            cardEditWidgets.add(set);

            Button setButton = addDynamic(Button.builder(tr("cards.set"), b ->
                            sendCardSetMutation(player, key))
                    .bounds(setButtonX, rowY, setButtonWidth, 20).build());
            setButton.visible = rowVisible;
            cardEditWidgets.add(setButton);
        }
    }

    private void sendCardStepMutation(PlayerAdminModels.PlayerRow player, String key, int direction) {
        Integer step = readWholeNumber(cardStepBoxes.get(key), 1, 1000, tr("players.field.card_step"));
        if (step != null) {
            sendCardMutation(player, key, "ADD", direction * step);
        }
    }

    private void sendCardSetMutation(PlayerAdminModels.PlayerRow player, String key) {
        Integer value = readWholeNumber(cardSetBoxes.get(key), 0, 100000, tr("players.field.card_set"));
        if (value != null) {
            sendCardMutation(player, key, "SET", value);
        }
    }

    private void modifyGreenApples(
            String mode,
            String playerUuid,
            EditBox input,
            boolean subtract,
            boolean allowZero
    ) {
        Integer amount = readWholeNumber(input, allowZero ? 0 : 1, Integer.MAX_VALUE, tr("players.field.green_apples"));
        if (amount == null) {
            return;
        }
        int value = subtract ? -amount : amount;
        host.setStatus(LotteryClientNetwork.clientModifyGreenApples(mode, playerUuid, value)
                ? tr("players.status.apples_submitted").getString()
                : tr("players.status.apples_failed").getString());
    }

    private Integer readWholeNumber(EditBox input, int min, int max, Component label) {
        if (input != null) {
            try {
                int value = Integer.parseInt(input.getValue().trim());
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (RuntimeException ignored) {
            }
        }
        host.setStatus(tr("players.status.invalid_number", label.getString(), min, max).getString());
        return null;
    }

    private static int cardCount(PlayerAdminModels.PlayerRow player, String key) {
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
            host.setStatus(tr("cards.pending").getString());
            return;
        }
        if (player == null || "CORRUPT".equalsIgnoreCase(player.cardStatus)) {
            host.setStatus(tr("cards.corrupt").getString());
            return;
        }
        if (!host.editable()) {
            host.setStatus(tr("cards.readonly").getString());
            return;
        }
        if (LotteryClientNetwork.clientModifyPlayerCard(player.uuid, key, operation, value)) {
            cardMutationPending = true;
            cardMutationPendingTicks = 0;
            host.setStatus(tr("cards.pending").getString());
            refreshDynamicIfActive();
        } else {
            host.setStatus(tr("players.status.card_failed").getString());
        }
    }

    void refreshCardLocks() {
        if (!playerCardsPage || cardEditWidgets.isEmpty()) {
            return;
        }
        List<PlayerAdminModels.PlayerRow> rows = currentPlayerRows();
        PlayerAdminModels.PlayerRow player = selectedPlayerIndex >= 0 && selectedPlayerIndex < rows.size()
                ? rows.get(selectedPlayerIndex) : null;
        boolean editable = player != null
                && !"CORRUPT".equalsIgnoreCase(player.cardStatus)
                && host.editable()
                && !cardMutationPending;
        for (AbstractWidget widget : cardEditWidgets) {
            widget.active = editable;
        }
    }

    void renderBeforeWidgets(GuiGraphics g, ConfigConsoleLayout layout, int contentY) {
        Font font = host.font();
        List<PlayerAdminModels.PlayerRow> players = currentPlayerRows();
        boolean narrow = layout.mode() == ConfigConsoleLayout.Mode.NARROW;
        boolean showNarrowDetail = narrow
                && viewState.narrowPage() == PlayerAssetsViewState.NarrowPage.DETAIL
                && !players.isEmpty();

        int listW = narrow ? host.pageWidth() : Math.min(260, host.pageWidth() / 2);
        if (!showNarrowDetail) {
            int searchW = searchBoxWidth(listW);
            int pageLabelX = host.pageLeft() + searchW + 46;
            int pageLabelMaxW = Math.max(24, host.pageLeft() + listW - pageLabelX);
            String pageText = tr("players.page_short", currentPage() + 1, totalPages(), totalMatched()).getString();
            g.drawString(font, font.plainSubstrByWidth(pageText, pageLabelMaxW),
                    pageLabelX, contentY + 29, 0xFF8A92A0, false);
        }

        if (narrow && !showNarrowDetail) {
            players = List.of();
        }
        int px = narrow ? host.pageLeft() : host.pageLeft() + listW + 20;
        int py = showNarrowDetail ? layout.content().y() + 4 : contentY + 46;
        if (!players.isEmpty() && selectedPlayerIndex >= 0 && selectedPlayerIndex < players.size()) {
            PlayerAdminModels.PlayerRow sel = players.get(selectedPlayerIndex);
            if (!narrow) {
                String stateLabel = sel.online ? "§a" + tr("players.online").getString()
                        : "§7" + tr("players.offline").getString();
                g.drawString(font, tr("players.selected_summary", sel.name, stateLabel),
                        px, py, 0xFFFFFFFF, false);
                g.drawString(font, tr("players.uuid_summary", LotteryConfigRootScreen.shortName(sel.uuid, 18)),
                        px, py + 12, 0xFF8A92A0, false);
                g.drawString(font, tr("players.assets_summary", sel.greenApples, sel.unlockCount),
                        px, py + 26, 0xFFD4A55A, false);
            }
            if (playerCardsPage) {
                PlayerCardScrollLayout cardLayout = playerCardScrollLayout;
                if (cardLayout != null && cardLayout.viewportHeight() > 0) {
                    int scroll = playerCardPanel.getScroll();
                    int labelWidth = cardLayout.stacked()
                            ? cardLayout.width()
                            : Math.max(78, cardLayout.width() - 202);
                    g.enableScissor(px, cardLayout.viewportTop(), host.pageRight(), cardLayout.viewportBottom());
                    try {
                        for (int i = 0; i < CARD_KEYS.length; i++) {
                            String key = CARD_KEYS[i];
                            String label = tr("cards." + key).getString() + "  ×" + cardCount(sel, key);
                            label = font.plainSubstrByWidth(label, Math.max(20, labelWidth - 4));
                            int labelY = cardLayout.labelY(i, scroll);
                            if (cardLayout.isTextVisible(labelY, font.lineHeight)) {
                                g.drawString(font, label, px, labelY, 0xFFE4E9ED, false);
                            }
                        }
                        int messageY = cardLayout.statusY(scroll);
                        if ("CORRUPT".equalsIgnoreCase(sel.cardStatus)) {
                            g.drawString(font, tr("cards.corrupt"), px, messageY, 0xFFFF6B6B, false);
                        } else if ("MISSING".equalsIgnoreCase(sel.cardStatus)) {
                            g.drawString(font, tr("cards.missing"), px, messageY, 0xFFD4A55A, false);
                        } else if (cardMutationPending) {
                            g.drawString(font, tr("cards.pending"), px, messageY, 0xFF57C6D6, false);
                        }
                    } finally {
                        g.disableScissor();
                    }
                }
            }
        } else if (narrow && currentPlayerRows().isEmpty()
                && ((ClientLotteryState.playerList != null
                && ClientLotteryState.playerList.players != null
                && !ClientLotteryState.playerList.players.isEmpty())
                || (playerSearchQuery != null && !playerSearchQuery.isBlank()))) {
            g.drawString(font, tr("players.no_match"), host.pageLeft(), contentY + 46, 0xFF8A92A0, false);
        } else if (!narrow) {
            Component emptyHint = (playerSearchQuery != null && !playerSearchQuery.isBlank())
                    ? tr("players.no_match")
                    : tr("players.empty_hint");
            g.drawString(font, emptyHint, px, py, 0xFF8A92A0, false);
        }
    }

    void renderAfterWidgets(GuiGraphics g) {
        if (!isNarrowDetail()) {
            playerList.renderScrollbar(g);
        }
        if (playerCardsPage && playerCardScrollLayout != null && playerCardScrollLayout.viewportHeight() > 0) {
            playerCardPanel.renderScrollbar(g);
        }
    }

    boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (playerCardsPage && playerCardScrollLayout != null && playerCardScrollLayout.viewportHeight() > 0) {
            int beforeCards = playerCardPanel.getScroll();
            int rows = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (rows != 0 && playerCardPanel.isMouseOver(mouseX, mouseY)
                    && playerCardScrollLayout.maxScroll() > 0) {
                int next = playerCardScrollLayout.scrollByRows(beforeCards, rows);
                playerCardPanel.setScroll(next);
                if (next != beforeCards) {
                    viewState.setCardScroll(next);
                    refreshDynamicIfActive();
                }
                return true;
            }
        }
        if (isNarrowDetail()) {
            return false;
        }
        int before = playerList.getScroll();
        if (playerList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            if (playerList.getScroll() != before) {
                refreshDynamicIfActive();
            }
            return true;
        }
        return false;
    }

    boolean keyPressed(int keyCode) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && playerSearchBox != null
                && playerSearchBox.isFocused()
                && playerSearchBox.visible) {
            if (LotteryClientNetwork.canSendPlay() && ClientLotteryState.op) {
                requestPageInteractive(0);
            }
            return true;
        }
        return false;
    }

    private static void placeBox(EditBox box, int x, int y, int w, int h) {
        box.setX(x);
        box.setY(y);
        box.setWidth(Math.max(1, w));
        box.setHeight(Math.max(1, h));
    }

    private static void syncUnfocusedValue(EditBox box, String desired) {
        String safe = desired == null ? "" : desired;
        if (!box.isFocused() && !Objects.equals(box.getValue(), safe)) {
            box.setValue(safe);
        }
    }
}
