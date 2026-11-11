package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.network.ClientLotteryState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * OP-gated operations console shell for player assets, titles, skins, crates, mail, daily tasks,
 * and self-select card configuration ({@code N-01}, {@code B-22}, {@code I-24.1}, {@code N-02}).
 */
public class LotteryConfigRootScreen extends Screen {
    private static final int ROW_H = 22;
    static final int TAB_PLAYERS = 0;
    static final int TAB_TITLES = 1;
    static final int TAB_SKINS = 2;
    static final int TAB_CRATES = 3;
    static final int TAB_MAIL = 4;
    static final int TAB_DAILY = 5;
    static final int TAB_SELF_SELECT = 6;

    private final Screen parent;
    private final ConfigConsoleNavBar navBar = new ConfigConsoleNavBar();
    private final PlayerAssetsAdminTab playerAssetsTab = new PlayerAssetsAdminTab(this);
    private final TitleAdminTab titleAdminTab = new TitleAdminTab(this);

    private int selectedTab;
    private String status = "";
    private Button refreshButton;
    private Button saveButton;
    private Button backButton;
    private boolean renderedLocked;
    private boolean wasOp;

    private int lastSeenPlayerListVersion = -1;
    private int lastSeenTitleVersion = -1;
    private int lastSeenCrateConfigVersion = -1;
    private int lastSeenCrateCatalogVersion = -1;

    private final List<AbstractWidget> tabWidgets = new ArrayList<>();
    private final Set<AbstractWidget> navigationTabWidgets =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Skip nested {@link #renderBackground} pass inside {@code super.render}. */
    private boolean suppressNestedBackground;

    public LotteryConfigRootScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_lottery.config.title"));
        this.parent = parent;
    }

    private static Component tr(String suffix, Object... args) {
        return Component.translatable("screen.habitrain_lottery.config." + suffix, args);
    }

    @Override
    protected void init() {
        super.init();
        rebuildScreen(true);
    }

