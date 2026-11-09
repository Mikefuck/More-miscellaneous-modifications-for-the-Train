package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.network.ClientLotteryState;
import com.habitrain.lottery.network.PlayerAdminModels;
import com.habitrain.lottery.network.TitlePlayerPageRequestC2S;
import com.habitrain.lottery.title.TitleCatalog;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracted title template library & player title management tab for {@link LotteryConfigRootScreen}
 * ({@code N-01}, {@code WP-05}, {@code I-10}, {@code I-22}, {@code I-24.1}, {@code I-24.3}, {@code N-02}).
 */
final class TitleAdminTab {
    private static final Gson GSON = new Gson();
    private static final int ROW_H = 22;
    private static final int SEARCH_DEBOUNCE_TICKS = 7;

    private record PlayerItem(String uuid, String name, boolean online) {
    }

    private final LotteryConfigRootScreen host;
    private final ScrollableButtonList titleCatalogList = new ScrollableButtonList();
    private final ScrollableButtonList titlePlayerList = new ScrollableButtonList();
    private final ScrollableButtonList titleOwnedList = new ScrollableButtonList();

    private final List<AbstractWidget> dynamicPlayerWidgets = new ArrayList<>();

    private TitleCatalog workingTitleCatalog = new TitleCatalog();
    private long loadedCatalogRevision = 0L;
    private String lastLoadedCatalogJson = null;
    private boolean titleCatalogDirty;
    private boolean bindingCatalogFields;

    private int selectedTitleCatalogIndex;
    private int selectedTitleOwnedIndex;
    private int selectedTitlePlayerIndex;
    private String selectedTitlePlayerUuid = "";
    private String titlePlayerSearchQuery = "";
    private int titleNarrowPage;
    private String titleCustomDraft = "";
    private int searchDebounceTicks = -1;

    private boolean built;
    private int cachedY;
    private int cachedH;

    private EditBox titleIdBox;
    private EditBox titleDisplayBox;
    private EditBox titleCustomBox;
    private EditBox titlePlayerSearchBox;
    private Button prevPageButton;
    private Button nextPageButton;

    TitleAdminTab(LotteryConfigRootScreen host) {
        this.host = host;
    }

    private static Component tr(String suffix, Object... args) {
        return Component.translatable("screen.habitrain_lottery.config." + suffix, args);
    }

    void detach() {
        built = false;
        dynamicPlayerWidgets.clear();
        prevPageButton = null;
        nextPageButton = null;
    }

    boolean isCatalogDirty() {
        return titleCatalogDirty;
    }

    int narrowPage() {
        return titleNarrowPage;
    }

    void tick() {
        if (searchDebounceTicks >= 0 && --searchDebounceTicks < 0) {
            if (LotteryClientNetwork.canSendPlay() && ClientLotteryState.op) {
                requestPlayerPageSilent(0);
            }
        }
    }

    void onTitleStateUpdated(boolean activeTab) {
        String latestCatalogJson = ClientLotteryState.titleCatalogJson;
        String safeCatalog = latestCatalogJson == null ? "" : latestCatalogJson;
        boolean catalogChanged = !Objects.equals(lastLoadedCatalogJson, safeCatalog);
        if (catalogChanged) {
            if (!titleCatalogDirty || titleCatalogMatchesSnapshot(safeCatalog)) {
                loadWorkingTitleCatalogFromState();
                if (activeTab) {
                    host.rebuildActiveTab();
                    return;
                }
            }
        }
        if (activeTab) {
            if (built) {
                rebuildDynamicPlayerAndOwned();
                host.applyOpWidgetLocks();
            } else {
                host.rebuildActiveTab();
            }
        }
    }

    void onPlayerListUpdated(boolean activeTab) {
        if (activeTab && LotteryClientNetwork.cachedTitlePlayers().isEmpty()) {
            if (built) {
                rebuildDynamicPlayerAndOwned();
                host.applyOpWidgetLocks();
            } else {
                host.rebuildActiveTab();
            }
        }
    }

    void loadWorkingTitleCatalogFromState() {
        String json = ClientLotteryState.titleCatalogJson;
        String safe = json == null ? "" : json;
        TitleCatalog parsed = null;
        try {
            if (!safe.isBlank()) {
                parsed = GSON.fromJson(safe, TitleCatalog.class);
            }
        } catch (Exception ignored) {
        }
        workingTitleCatalog = parsed != null ? parsed : new TitleCatalog();
        if (workingTitleCatalog.titles == null) {
            workingTitleCatalog.titles = new ArrayList<>();
        }
        loadedCatalogRevision = Math.max(0L, workingTitleCatalog.revision);
        lastLoadedCatalogJson = safe;
        titleCatalogDirty = false;
    }