    private void rebuildScreen(boolean initialOpen) {
        clearWidgets();
        tabWidgets.clear();
        navigationTabWidgets.clear();
        playerAssetsTab.detach();
        titleAdminTab.detach();
        refreshButton = null;
        saveButton = null;
        backButton = null;

        renderedLocked = MenuAccessBridge.isLocked();
        if (renderedLocked) {
            backButton = addRenderableWidget(AdminLockGuard.createLockedBackButton(
                    width, height, tr("action.back"), b -> onClose()));
            return;
        }

        navBar.layout(consoleLayout(), selectedTab);
        rebuildTabContent();
        buildFooter();

        if (!initialOpen) {
            return;
        }
        if (LotteryClientNetwork.canSendPlay()) {
            wasOp = ClientLotteryState.op;
            if (wasOp) {
                status = tr("status.connected_op").getString();
                playerAssetsTab.requestCurrentPage();
                LotteryClientNetwork.clientRequestTitleSnapshot();
            } else {
                status = tr("status.connected_readonly").getString();
            }
        } else {
            wasOp = false;
            status = tr("status.offline_browse").getString();
            ClientLotteryState.op = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (renderedLocked != MenuAccessBridge.isLocked()) {
            rebuildScreen(false);
        }
        if (MenuAccessBridge.isLocked()) {
            return;
        }

        boolean currentOp = LotteryClientNetwork.canSendPlay() && ClientLotteryState.op;
        if (currentOp != wasOp) {
            wasOp = currentOp;
            if (currentOp) {
                status = tr("status.connected_op").getString();
                playerAssetsTab.requestCurrentPage();
                LotteryClientNetwork.clientRequestTitleSnapshot();
            } else if (LotteryClientNetwork.canSendPlay()) {
                status = tr("status.connected_readonly").getString();
            }
            rebuildTabContent();
        }

        playerAssetsTab.tick();
        titleAdminTab.tick();

        if (ClientLotteryState.playerListVersion != lastSeenPlayerListVersion) {
            lastSeenPlayerListVersion = ClientLotteryState.playerListVersion;
            if (selectedTab == TAB_PLAYERS) {
                playerAssetsTab.onPlayerListUpdated();
            } else if (selectedTab == TAB_TITLES) {
                titleAdminTab.onPlayerListUpdated(true);
            }
        }
        if (ClientLotteryState.titleVersion != lastSeenTitleVersion) {
            lastSeenTitleVersion = ClientLotteryState.titleVersion;
            titleAdminTab.onTitleStateUpdated(selectedTab == TAB_TITLES);
        }
        if (selectedTab == TAB_CRATES && (CrateClientNetwork.STATE.configVersion != lastSeenCrateConfigVersion
                || CrateClientNetwork.STATE.catalogVersion != lastSeenCrateCatalogVersion)) {
            lastSeenCrateConfigVersion = CrateClientNetwork.STATE.configVersion;
            lastSeenCrateCatalogVersion = CrateClientNetwork.STATE.catalogVersion;
            rebuildTabContent();
        }
        if (ClientLotteryState.lastAdminMessage != null
                && !ClientLotteryState.lastAdminMessage.isBlank()) {
            status = ClientLotteryState.lastAdminMessage;
            ClientLotteryState.lastAdminMessage = "";
            if (selectedTab == TAB_PLAYERS) {
                playerAssetsTab.onAdminMessageArrived();
            }
        }
    }

    Font font() {
        return this.font;
    }

    boolean editable() {
        return LotteryClientNetwork.canSendPlay()
                && ClientLotteryState.op
                && !MenuAccessBridge.isLocked();
    }

    ConfigConsoleLayout consoleLayout() {
        return ConfigConsoleLayout.calculate(width, height);
    }

    int pageLeft() {
        return consoleLayout().content().x();
    }

    int pageRight() {
        return consoleLayout().content().right();
    }

    int pageWidth() {
        return Math.max(120, pageRight() - pageLeft());
    }

    int pageTop() {
        ConfigConsoleLayout.Rect content = consoleLayout().content();
        if (selectedTab == TAB_TITLES && consoleLayout().mode() == ConfigConsoleLayout.Mode.NARROW) {
            return Math.min(content.bottom(), content.y() + 4);
        }
        return Math.min(content.bottom(), content.y() + 30);
    }

    int pageBottom() {
        return consoleLayout().content().bottom();
    }

    void setStatus(String message) {
        this.status = message == null ? "" : message;
    }

    void confirmDiscardDirtyTitles(Runnable onConfirmDiscard) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (!yes) {
                minecraft.setScreen(this);
                status = tr("titles.status.dirty_keep").getString();
                return;
            }
            onConfirmDiscard.run();
        }, DailyTaskUiText.tr("discard_title"), DailyTaskUiText.tr("discard_body")));
    }

    void returnFromModalAndRefreshStatus(String nextStatus) {
        if (minecraft != null) {
            minecraft.setScreen(this);
        }
        this.status = nextStatus == null ? "" : nextStatus;
    }

    private boolean hideSectionHeader(ConfigConsoleLayout layout) {
        return (selectedTab == TAB_PLAYERS && playerAssetsTab.isNarrowDetail())
                || (selectedTab == TAB_TITLES && layout.mode() == ConfigConsoleLayout.Mode.NARROW);
    }

    private void buildFooter() {
        ConfigConsoleLayout layout = consoleLayout();
        int by = layout.footer().y() + Math.max(2, (layout.footer().height() - 20) / 2);
        int x = pageLeft();
        refreshButton = addRenderableWidget(Button.builder(tr("action.refresh"), b -> {
            if (MenuAccessBridge.isLocked()) {
                return;
            }
            if (selectedTab == TAB_CRATES) {
                if (!CrateClientNetwork.connected()) {
                    status = tr("status.not_connected").getString();
                } else if (!ClientLotteryState.op) {
                    status = tr("status.need_op").getString();
                } else {
                    CrateClientNetwork.requestConfig();
                    status = tr("footer.requested_crates").getString();
                }
                return;
            }
            if (!LotteryClientNetwork.canSendPlay()) {
                status = tr("status.not_connected").getString();
                return;
            }
            if (!ClientLotteryState.op) {
                status = tr("status.need_op").getString();
                return;
            }
            boolean players = playerAssetsTab.requestCurrentPage();
            if (titleAdminTab.isCatalogDirty()) {
                if (selectedTab == TAB_TITLES) {
                    titleAdminTab.refreshTitlesInteractive();
                } else {
                    status = players
                            ? tr("footer.requested_players_dirty_titles").getString()
                            : tr("players.status.request_failed").getString();
                }
            } else {
                boolean titles = LotteryClientNetwork.clientRequestTitleSnapshot();
                status = players && titles
                        ? tr("footer.requested_players_and_titles").getString()
                        : tr("footer.refresh_failed").getString();
            }
        }).bounds(x, by, 42, 20).build());

        saveButton = addRenderableWidget(Button.builder(tr("action.save"), b -> {
            if (MenuAccessBridge.isLocked()) {
                return;
            }
            titleAdminTab.saveCatalogToServer();
        }).bounds(x + 45, by, 90, 20)
                .tooltip(Tooltip.create(tr("footer.save_titles_hint")))
                .build());

        backButton = addRenderableWidget(Button.builder(tr("action.back"), b -> onClose())
                .bounds(pageRight() - 48, by, 48, 20).build());
        updateFooterState();
    }

    private void updateFooterState() {
        boolean visible = selectedTab == TAB_TITLES;
        if (refreshButton != null) {
            refreshButton.visible = selectedTab == TAB_PLAYERS
                    || selectedTab == TAB_TITLES
                    || selectedTab == TAB_CRATES;
            refreshButton.active = editable()
                    && (selectedTab != TAB_CRATES || CrateClientNetwork.connected());
            refreshButton.setTooltip(Tooltip.create(tr(selectedTab == TAB_CRATES
                    ? "footer.refresh_crates_hint" : "footer.refresh_admin_hint")));
        }
        if (saveButton != null) {
            saveButton.visible = visible;
            saveButton.active = visible && editable();
        }
    }

    private void clearTabWidgets() {
        for (AbstractWidget w : tabWidgets) {
            removeWidget(w);
        }
        tabWidgets.clear();
        navigationTabWidgets.clear();
        playerAssetsTab.detach();
        titleAdminTab.detach();
    }

    <T extends AbstractWidget> T addTabWidget(T w) {
        tabWidgets.add(w);
        addRenderableWidget(w);
        return w;
    }

    <T extends AbstractWidget> T addNavigationTabWidget(T w) {
        tabWidgets.add(w);
        navigationTabWidgets.add(w);
        addRenderableWidget(w);
        return w;
    }

    void removeTabWidget(AbstractWidget w) {
        if (w == null) {
            return;
        }
        removeWidget(w);
        tabWidgets.remove(w);
        navigationTabWidgets.remove(w);
    }

    void rebuildActiveTab() {
        if (!MenuAccessBridge.isLocked()) {
            rebuildTabContent();
        }
    }

    private void rebuildTabContent() {
        clearTabWidgets();
        int contentY = pageTop();
        int contentH = Math.max(ROW_H, pageBottom() - contentY);
        switch (selectedTab) {
            case TAB_PLAYERS -> playerAssetsTab.build(contentY);
            case TAB_TITLES -> titleAdminTab.build(contentY, contentH);
            case TAB_SKINS -> ConfigOverviewTabs.buildSkinsTab(this, minecraft, contentY);
            case TAB_CRATES -> ConfigOverviewTabs.buildCratesTab(this, minecraft, contentY);
            case TAB_MAIL -> ConfigOverviewTabs.buildMailTab(this, minecraft, contentY);
            case TAB_DAILY -> ConfigOverviewTabs.buildDailyTab(this, minecraft, contentY);
            case TAB_SELF_SELECT -> ConfigOverviewTabs.buildSelfSelectTab(this, minecraft, contentY);
            default -> {
            }
        }
        applyOpWidgetLocks();
        if (selectedTab == TAB_PLAYERS) {
            playerAssetsTab.refreshCardLocks();
        }
        updateFooterState();
    }

    void applyOpWidgetLocks() {
        if (selectedTab == TAB_SKINS || selectedTab == TAB_CRATES || selectedTab == TAB_DAILY
                || selectedTab == TAB_SELF_SELECT || editable()) {
            return;
        }
        for (AbstractWidget w : tabWidgets) {
            if (!navigationTabWidgets.contains(w)) {
                w.active = false;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (!suppressNestedBackground) {
            super.renderBackground(g, mx, my, delta);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        if (MenuAccessBridge.isLocked()) {
            renderBackground(g, mx, my, delta);
            AdminLockGuard.renderLockedScreen(g, font, width, height, backButton, mx, my, delta);
            return;
        }
        renderBackground(g, mx, my, delta);
        ConfigConsoleLayout layout = consoleLayout();
        ConfigConsoleNavBar.drawBackdrop(g, layout, width, height);
        boolean hideHeader = hideSectionHeader(layout);
        navBar.drawTabsAndHeader(g, font, layout, width, selectedTab, hideHeader, mx, my);

        int contentY = pageTop();
        if (selectedTab == TAB_PLAYERS) {
            playerAssetsTab.renderBeforeWidgets(g, layout, contentY);
        } else if (selectedTab == TAB_TITLES) {
            titleAdminTab.renderBeforeWidgets(g, layout, contentY, height);
        } else {
            ConfigOverviewTabs.renderOverview(g, font, this, layout, selectedTab, contentY);
        }

        suppressNestedBackground = true;
        try {
            super.render(g, mx, my, delta);
        } finally {
            suppressNestedBackground = false;
        }
        navBar.drawTabsAndHeader(g, font, layout, width, selectedTab, hideHeader, mx, my);

        if (selectedTab == TAB_PLAYERS) {
            playerAssetsTab.renderAfterWidgets(g);
        } else if (selectedTab == TAB_TITLES) {
            titleAdminTab.renderAfterWidgets(g, layout, contentY);
        }

        boolean compactTitles = selectedTab == TAB_TITLES && layout.mode() == ConfigConsoleLayout.Mode.NARROW;
        int statusX = compactTitles ? pageLeft() + 140 : pageLeft();
        int statusY = compactTitles ? layout.footer().y() + 12
                : Math.max(layout.header().bottom(), layout.footer().y() - 11);
        int statusWidth = compactTitles ? Math.max(0, pageRight() - statusX - 52) : pageWidth();
        g.drawString(font, font.plainSubstrByWidth(status == null ? "" : status, statusWidth),
                statusX, statusY, 0xFFD4A55A, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (MenuAccessBridge.isLocked()) {
            return AdminLockGuard.handleLockedMouseClicked(backButton, mx, my, button);
        }
        int hit = navBar.hitTestClick(consoleLayout(), selectedTab, mx, my);
        if (hit >= 0) {
            switchTab(hit);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void switchTab(int tab) {
        if (tab < 0 || tab >= ConfigConsoleNavBar.SECTION_IDS.length || selectedTab == tab) {
            return;
        }
        if (selectedTab == TAB_TITLES) {
            titleAdminTab.flushFieldsToModel();
        }
        selectedTab = tab;
        navBar.layout(consoleLayout(), selectedTab);
        rebuildTabContent();
        status = tr("status.switched_tab", ConfigConsoleNavBar.sectionTitle(tab).getString()).getString();
        if (!editable()) {
            return;
        }
        if (tab == TAB_PLAYERS) {
            playerAssetsTab.requestCurrentPage();
        } else if (tab == TAB_TITLES) {
            LotteryClientNetwork.clientRequestTitleSnapshot();
        } else if (tab == TAB_CRATES && CrateClientNetwork.connected()) {
            CrateClientNetwork.requestConfig();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (MenuAccessBridge.isLocked()) {
            return false;
        }
        if (selectedTab == TAB_PLAYERS && playerAssetsTab.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        if (selectedTab == TAB_TITLES && titleAdminTab.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (MenuAccessBridge.isLocked()) {
            return AdminLockGuard.handleLockedKeyPressed(this, backButton, keyCode);
        }
        if (selectedTab == TAB_PLAYERS && playerAssetsTab.keyPressed(keyCode)) {
            return true;
        }
        if (selectedTab == TAB_TITLES && titleAdminTab.keyPressed(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (MenuAccessBridge.isLocked()) {
            return false;
        }
        return super.charTyped(codePoint, modifiers);
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

    static String shortName(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