    private boolean titleCatalogMatchesSnapshot(String snapshotJson) {
        try {
            if (snapshotJson == null || snapshotJson.isBlank()) {
                return false;
            }
            TitleCatalog snapshot = GSON.fromJson(snapshotJson, TitleCatalog.class);
            if (snapshot == null) {
                return false;
            }
            return entriesMatchIgnoringRevision(snapshot.titles, workingTitleCatalog.titles);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean entriesMatchIgnoringRevision(
            List<TitleCatalog.TitleEntry> a,
            List<TitleCatalog.TitleEntry> b
    ) {
        List<TitleCatalog.TitleEntry> left = a == null ? List.of() : a;
        List<TitleCatalog.TitleEntry> right = b == null ? List.of() : b;
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            TitleCatalog.TitleEntry ea = left.get(i);
            TitleCatalog.TitleEntry eb = right.get(i);
            if (ea == null || eb == null) {
                if (ea != eb) return false;
                continue;
            }
            if (!Objects.equals(ea.id == null ? "" : ea.id.trim(), eb.id == null ? "" : eb.id.trim())) {
                return false;
            }
            if (!Objects.equals(ea.display == null ? "" : ea.display, eb.display == null ? "" : eb.display)) {
                return false;
            }
            if (ea.enabled != eb.enabled) {
                return false;
            }
        }
        return true;
    }

    void refreshTitlesInteractive() {
        flushFieldsToModel();
        if (!LotteryClientNetwork.canSendPlay()) {
            host.setStatus(tr("status.not_connected").getString());
            return;
        }
        if (!ClientLotteryState.op) {
            host.setStatus(tr("status.need_op").getString());
            return;
        }
        if (titleCatalogDirty) {
            host.confirmDiscardDirtyTitles(this::discardDirtyAndRefreshFromServer);
            return;
        }
        host.setStatus(LotteryClientNetwork.clientRequestTitleSnapshot()
                ? tr("titles.status.requested_snapshot").getString()
                : tr("titles.status.request_failed").getString());
    }

    private void discardDirtyAndRefreshFromServer() {
        titleCatalogDirty = false;
        titleIdBox = null;
        titleDisplayBox = null;
        loadWorkingTitleCatalogFromState();
        boolean requested = LotteryClientNetwork.canSendPlay()
                && ClientLotteryState.op
                && LotteryClientNetwork.clientRequestTitleSnapshot();
        host.returnFromModalAndRefreshStatus(requested
                ? tr("titles.status.requested_snapshot").getString()
                : tr("titles.status.request_failed").getString());
    }

    boolean requestPlayerPageSilent(int page) {
        if (!LotteryClientNetwork.canSendPlay() || !ClientLotteryState.op) {
            return false;
        }
        return LotteryClientNetwork.clientRequestTitlePlayerPage(
                titlePlayerSearchQuery,
                Math.max(0, page),
                TitlePlayerPageRequestC2S.DEFAULT_PAGE_SIZE);
    }

    private void requestPlayerPageInteractive(int page) {
        if (!LotteryClientNetwork.canSendPlay()) {
            host.setStatus(tr("status.not_connected").getString());
            return;
        }
        if (!ClientLotteryState.op) {
            host.setStatus(tr("status.need_op").getString());
            return;
        }
        searchDebounceTicks = -1;
        if (requestPlayerPageSilent(page)) {
            host.setStatus(tr("players.status.requested_list").getString());
        } else {
            host.setStatus(tr("players.status.request_failed").getString());
        }
    }

    private int currentPage() {
        return Math.max(0, ClientLotteryState.titlePlayersPage);
    }

    private int totalPages() {
        return Math.max(1, ClientLotteryState.titlePlayersTotalPages);
    }

    private int totalMatched() {
        if (ClientLotteryState.titlePlayersTotalMatched > 0) {
            return ClientLotteryState.titlePlayersTotalMatched;
        }
        return LotteryClientNetwork.cachedTitlePlayers().size();
    }

    private void ensureWorkingTitleCatalog() {
        if (workingTitleCatalog == null) {
            workingTitleCatalog = new TitleCatalog();
        }
        if (workingTitleCatalog.titles == null) {
            workingTitleCatalog.titles = new ArrayList<>();
        }
        if (lastLoadedCatalogJson == null
                && ClientLotteryState.titleCatalogJson != null
                && !ClientLotteryState.titleCatalogJson.isBlank()) {
            loadWorkingTitleCatalogFromState();
        }
    }

    private String nextTitleTemplateId() {
        Set<String> used = new HashSet<>();
        for (TitleCatalog.TitleEntry entry : workingTitleCatalog.titles) {
            if (entry != null && entry.id != null) {
                used.add(entry.id.trim());
            }
        }
        int n = 1;
        while (used.contains("title_" + n)) {
            n++;
        }
        return "title_" + n;
    }

    private List<PlayerItem> titlePlayerRows() {
        Map<String, LotteryClientNetwork.TitlePlayerSummary> cached = LotteryClientNetwork.cachedTitlePlayers();
        List<PlayerItem> base = new ArrayList<>();
        if (!cached.isEmpty()) {
            for (LotteryClientNetwork.TitlePlayerSummary summary : cached.values()) {
                base.add(new PlayerItem(summary.uuid(), summary.name(), summary.online()));
            }
        } else if (ClientLotteryState.playerList != null
                && ClientLotteryState.playerList.players != null) {
            for (PlayerAdminModels.PlayerRow row : ClientLotteryState.playerList.players) {
                if (row != null) {
                    base.add(new PlayerItem(
                            row.uuid == null ? "" : row.uuid,
                            row.name == null ? "" : row.name,
                            row.online));
                }
            }
        }
        if (base.isEmpty()) {
            return List.of();
        }
        String q = titlePlayerSearchQuery == null ? "" : titlePlayerSearchQuery.toLowerCase(Locale.ROOT).trim();
        String serverQ = ClientLotteryState.titlePlayersQuery == null
                ? ""
                : ClientLotteryState.titlePlayersQuery.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty() || (!cached.isEmpty() && q.equals(serverQ))) {
            return base;
        }
        List<PlayerItem> filtered = new ArrayList<>();
        for (PlayerItem item : base) {
            String name = item.name().toLowerCase(Locale.ROOT);
            String uuid = item.uuid().toLowerCase(Locale.ROOT);
            if (name.contains(q) || uuid.contains(q)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private String titlePlayerCurrent(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "";
        }
        return LotteryClientNetwork.cachedTitlePlayerCurrent().getOrDefault(uuid, "");
    }

    private List<String> titlePlayerOwned(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return List.of();
        }
        return LotteryClientNetwork.cachedTitlePlayerOwned().getOrDefault(uuid, List.of());
    }

    void flushFieldsToModel() {
        if (bindingCatalogFields) return;
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
            String next = titleIdBox.getValue() == null ? "" : titleIdBox.getValue().trim();
            titleCatalogDirty |= !Objects.equals(e.id, next);
            e.id = next;
        }
        if (titleDisplayBox != null) {
            String next = titleDisplayBox.getValue() == null ? "" : titleDisplayBox.getValue();
            titleCatalogDirty |= !Objects.equals(e.display, next);
            e.display = next;
        }
    }

    private void bindCatalogFields(TitleCatalog.TitleEntry selected) {
        bindingCatalogFields = true;
        try {
            String id = selected == null || selected.id == null ? "" : selected.id;
            String display = selected == null || selected.display == null ? "" : selected.display;
            // Selection changes must also replace a previously focused field.
            if (!Objects.equals(titleIdBox.getValue(), id)) titleIdBox.setValue(id);
            if (!Objects.equals(titleDisplayBox.getValue(), display)) titleDisplayBox.setValue(display);
        } finally {
            bindingCatalogFields = false;
        }
    }

    boolean validateCatalogForSave() {
        ensureWorkingTitleCatalog();
        flushFieldsToModel();
        if (workingTitleCatalog.titles.size() > TitleCatalog.MAX_TITLES) {
            host.setStatus(tr("titles.status.too_many", TitleCatalog.MAX_TITLES).getString());
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (TitleCatalog.TitleEntry e : workingTitleCatalog.titles) {
            if (e == null) {
                continue;
            }
            String id = e.id == null ? "" : e.id.trim();
            if (id.isEmpty()) {
                host.setStatus(tr("titles.status.empty_id").getString());
                return false;
            }
            if (id.length() > TitleCatalog.MAX_ID_LENGTH) {
                host.setStatus(tr("titles.status.id_too_long", TitleCatalog.MAX_ID_LENGTH).getString());
                return false;
            }
            if (!seen.add(id)) {
                host.setStatus(tr("titles.status.duplicate_id", id).getString());
                return false;
            }
            String display = e.display == null ? "" : e.display;
            if (display.length() > TitleCatalog.MAX_DISPLAY_LENGTH) {
                host.setStatus(tr("titles.status.display_too_long", TitleCatalog.MAX_DISPLAY_LENGTH, id).getString());
                return false;
            }
            e.id = id;
            e.display = display;
        }
        if (workingTitleCatalog.version <= 0) {
            workingTitleCatalog.version = 1;
        }
        workingTitleCatalog.revision = loadedCatalogRevision;
        return true;
    }

    void saveCatalogToServer() {
        if (!LotteryClientNetwork.canSendPlay()) {
            host.setStatus(tr("status.not_connected").getString());
            return;
        }
        if (!ClientLotteryState.op) {
            host.setStatus(tr("status.need_op").getString());
            return;
        }
        if (!validateCatalogForSave()) {
            return;
        }
        if (LotteryClientNetwork.clientSaveTitleCatalog(GSON.toJson(workingTitleCatalog), loadedCatalogRevision)) {
            host.setStatus(tr("titles.status.save_submitted").getString());
        } else {
            host.setStatus(tr("titles.status.save_failed").getString());
        }
    }

    private void sendTitlePlayerOp(String mode, String payload) {
        if (!LotteryClientNetwork.canSendPlay()) {
            host.setStatus(tr("status.not_connected").getString());
            return;
        }
        if (!ClientLotteryState.op) {
            host.setStatus(tr("status.need_op").getString());
            return;
        }
        String uuid = selectedTitlePlayerUuid;
        if (uuid == null || uuid.isBlank()) {
            List<PlayerItem> rows = titlePlayerRows();
            if (selectedTitlePlayerIndex >= 0 && selectedTitlePlayerIndex < rows.size()) {
                uuid = rows.get(selectedTitlePlayerIndex).uuid();
            }
        }
        if (uuid == null || uuid.isBlank()) {
            host.setStatus(tr("titles.choose_player").getString());
            return;
        }
        var expectedRevision = LotteryClientNetwork.cachedTitlePlayerRevision(uuid);
        if (expectedRevision.isEmpty()) {
            requestPlayerPageSilent(currentPage());
            host.setStatus(tr("titles.status.sync_required").getString());
            return;
        }
        if (LotteryClientNetwork.clientTitleModify(
                mode, uuid, payload == null ? "" : payload, expectedRevision.getAsLong())) {
            host.setStatus(tr("titles.status.op_submitted").getString());
        } else {
            host.setStatus(tr("titles.status.op_failed").getString());
        }
    }

    void build(int y, int h) {
        this.cachedY = y;
        this.cachedH = h;
        dynamicPlayerWidgets.clear();
        ensureWorkingTitleCatalog();

        if (host.consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW) {
            buildNarrow(y);
            built = true;
            return;
        }

        Font font = host.font();
        int pageX = host.pageLeft();
        int pageR = host.pageRight();

        Button refreshTitlesBtn = host.addNavigationTabWidget(Button.builder(tr("titles.refresh_titles"), b -> {
            refreshTitlesInteractive();
        }).bounds(pageX, y, 70, 20).build());
        refreshTitlesBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;

        Button refreshPlayersBtn = host.addNavigationTabWidget(Button.builder(tr("titles.refresh_players"), b ->
                requestPlayerPageInteractive(currentPage())).bounds(pageX + 76, y, 70, 20).build());
        refreshPlayersBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;

        int leftW = Math.min(210, Math.max(150, host.pageWidth() / 3));
        int midGap = 10;
        int listTop = y + 38;
        int listH = Math.max(ROW_H * 3, h - 112);

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
            catalogLabels.add(mark + tr("titles.row_template",
                    LotteryConfigRootScreen.shortName(id, 8),
                    LotteryConfigRootScreen.shortName(disp, 10)).getString());
        }
        titleCatalogList.setBounds(pageX, listTop, leftW, listH);
        titleCatalogList.setItems(catalogLabels, selectedTitleCatalogIndex);
        titleCatalogList.setOnSelect(fi -> {
            flushFieldsToModel();
            selectedTitleCatalogIndex = fi;
            host.rebuildActiveTab();
        });
        titleCatalogList.rebuildWidgets(host::addNavigationTabWidget, null);

        int editY = titleCatalogList.viewportBottom() + 4;
        if (titleIdBox == null) {
            titleIdBox = new EditBox(font, pageX, editY, leftW / 2 - 2, 18, tr("titles.template_id"));
            titleIdBox.setMaxLength(TitleCatalog.MAX_ID_LENGTH);
            titleIdBox.setHint(tr("titles.template_id"));
            titleIdBox.setResponder(v -> flushFieldsToModel());
        } else {
            placeBox(titleIdBox, pageX, editY, leftW / 2 - 2, 18);
        }
        if (titleDisplayBox == null) {
            titleDisplayBox = new EditBox(font, pageX + leftW / 2 + 2, editY, leftW / 2 - 2, 18,
                    tr("titles.template_display"));
            titleDisplayBox.setMaxLength(TitleCatalog.MAX_DISPLAY_LENGTH);
            titleDisplayBox.setHint(tr("titles.template_display_hint"));
            titleDisplayBox.setResponder(v -> flushFieldsToModel());
        } else {
            placeBox(titleDisplayBox, pageX + leftW / 2 + 2, editY, leftW / 2 - 2, 18);
        }
        bindCatalogFields(titles.isEmpty() ? null : titles.get(selectedTitleCatalogIndex));
        host.addTabWidget(titleIdBox);
        host.addTabWidget(titleDisplayBox);

        int btnY = editY + 22;
        int templateButtonW = Math.min(48, Math.max(40, (leftW - 8) / 3));
        int grantTemplateX = pageX + (templateButtonW + 4) * 2;
        int grantTemplateW = Math.max(44, leftW - (templateButtonW + 4) * 2);
        host.addTabWidget(Button.builder(tr("titles.add_short"), b -> {
            flushFieldsToModel();
            ensureWorkingTitleCatalog();
            if (workingTitleCatalog.titles.size() >= TitleCatalog.MAX_TITLES) {
                host.setStatus(tr("titles.status.too_many", TitleCatalog.MAX_TITLES).getString());
                return;
            }
            workingTitleCatalog.titles.add(new TitleCatalog.TitleEntry(
                    nextTitleTemplateId(), tr("titles.new_template_default").getString(), true));
            titleCatalogDirty = true;
            selectedTitleCatalogIndex = workingTitleCatalog.titles.size() - 1;
            titleCatalogList.ensureSelectedVisible();
            host.rebuildActiveTab();
            host.setStatus(tr("titles.status.added_template").getString());
        }).bounds(pageX, btnY, templateButtonW, 18).build());

        host.addTabWidget(Button.builder(tr("titles.remove_short"), b -> {
            ensureWorkingTitleCatalog();
            if (workingTitleCatalog.titles.isEmpty()) {
                host.setStatus(tr("titles.status.no_template").getString());
                return;
            }
            flushFieldsToModel();
            selectedTitleCatalogIndex = Mth.clamp(selectedTitleCatalogIndex, 0, workingTitleCatalog.titles.size() - 1);
            workingTitleCatalog.titles.remove(selectedTitleCatalogIndex);
            titleCatalogDirty = true;
            selectedTitleCatalogIndex = Math.max(0, selectedTitleCatalogIndex - 1);
            host.rebuildActiveTab();
            host.setStatus(tr("titles.status.removed_template").getString());
        }).bounds(pageX + templateButtonW + 4, btnY, templateButtonW, 18).build());

        host.addTabWidget(Button.builder(tr("titles.grant_custom"), b -> {
            flushFieldsToModel();
            ensureWorkingTitleCatalog();
            if (workingTitleCatalog.titles.isEmpty()) {
                host.setStatus(tr("titles.status.no_template").getString());
                return;
            }
            selectedTitleCatalogIndex = Mth.clamp(selectedTitleCatalogIndex, 0, workingTitleCatalog.titles.size() - 1);
            TitleCatalog.TitleEntry e = workingTitleCatalog.titles.get(selectedTitleCatalogIndex);
            String display = e == null || e.display == null ? "" : e.display;
            if (display.isBlank()) {
                host.setStatus(tr("titles.status.empty_display").getString());
                return;
            }
            sendTitlePlayerOp("grant", display);
        }).bounds(grantTemplateX, btnY, grantTemplateW, 18)
                .tooltip(Tooltip.create(tr("titles.grant_template_hint")))
                .build());

        // Right column: search box + pagination buttons (persistent across search responder)
        int rightX = pageX + leftW + midGap;
        int rightW = Math.max(120, pageR - rightX);
        int playerColW = Math.min(150, Math.max(64, rightW / 2 - 4));
        int searchW = Math.max(40, playerColW - 40);

        if (titlePlayerSearchBox == null) {
            titlePlayerSearchBox = new EditBox(font, rightX, y + 42, searchW, 18, tr("titles.search_players"));
            titlePlayerSearchBox.setMaxLength(64);
            titlePlayerSearchBox.setHint(tr("titles.search_players"));
            titlePlayerSearchBox.setValue(titlePlayerSearchQuery == null ? "" : titlePlayerSearchQuery);
            titlePlayerSearchBox.setResponder(this::onPlayerSearchChanged);
        } else {
            placeBox(titlePlayerSearchBox, rightX, y + 42, searchW, 18);
            syncUnfocusedValue(titlePlayerSearchBox, titlePlayerSearchQuery == null ? "" : titlePlayerSearchQuery);
        }
        host.addNavigationTabWidget(titlePlayerSearchBox);

        prevPageButton = host.addNavigationTabWidget(Button.builder(Component.literal("‹"),
                b -> requestPlayerPageInteractive(Math.max(0, currentPage() - 1)))
                .bounds(rightX + searchW + 2, y + 42, 18, 18).build());
        nextPageButton = host.addNavigationTabWidget(Button.builder(Component.literal("›"),
                b -> requestPlayerPageInteractive(currentPage() + 1))
                .bounds(rightX + searchW + 22, y + 42, 18, 18).build());

        built = true;
        rebuildDynamicPlayerAndOwned();
    }

    private void onPlayerSearchChanged(String raw) {
        String next = raw == null ? "" : raw;
        if (next.equals(titlePlayerSearchQuery)) {
            return;
        }
        List<PlayerItem> before = titlePlayerRows();
        if (selectedTitlePlayerIndex >= 0 && selectedTitlePlayerIndex < before.size()) {
            selectedTitlePlayerUuid = before.get(selectedTitlePlayerIndex).uuid();
        }
        titlePlayerSearchQuery = next;
        titlePlayerList.setScroll(0);
        if (LotteryClientNetwork.canSendPlay() && ClientLotteryState.op) {
            searchDebounceTicks = SEARCH_DEBOUNCE_TICKS;
        }
        if (built) {
            rebuildDynamicPlayerAndOwned();
            host.applyOpWidgetLocks();
        }
    }

    private void clearDynamicPlayerWidgets() {
        for (AbstractWidget w : dynamicPlayerWidgets) {
            host.removeTabWidget(w);
        }
        dynamicPlayerWidgets.clear();
    }

    private <T extends AbstractWidget> T addDynamic(T widget) {
        dynamicPlayerWidgets.add(widget);
        return host.addTabWidget(widget);
    }

    private <T extends AbstractWidget> T addDynamicNav(T widget) {
        dynamicPlayerWidgets.add(widget);
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

    private void rebuildDynamicPlayerAndOwned() {
        clearDynamicPlayerWidgets();
        if (host.consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW) {
            rebuildNarrowDynamic(cachedY);
            return;
        }

        updatePaginationButtons(true);
        Font font = host.font();
        int pageX = host.pageLeft();
        int pageR = host.pageRight();
        int leftW = Math.min(210, Math.max(150, host.pageWidth() / 3));
        int midGap = 10;
        int rightX = pageX + leftW + midGap;
        int rightW = Math.max(120, pageR - rightX);
        int playerColW = Math.min(150, Math.max(64, rightW / 2 - 4));
        int ownedColX = rightX + playerColW + 8;
        int ownedColW = Math.max(60, pageR - ownedColX);
        int listTopRight = cachedY + 66;
        int rightListH = Math.max(ROW_H * 3, cachedH - 140);

        List<PlayerItem> players = titlePlayerRows();
        resolveSelectedPlayer(players);

        List<String> playerLabels = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            PlayerItem row = players.get(i);
            String mark = i == selectedTitlePlayerIndex ? "§a" : (row.online() ? "§f" : "§7");
            playerLabels.add(mark + (row.online() ? "● " : "○ ")
                    + LotteryConfigRootScreen.shortName(row.name(), 12));
        }
        titlePlayerList.setBounds(rightX, listTopRight, playerColW, rightListH);
        titlePlayerList.setItems(playerLabels, selectedTitlePlayerIndex);
        titlePlayerList.setOnSelect(fi -> {
            selectedTitlePlayerIndex = fi;
            List<PlayerItem> rows = titlePlayerRows();
            if (fi >= 0 && fi < rows.size()) {
                selectedTitlePlayerUuid = rows.get(fi).uuid();
            }
            selectedTitleOwnedIndex = 0;
            rebuildDynamicPlayerAndOwned();
            host.applyOpWidgetLocks();
        });
        titlePlayerList.rebuildWidgets(this::addDynamicNav, null);

        List<String> owned = titlePlayerOwned(selectedTitlePlayerUuid);
        String current = titlePlayerCurrent(selectedTitlePlayerUuid);
        selectedTitleOwnedIndex = owned.isEmpty() ? 0 : Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
        List<String> ownedLabels = new ArrayList<>(owned.size());
        for (int i = 0; i < owned.size(); i++) {
            String t = owned.get(i);
            boolean isCur = t != null && t.equals(current);
            String mark = i == selectedTitleOwnedIndex ? "§a" : (isCur ? "§e" : "§7");
            String prefix = isCur ? "★ " : tr("titles.row_owned_prefix").getString();
            ownedLabels.add(mark + prefix + LotteryConfigRootScreen.shortName(t, 14));
        }
        titleOwnedList.setBounds(ownedColX, listTopRight, ownedColW, Math.max(ROW_H * 2, rightListH - 46));
        titleOwnedList.setItems(ownedLabels, selectedTitleOwnedIndex);
        titleOwnedList.setOnSelect(fi -> {
            selectedTitleOwnedIndex = fi;
            rebuildDynamicPlayerAndOwned();
            host.applyOpWidgetLocks();
        });
        titleOwnedList.rebuildWidgets(this::addDynamicNav, null);

        int opY = titleOwnedList.viewportBottom() + 4;
        int customButtonW = Math.min(76, Math.max(32, ownedColW / 2));
        int customFieldW = Math.max(20, ownedColW - customButtonW - 2);
        if (titleCustomBox == null) {
            titleCustomBox = new EditBox(font, ownedColX, opY, customFieldW, 18, tr("titles.custom"));
            titleCustomBox.setMaxLength(TitleCatalog.MAX_DISPLAY_LENGTH);
            titleCustomBox.setHint(tr("titles.custom"));
            titleCustomBox.setValue(titleCustomDraft);
            titleCustomBox.setResponder(value -> titleCustomDraft = value == null ? "" : value);
        } else {
            placeBox(titleCustomBox, ownedColX, opY, customFieldW, 18);
            syncUnfocusedValue(titleCustomBox, titleCustomDraft);
        }
        addDynamic(titleCustomBox);

        addDynamic(Button.builder(tr("titles.grant_custom_wide"), b -> {
            String text = titleCustomBox == null ? "" : titleCustomBox.getValue();
            if (text == null || text.isBlank()) {
                host.setStatus(tr("titles.custom_empty").getString());
                return;
            }
            sendTitlePlayerOp("grant", text);
        }).bounds(ownedColX + customFieldW + 2, opY, customButtonW, 18).build());

        int opY2 = opY + 22;
        int ownedActionW = Math.max(16, (ownedColW - 8) / 3);
        addDynamic(Button.builder(tr("titles.revoke"), b -> {
            if (owned.isEmpty()) {
                host.setStatus(tr("titles.status.no_owned").getString());
                return;
            }
            selectedTitleOwnedIndex = Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
            sendTitlePlayerOp("revoke", owned.get(selectedTitleOwnedIndex));
        }).bounds(ownedColX, opY2, ownedActionW, 18).build());

        addDynamic(Button.builder(tr("titles.equip"), b -> {
            if (owned.isEmpty()) {
                host.setStatus(tr("titles.status.no_owned").getString());
                return;
            }
            selectedTitleOwnedIndex = Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
            sendTitlePlayerOp("set_current", owned.get(selectedTitleOwnedIndex));
        }).bounds(ownedColX + ownedActionW + 4, opY2, ownedActionW, 18).build());

        addDynamic(Button.builder(tr("titles.clear"), b -> sendTitlePlayerOp("set_current", ""))
                .bounds(ownedColX + (ownedActionW + 4) * 2, opY2, ownedActionW, 18)
                .tooltip(Tooltip.create(tr("titles.clear_hint"))).build());
    }

    private void resolveSelectedPlayer(List<PlayerItem> players) {
        String keepUuid = selectedTitlePlayerUuid;
        if ((keepUuid == null || keepUuid.isBlank())
                && selectedTitlePlayerIndex >= 0
                && selectedTitlePlayerIndex < players.size()) {
            keepUuid = players.get(selectedTitlePlayerIndex).uuid();
        }
        if (!players.isEmpty()) {
            int restored = -1;
            if (keepUuid != null && !keepUuid.isBlank()) {
                for (int i = 0; i < players.size(); i++) {
                    if (keepUuid.equals(players.get(i).uuid())) {
                        restored = i;
                        break;
                    }
                }
            }
            selectedTitlePlayerIndex = restored >= 0 ? restored : 0;
            selectedTitlePlayerUuid = players.get(selectedTitlePlayerIndex).uuid();
        } else {
            selectedTitlePlayerIndex = 0;
            selectedTitlePlayerUuid = "";
        }
    }

    private void buildNarrow(int y) {
        Font font = host.font();
        int x = host.pageLeft();
        int width = host.pageRight() - x;
        int gap = 4;
        int modeWidth = (width - gap * 2) / 3;
        String[] modes = {"templates", "players", "owned"};
        for (int i = 0; i < modes.length; i++) {
            final int next = i;
            int buttonX = x + i * (modeWidth + gap);
            int buttonWidth = i == 2 ? host.pageRight() - buttonX : modeWidth;
            host.addNavigationTabWidget(Button.builder(Component.literal((titleNarrowPage == i ? "● " : "○ ")
                    + tr("titles." + modes[i]).getString()), b -> {
                flushFieldsToModel();
                titleNarrowPage = next;
                host.rebuildActiveTab();
            }).bounds(buttonX, y, buttonWidth, 20).build());
        }

        int toolsY = y + 24;
        if (titleNarrowPage == 0) {
            Button refreshBtn = host.addNavigationTabWidget(Button.builder(tr("titles.refresh_titles"),
                    b -> refreshTitlesInteractive()).bounds(x, toolsY, width, 20).build());
            refreshBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;

            ensureWorkingTitleCatalog();
            List<TitleCatalog.TitleEntry> titles = workingTitleCatalog.titles;
            selectedTitleCatalogIndex = titles.isEmpty() ? 0
                    : Mth.clamp(selectedTitleCatalogIndex, 0, titles.size() - 1);
            List<String> labels = new ArrayList<>(titles.size());
            for (int i = 0; i < titles.size(); i++) {
                TitleCatalog.TitleEntry title = titles.get(i);
                String id = title == null || title.id == null ? "?" : title.id;
                String display = title == null || title.display == null ? "" : title.display;
                labels.add((i == selectedTitleCatalogIndex ? "§a● " : "§7○ ")
                        + LotteryConfigRootScreen.shortName(id, 18) + "  "
                        + LotteryConfigRootScreen.shortName(display, 20));
            }
            int listY = y + 48;
            int listH = Math.max(20, host.pageBottom() - listY - 44);
            titleCatalogList.setBounds(x, listY, width, listH);
            titleCatalogList.setItems(labels, selectedTitleCatalogIndex);
            titleCatalogList.setOnSelect(index -> {
                flushFieldsToModel();
                selectedTitleCatalogIndex = index;
                host.rebuildActiveTab();
            });
            titleCatalogList.rebuildWidgets(host::addNavigationTabWidget, null);

            int editY = titleCatalogList.viewportBottom() + 3;
            int idW = Math.max(80, width / 3);
            if (titleIdBox == null) {
                titleIdBox = new EditBox(font, x, editY, idW, 18, tr("titles.template_id"));
                titleIdBox.setMaxLength(TitleCatalog.MAX_ID_LENGTH);
                titleIdBox.setHint(tr("titles.template_id"));
                titleIdBox.setResponder(v -> flushFieldsToModel());
            } else {
                placeBox(titleIdBox, x, editY, idW, 18);
            }
            if (titleDisplayBox == null) {
                titleDisplayBox = new EditBox(font, x + idW + gap, editY,
                        width - idW - gap, 18, tr("titles.template_display"));
                titleDisplayBox.setMaxLength(TitleCatalog.MAX_DISPLAY_LENGTH);
                titleDisplayBox.setHint(tr("titles.template_display"));
                titleDisplayBox.setResponder(v -> flushFieldsToModel());
            } else {
                placeBox(titleDisplayBox, x + idW + gap, editY, width - idW - gap, 18);
            }
            bindCatalogFields(titles.isEmpty() ? null : titles.get(selectedTitleCatalogIndex));
            host.addTabWidget(titleIdBox);
            host.addTabWidget(titleDisplayBox);

            int actionY = editY + 22;
            int actionW = (width - gap * 2) / 3;
            host.addTabWidget(Button.builder(tr("titles.add"), b -> {
                flushFieldsToModel();
                if (workingTitleCatalog.titles.size() >= TitleCatalog.MAX_TITLES) {
                    host.setStatus(tr("titles.status.too_many", TitleCatalog.MAX_TITLES).getString());
                    return;
                }
                workingTitleCatalog.titles.add(new TitleCatalog.TitleEntry(
                        nextTitleTemplateId(), tr("titles.new_template_default").getString(), true));
                titleCatalogDirty = true;
                selectedTitleCatalogIndex = workingTitleCatalog.titles.size() - 1;
                host.rebuildActiveTab();
                host.setStatus(tr("titles.status.added_template").getString());
            }).bounds(x, actionY, actionW, 18).build());

            host.addTabWidget(Button.builder(tr("titles.remove"), b -> {
                if (workingTitleCatalog.titles.isEmpty()) return;
                flushFieldsToModel();
                workingTitleCatalog.titles.remove(selectedTitleCatalogIndex);
                titleCatalogDirty = true;
                selectedTitleCatalogIndex = Math.max(0, selectedTitleCatalogIndex - 1);
                host.rebuildActiveTab();
                host.setStatus(tr("titles.status.removed_template").getString());
            }).bounds(x + actionW + gap, actionY, actionW, 18).build());

            host.addTabWidget(Button.builder(tr("titles.grant_template"), b -> {
                flushFieldsToModel();
                if (workingTitleCatalog.titles.isEmpty()) return;
                TitleCatalog.TitleEntry selected = workingTitleCatalog.titles.get(selectedTitleCatalogIndex);
                sendTitlePlayerOp("grant", selected == null ? "" : selected.display);
            }).bounds(x + (actionW + gap) * 2, actionY,
                    host.pageRight() - x - (actionW + gap) * 2, 18).build());
            return;
        }

        if (titleNarrowPage == 1) {
            int refreshW = 68;
            int pageBtnW = 18;
            Button refreshPlayersBtn = host.addNavigationTabWidget(Button.builder(tr("titles.refresh_players"),
                    b -> requestPlayerPageInteractive(currentPage()))
                    .bounds(x, toolsY, refreshW, 20).build());
            refreshPlayersBtn.active = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;

            int searchX = x + refreshW + gap;
            int searchW = Math.max(48, width - refreshW - gap - (pageBtnW * 2 + 6));
            if (titlePlayerSearchBox == null) {
                titlePlayerSearchBox = new EditBox(font, searchX, toolsY, searchW, 20, tr("titles.search_players"));
                titlePlayerSearchBox.setMaxLength(64);
                titlePlayerSearchBox.setHint(tr("titles.search_players"));
                titlePlayerSearchBox.setValue(titlePlayerSearchQuery);
                titlePlayerSearchBox.setResponder(this::onPlayerSearchChanged);
            } else {
                placeBox(titlePlayerSearchBox, searchX, toolsY, searchW, 20);
                syncUnfocusedValue(titlePlayerSearchBox, titlePlayerSearchQuery);
            }
            host.addNavigationTabWidget(titlePlayerSearchBox);

            prevPageButton = host.addNavigationTabWidget(Button.builder(Component.literal("‹"),
                    b -> requestPlayerPageInteractive(Math.max(0, currentPage() - 1)))
                    .bounds(searchX + searchW + 2, toolsY, pageBtnW, 20).build());
            nextPageButton = host.addNavigationTabWidget(Button.builder(Component.literal("›"),
                    b -> requestPlayerPageInteractive(currentPage() + 1))
                    .bounds(searchX + searchW + 4 + pageBtnW, toolsY, pageBtnW, 20).build());
        }

        rebuildNarrowDynamic(y);
    }

    private void rebuildNarrowDynamic(int y) {
        if (titleNarrowPage == 0) {
            return;
        }
        int x = host.pageLeft();
        int width = host.pageRight() - x;
        int gap = 4;

        if (titleNarrowPage == 1) {
            updatePaginationButtons(true);
            List<PlayerItem> players = titlePlayerRows();
            resolveSelectedPlayer(players);
            List<String> labels = new ArrayList<>(players.size());
            for (int i = 0; i < players.size(); i++) {
                PlayerItem player = players.get(i);
                String mark = i == selectedTitlePlayerIndex ? "§a" : (player.online() ? "§f" : "§7");
                labels.add(mark + (player.online() ? "● " : "○ ")
                        + LotteryConfigRootScreen.shortName(player.name(), 28));
            }
            int listY = y + 48;
            titlePlayerList.setBounds(x, listY, width, Math.max(20, host.pageBottom() - listY));
            titlePlayerList.setItems(labels, selectedTitlePlayerIndex);
            titlePlayerList.setOnSelect(index -> {
                selectedTitlePlayerIndex = index;
                List<PlayerItem> rows = titlePlayerRows();
                if (index >= 0 && index < rows.size()) {
                    selectedTitlePlayerUuid = rows.get(index).uuid();
                }
                selectedTitleOwnedIndex = 0;
                titleNarrowPage = 2;
                host.rebuildActiveTab();
            });
            titlePlayerList.rebuildWidgets(this::addDynamicNav, null);
            return;
        }

        Font font = host.font();
        List<String> owned = titlePlayerOwned(selectedTitlePlayerUuid);
        String current = titlePlayerCurrent(selectedTitlePlayerUuid);
        selectedTitleOwnedIndex = owned.isEmpty() ? 0
                : Mth.clamp(selectedTitleOwnedIndex, 0, owned.size() - 1);
        List<String> labels = new ArrayList<>(owned.size());
        for (int i = 0; i < owned.size(); i++) {
            String title = owned.get(i);
            labels.add((i == selectedTitleOwnedIndex ? "§a● " : "§7○ ")
                    + (title.equals(current) ? "★ " : "")
                    + LotteryConfigRootScreen.shortName(title, 28));
        }
        titleOwnedList.setBounds(x, y + 38, width, 38);
        titleOwnedList.setItems(labels, selectedTitleOwnedIndex);
        titleOwnedList.setOnSelect(index -> {
            selectedTitleOwnedIndex = index;
            rebuildDynamicPlayerAndOwned();
            host.applyOpWidgetLocks();
        });
        titleOwnedList.rebuildWidgets(this::addDynamicNav, null);

        int customY = y + 78;
        int grantW = 72;
        if (titleCustomBox == null) {
            titleCustomBox = new EditBox(font, x, customY, width - grantW - gap, 18, tr("titles.custom"));
            titleCustomBox.setMaxLength(TitleCatalog.MAX_DISPLAY_LENGTH);
            titleCustomBox.setHint(tr("titles.custom"));
            titleCustomBox.setValue(titleCustomDraft);
            titleCustomBox.setResponder(value -> titleCustomDraft = value == null ? "" : value);
        } else {
            placeBox(titleCustomBox, x, customY, width - grantW - gap, 18);
            syncUnfocusedValue(titleCustomBox, titleCustomDraft);
        }
        addDynamic(titleCustomBox);

        addDynamic(Button.builder(tr("titles.grant_custom"), b -> {
            if (titleCustomDraft == null || titleCustomDraft.isBlank()) {
                host.setStatus(tr("titles.custom_empty").getString());
                return;
            }
            sendTitlePlayerOp("grant", titleCustomDraft);
        }).bounds(host.pageRight() - grantW, customY, grantW, 18).build());

        int actionY = y + 100;
        int actionW = (width - gap * 2) / 3;
        addDynamic(Button.builder(tr("titles.revoke"), b -> {
            if (!owned.isEmpty()) {
                sendTitlePlayerOp("revoke", owned.get(selectedTitleOwnedIndex));
            }
        }).bounds(x, actionY, actionW, 18).build());

        addDynamic(Button.builder(tr("titles.equip"), b -> {
            if (!owned.isEmpty()) {
                sendTitlePlayerOp("set_current", owned.get(selectedTitleOwnedIndex));
            }
        }).bounds(x + actionW + gap, actionY, actionW, 18).build());

        addDynamic(Button.builder(tr("titles.clear"), b -> sendTitlePlayerOp("set_current", ""))
                .bounds(x + (actionW + gap) * 2, actionY,
                        host.pageRight() - x - (actionW + gap) * 2, 18)
                .tooltip(Tooltip.create(tr("titles.clear_hint"))).build());
    }

    void renderBeforeWidgets(GuiGraphics g, ConfigConsoleLayout layout, int contentY, int screenHeight) {
        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            return;
        }
        Font font = host.font();
        int leftW = Math.min(210, Math.max(150, host.pageWidth() / 3));
        int rightX = host.pageLeft() + leftW + 10;
        g.drawString(font, tr("titles.header_catalog"), host.pageLeft(), contentY + 26, 0xFF8A92A0, false);
        g.drawString(font, tr("titles.header_players"), rightX, contentY + 26, 0xFF8A92A0, false);

        String pageText = tr("players.page_short", currentPage() + 1, totalPages(), totalMatched()).getString();
        int pageW = font.width(pageText);
        if (host.pageRight() - pageW > rightX + font.width(tr("titles.header_players")) + 8) {
            g.drawString(font, pageText, host.pageRight() - pageW, contentY + 26, 0xFF8A92A0, false);
        }

        String uuid = selectedTitlePlayerUuid;
        String cur = titlePlayerCurrent(uuid);
        if (uuid != null && !uuid.isBlank()) {
            List<PlayerItem> rows = titlePlayerRows();
            String name = LotteryConfigRootScreen.shortName(uuid, 10);
            for (PlayerItem r : rows) {
                if (uuid.equals(r.uuid()) && !r.name().isBlank()) {
                    name = r.name();
                    break;
                }
            }
            String equippedText = (cur == null || cur.isBlank())
                    ? tr("titles.none").getString()
                    : LotteryConfigRootScreen.shortName(cur, 16);
            g.drawString(font, tr("titles.selected_player", name), rightX, screenHeight - 58, 0xFFB0B8C0, false);
            g.drawString(font, tr("titles.equipped_summary", equippedText),
                    rightX + 100, screenHeight - 58, 0xFFD4A55A, false);
        } else {
            g.drawString(font, tr("titles.sync_hint"), rightX, screenHeight - 58, 0xFF8A92A0, false);
        }
    }

    void renderAfterWidgets(GuiGraphics g, ConfigConsoleLayout layout, int contentY) {
        Font font = host.font();
        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            if (titleNarrowPage == 2) {
                String current = titlePlayerCurrent(selectedTitlePlayerUuid);
                String summary = selectedTitlePlayerUuid.isBlank()
                        ? tr("titles.choose_player").getString()
                        : tr("titles.current").getString() + ": "
                        + (current.isBlank() ? tr("titles.none").getString() : current);
                g.drawString(font, font.plainSubstrByWidth(summary, host.pageWidth()),
                        host.pageLeft(), contentY + 24, 0xFFB7C1C6, false);
            } else if (titleNarrowPage == 0 && workingTitleCatalog.titles.isEmpty()) {
                g.drawString(font, tr("titles.empty_templates"), host.pageLeft() + 4, contentY + 51,
                        0xFF8A92A0, false);
            } else if (titleNarrowPage == 1 && titlePlayerRows().isEmpty()) {
                g.drawString(font, tr("titles.empty_players"), host.pageLeft() + 4, contentY + 51,
                        0xFF8A92A0, false);
            }
        }
        titleCatalogList.renderScrollbar(g);
        titlePlayerList.renderScrollbar(g);
        titleOwnedList.renderScrollbar(g);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        int beforeCat = titleCatalogList.getScroll();
        if (titleCatalogList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            if (titleCatalogList.getScroll() != beforeCat) {
                flushFieldsToModel();
                host.rebuildActiveTab();
            }
            return true;
        }
        int beforePl = titlePlayerList.getScroll();
        if (titlePlayerList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            if (titlePlayerList.getScroll() != beforePl) {
                if (built) {
                    rebuildDynamicPlayerAndOwned();
                    host.applyOpWidgetLocks();
                } else {
                    host.rebuildActiveTab();
                }
            }
            return true;
        }
        int beforeOwn = titleOwnedList.getScroll();
        if (titleOwnedList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            if (titleOwnedList.getScroll() != beforeOwn) {
                if (built) {
                    rebuildDynamicPlayerAndOwned();
                    host.applyOpWidgetLocks();
                } else {
                    host.rebuildActiveTab();
                }
            }
            return true;
        }
        return false;
    }

    boolean keyPressed(int keyCode) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && titlePlayerSearchBox != null
                && titlePlayerSearchBox.isFocused()
                && titlePlayerSearchBox.visible) {
            if (LotteryClientNetwork.canSendPlay() && ClientLotteryState.op) {
                requestPlayerPageInteractive(0);
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
