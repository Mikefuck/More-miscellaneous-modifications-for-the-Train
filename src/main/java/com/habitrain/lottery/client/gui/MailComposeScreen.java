package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.MailTargetParse;
import com.habitrain.lottery.network.MailComposeC2SPayload;
import com.habitrain.lottery.network.MailComposeC2SPayload.RewardEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * OP mail composer ({@code B-22}, {@code I-24.2}, {@code I-24.3}, {@code N-02}).
 */
public class MailComposeScreen extends Screen {
    private static final String KEY_PREFIX = "screen.habitrain_lottery.mail_compose.";
    private static final int PAGE_CONTENT = 0;
    private static final int PAGE_RECIPIENTS = 1;
    private static final int PAGE_REWARDS = 2;
    private static final int PAGE_SKIN = 3;
    private static final int PAGE_CRATES = 4;
    private static final int SEND_COOLDOWN_TICKS = 60;

    private static final String[] PAGE_KEYS = {
            "step.content", "step.recipients", "step.rewards", "step.skin", "step.crates"
    };
    private static final String[] FACTIONS = {"killer", "civilian", "neutral", "neutral_for_killer"};
    private static final String[] FACTION_KEYS = {
            "faction.killer", "faction.civilian", "faction.neutral", "faction.neutral_for_killer"
    };

    private static final int PANEL_BORDER = 0xFF8B6835;
    private static final int PANEL_BG = 0xF0101720;
    private static final int PANEL_HEADER = 0xFF182531;
    private static final int BRASS = 0xFFD8A441;
    private static final int TEXT = 0xFFE8EDF1;
    private static final int MUTED = 0xFF98A4AF;

    private final Screen parent;

    private EditBox senderBox;
    private EditBox titleBox;
    private MultilineTextArea bodyArea;
    private MultilineTextArea offlineArea;
    private EditBox greenApplesBox;
    private EditBox cardAmountBox;
    private EditBox selfSelectAmountBox;
    private EditBox limitBreakAmountBox;
    private EditBox crateAmountBox;
    private EditBox expiresBox;
    private EditBox skinBox;
    private String skinEntry = "";

    private Button backButton;
    private Button sendButton;

    private int page = PAGE_CONTENT;
    private int targetMode = MailComposeC2SPayload.MODE_ONLINE_LIST;
    private int factionIdx;
    private int crateIdx;
    private boolean keyAttachment;

    private int crateRowScroll;
    private int crateGridTop;
    private int crateGridBottom;
    private int crateVisibleRows = 1;
    private int crateTotalRows;
    private int cratePreviewY;

    private int sendCooldownTicks;
    private boolean renderedLocked;

    private final List<String> selectedOnline = new ArrayList<>();
    private final List<String> onlineNames = new ArrayList<>();
    private final ScrollableButtonList onlineList = new ScrollableButtonList();
    private final List<AbstractWidget> onlineRowWidgets = new ArrayList<>();
    private EditBox onlineSearchBox;
    private String onlineSearchQuery = "";
    private String status = "";

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelBottom;
    private int contentX;
    private int contentTop;
    private int contentWidth;
    private int contentBottom;
    private int footerY;

    private boolean suppressNestedBackground;

    public MailComposeScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(KEY_PREFIX + key, args);
    }

    public boolean editable() {
        return !MenuAccessBridge.isLocked() && ClientPlayNetworking.canSend(MailComposeC2SPayload.TYPE);
    }

    @Override
    protected void init() {
        super.init();
        rebuildPage();
    }

    @Override
    public void tick() {
        super.tick();
        if (renderedLocked != MenuAccessBridge.isLocked()) {
            rebuildPage();
            return;
        }
        if (sendCooldownTicks > 0) {
            sendCooldownTicks--;
            updateSendButtonState();
        }
    }

    private void rebuildPage() {
        clearWidgets();
        onlineRowWidgets.clear();
        backButton = null;
        sendButton = null;

        renderedLocked = MenuAccessBridge.isLocked();
        if (renderedLocked) {
            backButton = addRenderableWidget(AdminLockGuard.createLockedBackButton(
                    width, height, tr("action.back"), b -> onClose()));
            return;
        }

        calculateLayout();
        buildStepButtons();

        switch (page) {
            case PAGE_RECIPIENTS -> buildRecipientsPage();
            case PAGE_REWARDS -> buildRewardsPage();
            case PAGE_SKIN -> buildSkinPage();
            case PAGE_CRATES -> buildCratePage();
            default -> buildContentPage();
        }

        backButton = addRenderableWidget(Button.builder(tr("action.back"), b -> onClose())
                .bounds(contentX, footerY, 72, 20)
                .build());
        sendButton = addRenderableWidget(Button.builder(tr("action.send"), b -> send())
                .bounds(contentX + contentWidth - 104, footerY, 104, 20)
                .build());
        updateSendButtonState();
    }

    private void updateSendButtonState() {
        if (sendButton == null) {
            return;
        }
        if (sendCooldownTicks > 0) {
            int secs = Math.max(1, (sendCooldownTicks + 19) / 20);
            sendButton.active = false;
            sendButton.setMessage(tr("action.send_cooldown", secs));
        } else {
            sendButton.active = editable();
            sendButton.setMessage(tr("action.send"));
        }
    }

    private void calculateLayout() {
        panelWidth = Math.max(1, Math.min(680, width - 16));
        panelX = (width - panelWidth) / 2;
        panelY = 8;
        panelBottom = Math.max(panelY + 120, height - 8);
        contentX = panelX + 12;
        contentWidth = Math.max(1, panelWidth - 24);
        contentTop = panelY + 58;
        footerY = panelBottom - 26;
        contentBottom = Math.max(contentTop + 36, footerY - 17);
    }

    private void buildStepButtons() {
        int gap = 4;
        int navY = panelY + 30;
        int baseWidth = Math.max(1, (contentWidth - gap * (PAGE_KEYS.length - 1)) / PAGE_KEYS.length);
        int x = contentX;
        for (int i = 0; i < PAGE_KEYS.length; i++) {
            int buttonWidth = i == PAGE_KEYS.length - 1
                    ? contentX + contentWidth - x
                    : baseWidth;
            final int nextPage = i;
            Button button = Button.builder(tr(PAGE_KEYS[i]), b -> {
                page = nextPage;
                status = "";
                rebuildPage();
            }).bounds(x, navY, buttonWidth, 20).build();
            button.active = i != page;
            addRenderableWidget(button);
            x += buttonWidth + gap;
        }
    }

    private void buildContentPage() {
        int gap = 8;
        int fieldWidth = Math.max(1, (contentWidth - gap) / 2);
        int fieldY = contentTop + 12;

        if (senderBox == null) {
            senderBox = new EditBox(font, contentX, fieldY, fieldWidth, 18, tr("field.sender"));
            senderBox.setMaxLength(64);
            senderBox.setValue(tr("default.sender").getString());
        }
        place(senderBox, contentX, fieldY, fieldWidth);
        addRenderableWidget(senderBox);

        if (titleBox == null) {
            titleBox = new EditBox(font, contentX + fieldWidth + gap, fieldY, fieldWidth, 18,
                    tr("field.title"));
            titleBox.setMaxLength(128);
            titleBox.setValue(tr("default.title").getString());
        }
        place(titleBox, contentX + fieldWidth + gap, fieldY, fieldWidth);
        addRenderableWidget(titleBox);

        int bodyY = fieldY + 34;
        int bodyHeight = Math.max(36, contentBottom - bodyY);
        if (bodyArea == null) {
            bodyArea = new MultilineTextArea(font, contentX, bodyY, contentWidth, bodyHeight);
            bodyArea.setMaxLength(8000);
            bodyArea.setValue(tr("default.body").getString());
        } else {
            bodyArea.setX(contentX);
            bodyArea.setY(bodyY);
            bodyArea.setWidth(contentWidth);
            bodyArea.setHeight(bodyHeight);
        }
        addRenderableWidget(bodyArea);
    }

    private void buildSkinPage() {
        if (skinBox == null) {
            skinBox = new EditBox(font, contentX, contentTop + 24, contentWidth, 20, tr("skin.label"));
            skinBox.setMaxLength(64);
            skinBox.setHint(tr("skin.hint"));
            skinBox.setTooltip(Tooltip.create(tr("skin.tooltip")));
            skinBox.setValue(skinEntry);
            skinBox.setResponder(value -> skinEntry = value == null ? "" : value.trim());
        } else {
            skinBox.setX(contentX);
            skinBox.setY(contentTop + 24);
            skinBox.setWidth(Math.max(1, contentWidth));
            skinBox.setHeight(20);
            if (!skinBox.isFocused() && !Objects.equals(skinBox.getValue(), skinEntry)) {
                skinBox.setValue(skinEntry);
            }
        }
        addRenderableWidget(skinBox);
    }

    private void buildRecipientsPage() {
        int gap = 4;
        int modeY = contentTop + 12;
        int modeWidth = Math.max(1, (contentWidth - gap * 2) / 3);
        addModeButton("mode.online", MailComposeC2SPayload.MODE_ONLINE_LIST,
                contentX, modeY, modeWidth);
        addModeButton("mode.all", MailComposeC2SPayload.MODE_ALL_PLAYERS,
                contentX + modeWidth + gap, modeY, modeWidth);
        addModeButton("mode.by_name", MailComposeC2SPayload.MODE_OFFLINE_NAME,
                contentX + (modeWidth + gap) * 2, modeY,
                contentX + contentWidth - (contentX + (modeWidth + gap) * 2));

        int detailTop = modeY + 30;
        if (targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
            refreshOnlineNames();
            selectedOnline.removeIf(name -> !onlineNames.contains(name));

            int smallButtonWidth = Math.min(58, Math.max(42, contentWidth / 5));
            addRenderableWidget(Button.builder(tr("action.select_all"), b -> {
                refreshOnlineNames();
                selectedOnline.clear();
                selectedOnline.addAll(visibleOnlineNames());
                status = tr("status.selected_online", selectedOnline.size()).getString();
                rebuildOnlineListRows(detailTop);
            }).bounds(contentX + contentWidth - smallButtonWidth * 2 - gap, detailTop,
                    smallButtonWidth, 20).build());
            addRenderableWidget(Button.builder(tr("action.clear_selection"), b -> {
                selectedOnline.clear();
                status = tr("status.cleared_online").getString();
                rebuildOnlineListRows(detailTop);
            }).bounds(contentX + contentWidth - smallButtonWidth, detailTop,
                    smallButtonWidth, 20).build());

            if (onlineSearchBox == null) {
                onlineSearchBox = new EditBox(font, contentX, detailTop + 24, contentWidth, 16,
                        tr("search.label"));
                onlineSearchBox.setMaxLength(64);
                onlineSearchBox.setHint(tr("search.hint"));
                onlineSearchBox.setValue(onlineSearchQuery == null ? "" : onlineSearchQuery);
                onlineSearchBox.setResponder(s -> {
                    String next = s == null ? "" : s;
                    if (next.equals(onlineSearchQuery)) {
                        return;
                    }
                    onlineSearchQuery = next;
                    onlineList.setScroll(0);
                    if (!MenuAccessBridge.isLocked()
                            && page == PAGE_RECIPIENTS
                            && targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
                        rebuildOnlineListRows(contentTop + 42);
                    }
                });
            } else {
                onlineSearchBox.setX(contentX);
                onlineSearchBox.setY(detailTop + 24);
                onlineSearchBox.setWidth(Math.max(1, contentWidth));
                onlineSearchBox.setHeight(16);
                if (!onlineSearchBox.isFocused()
                        && !Objects.equals(onlineSearchBox.getValue(), onlineSearchQuery)) {
                    onlineSearchBox.setValue(onlineSearchQuery == null ? "" : onlineSearchQuery);
                }
            }
            addRenderableWidget(onlineSearchBox);

            rebuildOnlineListRows(detailTop);
        } else if (targetMode == MailComposeC2SPayload.MODE_OFFLINE_NAME) {
            int areaY = detailTop + 14;
            int areaHeight = Math.max(34, contentBottom - areaY);
            if (offlineArea == null) {
                offlineArea = new MultilineTextArea(font, contentX, areaY, contentWidth, areaHeight);
                offlineArea.setMaxLength(8000);
            } else {
                offlineArea.setX(contentX);
                offlineArea.setY(areaY);
                offlineArea.setWidth(contentWidth);
                offlineArea.setHeight(areaHeight);
            }
            addRenderableWidget(offlineArea);
        }
    }

    private void rebuildOnlineListRows(int detailTop) {
        for (AbstractWidget w : onlineRowWidgets) {
            removeWidget(w);
        }
        onlineRowWidgets.clear();

        refreshOnlineNames();
        selectedOnline.removeIf(name -> !onlineNames.contains(name));
        List<String> visible = visibleOnlineNames();
        List<String> labels = new ArrayList<>(visible.size());
        for (String name : visible) {
            labels.add((selectedOnline.contains(name) ? "§a[✓] " : "§7[ ] ") + name);
        }

        int listTop = detailTop + 50;
        int viewportHeight = Math.max(20, contentBottom - listTop);
        int savedScroll = onlineList.getScroll();
        onlineList.setBounds(contentX, listTop, contentWidth, viewportHeight);
        onlineList.setRowHeight(20);
        onlineList.setItems(labels, 0);
        onlineList.setScroll(savedScroll);
        onlineList.setOnSelect(index -> {
            if (index < 0 || index >= visible.size()) {
                return;
            }
            String name = visible.get(index);
            if (selectedOnline.contains(name)) {
                selectedOnline.remove(name);
            } else {
                selectedOnline.add(name);
            }
            rebuildOnlineListRows(detailTop);
        });
        onlineList.rebuildWidgets(w -> {
            onlineRowWidgets.add(w);
            addRenderableWidget(w);
        }, null);
    }

    private void addModeButton(String labelKey, int mode, int x, int y, int buttonWidth) {
        Button button = Button.builder(tr(labelKey), b -> {
            targetMode = mode;
            if (mode == MailComposeC2SPayload.MODE_ALL_PLAYERS && expiresBox != null) {
                expiresBox.setValue(String.valueOf(MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS));
                status = tr("status.all_players_mode", MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS).getString();
            } else {
                status = "";
            }
            rebuildPage();
        }).bounds(x, y, Math.max(1, buttonWidth), 20).build();
        button.active = targetMode != mode;
        addRenderableWidget(button);
    }

    private void buildRewardsPage() {
        int gap = 6;
        int fieldWidth = Math.max(1, (contentWidth - gap) / 2);
        int fieldY = contentTop + 12;

        greenApplesBox = prepareNumberBox(greenApplesBox, contentX, fieldY, fieldWidth, true,
                tr("field.green_apples"));
        boolean freshExpiresBox = expiresBox == null;
        expiresBox = prepareNumberBox(expiresBox, contentX + fieldWidth + gap, fieldY,
                contentX + contentWidth - (contentX + fieldWidth + gap), false, tr("field.expires"));
        if (freshExpiresBox && targetMode == MailComposeC2SPayload.MODE_ALL_PLAYERS) {
            expiresBox.setValue(String.valueOf(MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS));
        }
        addRenderableWidget(greenApplesBox);
        addRenderableWidget(expiresBox);

        int factionY = fieldY + 38;
        int factionGap = 3;
        int factionWidth = Math.max(1, (contentWidth - factionGap * 3) / FACTIONS.length);
        int x = contentX;
        for (int i = 0; i < FACTIONS.length; i++) {
            final int selectedFaction = i;
            int buttonWidth = i == FACTIONS.length - 1
                    ? contentX + contentWidth - x
                    : factionWidth;
            String label = (i == factionIdx ? "◆ " : "") + tr(FACTION_KEYS[i]).getString();
            Button button = Button.builder(Component.literal(label), b -> {
                factionIdx = selectedFaction;
                rebuildPage();
            }).bounds(x, factionY, buttonWidth, 20).build();
            button.active = i != factionIdx;
            addRenderableWidget(button);
            x += buttonWidth + factionGap;
        }

        int cardAmountY = factionY + 38;
        int amountWidth = Math.max(1, (contentWidth - gap * 2) / 3);
        cardAmountBox = prepareNumberBox(cardAmountBox, contentX, cardAmountY, amountWidth,
                true, tr("field.card_amount"));
        addRenderableWidget(cardAmountBox);
        selfSelectAmountBox = prepareNumberBox(selfSelectAmountBox, contentX + amountWidth + gap, cardAmountY,
                amountWidth, true, tr("field.self_select_amount"));
        addRenderableWidget(selfSelectAmountBox);
        Component limitBreakLabel = Component.translatable("screen.habitrain_lottery.config.cards.limit_break");
        limitBreakAmountBox = prepareNumberBox(limitBreakAmountBox,
                contentX + (amountWidth + gap) * 2, cardAmountY, amountWidth, true, limitBreakLabel);
        limitBreakAmountBox.setTooltip(Tooltip.create(limitBreakLabel));
        addRenderableWidget(limitBreakAmountBox);
    }

    private void buildCratePage() {
        int gap = 5;
        List<CrateCatalog.Entry> entries = CrateCatalog.publishedEntries();
        if (!entries.isEmpty()) {
            crateIdx = Mth.clamp(crateIdx, 0, entries.size() - 1);
        } else {
            crateIdx = 0;
        }

        // Pin Crate/Key toggle + crateAmountBox + attachment preview at the bottom of content area,
        // and make the 3-column crate grid scrollable above them (I-24.2).
        cratePreviewY = Math.max(contentTop + 56, contentBottom - 10);
        int amountY = cratePreviewY - 22;
        int controlsY = amountY - 24;

        crateGridTop = contentTop + 14;
        crateGridBottom = Math.max(crateGridTop + 20, controlsY - 6);
        crateTotalRows = (entries.size() + 2) / 3;
        crateVisibleRows = Math.max(1, (crateGridBottom - crateGridTop + 5) / 25);
        int maxRowScroll = Math.max(0, crateTotalRows - crateVisibleRows);
        crateRowScroll = Mth.clamp(crateRowScroll, 0, maxRowScroll);

        int gridWidth = maxRowScroll > 0 ? Math.max(60, contentWidth - 6) : contentWidth;
        int colWidth = Math.max(1, (gridWidth - gap * 2) / 3);
        int endRow = Math.min(crateTotalRows, crateRowScroll + crateVisibleRows);
        for (int row = crateRowScroll; row < endRow; row++) {
            int rowY = crateGridTop + (row - crateRowScroll) * 25;
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                if (i >= entries.size()) {
                    break;
                }
                CrateCatalog.Entry entry = entries.get(i);
                int x = contentX + col * (colWidth + gap);
                int buttonWidth = col == 2 ? contentX + gridWidth - x : colWidth;
                final int selected = i;
                Button button = Button.builder(Component.literal((i == crateIdx ? "◆ " : "") + entry.id()), b -> {
                    crateIdx = selected;
                    rebuildPage();
                }).bounds(x, rowY, Math.max(1, buttonWidth), 20).build();
                button.active = i != crateIdx;
                addRenderableWidget(button);
            }
        }

        int half = Math.max(1, (contentWidth - gap) / 2);
        String crateLabel = (keyAttachment ? "" : "◆ ") + tr("crates.kind_crate").getString();
        Button crateButton = Button.builder(Component.literal(crateLabel), b -> {
            keyAttachment = false;
            rebuildPage();
        }).bounds(contentX, controlsY, half, 20).build();
        crateButton.active = keyAttachment;
        addRenderableWidget(crateButton);

        String keyLabel = (keyAttachment ? "◆ " : "") + tr("crates.kind_key").getString();
        Button keyButton = Button.builder(Component.literal(keyLabel), b -> {
            keyAttachment = true;
            rebuildPage();
        }).bounds(contentX + half + gap, controlsY, contentX + contentWidth - (contentX + half + gap), 20).build();
        keyButton.active = !keyAttachment;
        addRenderableWidget(keyButton);

        crateAmountBox = prepareNumberBox(crateAmountBox, contentX, amountY, contentWidth, false,
                tr(keyAttachment ? "crates.key_amount" : "crates.crate_amount"));
        addRenderableWidget(crateAmountBox);
    }

    private EditBox prepareNumberBox(
            EditBox box,
            int x,
            int y,
            int boxWidth,
            boolean allowNegative,
            Component narration
    ) {
        if (box == null) {
            box = new EditBox(font, x, y, Math.max(1, boxWidth), 18, narration);
            box.setMaxLength(8);
            box.setValue("0");
            box.setFilter(value -> value.isEmpty()
                    || (allowNegative ? value.matches("-?\\d*") : value.matches("\\d*")));
        } else {
            box.setMessage(narration);
        }
        place(box, x, y, Math.max(1, boxWidth));
        return box;
    }

    private static void place(EditBox box, int x, int y, int boxWidth) {
        box.setX(x);
        box.setY(y);
        box.setWidth(Math.max(1, boxWidth));
    }

    private void refreshOnlineNames() {
        onlineNames.clear();
        if (minecraft != null && minecraft.getConnection() != null) {
            for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                onlineNames.add(info.getProfile().getName());
            }
        }
    }

    /** Online names filtered by the name search (case-insensitive substring). */
    private List<String> visibleOnlineNames() {
        if (onlineSearchQuery == null || onlineSearchQuery.isBlank()) {
            return onlineNames;
        }
        String q = onlineSearchQuery.toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        for (String name : onlineNames) {
            if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(name);
            }
        }
        return out;
    }

    private List<RewardEntry> buildRewards() {
        List<RewardEntry> built = new ArrayList<>();
        int greenApples = parseIntSafe(greenApplesBox != null ? greenApplesBox.getValue() : "0", 0);
        int cards = parseIntSafe(cardAmountBox != null ? cardAmountBox.getValue() : "0", 0);
        int selfCards = parseIntSafe(selfSelectAmountBox != null ? selfSelectAmountBox.getValue() : "0", 0);
        int limitBreakCards = parseIntSafe(limitBreakAmountBox != null ? limitBreakAmountBox.getValue() : "0", 0);
        if (greenApples != 0) {
            built.add(new RewardEntry(RewardEntry.GREEN_APPLES, greenApples, ""));
        }
        if (cards != 0) {
            built.add(new RewardEntry(RewardEntry.FACTION_CARD, cards, FACTIONS[factionIdx]));
        }
        if (selfCards != 0) {
            built.add(new RewardEntry(RewardEntry.SELF_SELECT_CARD, selfCards, ""));
        }
        if (limitBreakCards != 0) {
            built.add(new RewardEntry(RewardEntry.LIMIT_BREAK_CARD, limitBreakCards, ""));
        }
        if (!skinEntry.isBlank()) {
            built.add(new RewardEntry(RewardEntry.SKIN, 1, skinEntry));
        }
        int crateAmount = parseIntSafe(crateAmountBox != null ? crateAmountBox.getValue() : "0", 0);
        List<CrateCatalog.Entry> entries = CrateCatalog.publishedEntries();
        if (crateAmount != 0 && !entries.isEmpty()) {
            built.add(new RewardEntry(
                    keyAttachment ? RewardEntry.KEY : RewardEntry.CRATE,
                    crateAmount,
                    entries.get(Mth.clamp(crateIdx, 0, entries.size() - 1)).id()));
        }
        return built;
    }

    private void send() {
        if (!editable() || sendCooldownTicks > 0) {
            return;
        }
        List<String> targets = new ArrayList<>();
        if (targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
            targets.addAll(selectedOnline);
            if (targets.isEmpty()) {
                status = tr("status.choose_online_first").getString();
                page = PAGE_RECIPIENTS;
                rebuildPage();
                return;
            }
            if (targets.size() > MailTargetParse.MAX_TARGETS) {
                status = tr("status.too_many_targets", MailTargetParse.MAX_TARGETS).getString();
                return;
            }
        } else if (targetMode == MailComposeC2SPayload.MODE_OFFLINE_NAME) {
            String raw = offlineArea != null ? offlineArea.getValue() : "";
            List<String> names = MailTargetParse.splitNames(raw);
            if (names.isEmpty()) {
                status = tr("status.enter_name_first").getString();
                page = PAGE_RECIPIENTS;
                rebuildPage();
                return;
            }
            targets.addAll(names);
        }
        if (titleBox == null || titleBox.getValue().isBlank()) {
            status = tr("status.empty_title").getString();
            page = PAGE_CONTENT;
            rebuildPage();
            return;
        }

        int expiresDays = parseIntSafe(expiresBox != null ? expiresBox.getValue() : "0", 0);
        if (targetMode == MailComposeC2SPayload.MODE_ALL_PLAYERS && expiresDays <= 0) {
            expiresDays = MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS;
            if (expiresBox != null) {
                expiresBox.setValue(String.valueOf(expiresDays));
            }
        }

        String senderValue = (senderBox == null || senderBox.getValue().isBlank())
                ? tr("default.sender").getString()
                : senderBox.getValue();
        MailComposeC2SPayload payload = new MailComposeC2SPayload(
                targetMode,
                targets,
                senderValue,
                titleBox.getValue(),
                bodyArea == null ? "" : bodyArea.getValue(),
                expiresDays,
                buildRewards()
        );

        if (targetMode == MailComposeC2SPayload.MODE_ALL_PLAYERS && minecraft != null) {
            int finalExpires = expiresDays;
            String summary = rewardSummary();
            minecraft.setScreen(new ConfirmScreen(confirmed -> {
                minecraft.setScreen(this);
                if (confirmed) {
                    dispatchMailPayload(payload);
                }
            }, tr("confirm_all.title"), tr("confirm_all.body", titleBox.getValue(), finalExpires, summary)));
            return;
        }
        dispatchMailPayload(payload);
    }

    private void dispatchMailPayload(MailComposeC2SPayload payload) {
        if (!editable() || sendCooldownTicks > 0) {
            return;
        }
        try {
            ClientPlayNetworking.send(payload);
            sendCooldownTicks = SEND_COOLDOWN_TICKS;
            status = tr("status.submitted").getString();
            updateSendButtonState();
        } catch (Throwable t) {
            String detail = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            status = tr("status.send_failed", detail).getString();
        }
    }

    private static int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value == null || value.isBlank() ? String.valueOf(fallback) : value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String rewardSummary() {
        List<RewardEntry> rewards = buildRewards();
        if (rewards.isEmpty()) {
            return tr("summary.none").getString();
        }
        List<String> parts = new ArrayList<>();
        for (RewardEntry reward : rewards) {
            switch (reward.kind()) {
                case RewardEntry.SKIN -> parts.add(tr("summary.skin", reward.factionType()).getString());
                case RewardEntry.GREEN_APPLES -> parts.add(tr("summary.green_apples", reward.amount()).getString());
                case RewardEntry.FACTION_CARD -> parts.add(
                        tr("summary.faction_card", tr(FACTION_KEYS[factionIdx]).getString(), reward.amount()).getString());
                case RewardEntry.SELF_SELECT_CARD -> parts.add(
                        tr("summary.self_select", reward.amount()).getString());
                case RewardEntry.LIMIT_BREAK_CARD -> parts.add(
                        Component.translatable("screen.habitrain_lottery.config.cards.limit_break").getString()
                                + " x" + reward.amount());
                case RewardEntry.CRATE -> parts.add(
                        tr("summary.crate", reward.factionType(), reward.amount()).getString());
                case RewardEntry.KEY -> parts.add(
                        tr("summary.key", reward.factionType(), reward.amount()).getString());
                default -> {
                }
            }
        }
        return String.join("  ·  ", parts);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (suppressNestedBackground) {
            return;
        }
        super.renderBackground(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (MenuAccessBridge.isLocked()) {
            renderBackground(graphics, mouseX, mouseY, delta);
            AdminLockGuard.renderLockedScreen(graphics, font, width, height, backButton, mouseX, mouseY, delta);
            return;
        }
        renderBackground(graphics, mouseX, mouseY, delta);
        drawFrame(graphics);
        drawPageLabels(graphics);

        suppressNestedBackground = true;
        try {
            super.render(graphics, mouseX, mouseY, delta);
        } finally {
            suppressNestedBackground = false;
        }

        if (page == PAGE_RECIPIENTS && targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
            onlineList.renderScrollbar(graphics);
        } else if (page == PAGE_CRATES && crateTotalRows > crateVisibleRows) {
            renderCrateGridScrollbar(graphics);
        }
    }

    private void renderCrateGridScrollbar(GuiGraphics g) {
        int max = Math.max(0, crateTotalRows - crateVisibleRows);
        if (max <= 0) {
            return;
        }
        int trackH = Math.max(12, crateGridBottom - crateGridTop);
        int trackX = contentX + contentWidth - 3;
        g.fill(trackX, crateGridTop, trackX + 3, crateGridTop + trackH, 0x30FFFFFF);
        int thumbH = Math.max(12, trackH * crateVisibleRows / Math.max(1, crateTotalRows));
        int thumbY = crateGridTop + (trackH - thumbH) * crateRowScroll / max;
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, BRASS);
    }

    private void drawFrame(GuiGraphics graphics) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelBottom, PANEL_BORDER);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelBottom - 1, PANEL_BG);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 27, PANEL_HEADER);
        graphics.fill(panelX + 1, panelY + 26, panelX + panelWidth - 1, panelY + 28, BRASS);

        graphics.drawString(font, tr("header"), contentX, panelY + 10, TEXT, false);
        String opStamp = tr("op_stamp").getString();
        int stampWidth = font.width(opStamp);
        if (contentWidth > 180) {
            graphics.drawString(font, opStamp, contentX + contentWidth - stampWidth, panelY + 10,
                    0xFFE7B85D, false);
        }

        graphics.fill(contentX, footerY - 6, contentX + contentWidth, footerY - 5, 0x407C8994);
        String clippedStatus = font.plainSubstrByWidth(status == null ? "" : status, contentWidth);
        graphics.drawString(font, clippedStatus, contentX, footerY - 16, BRASS, false);

        for (int y = contentTop + 3; y < contentBottom; y += 12) {
            graphics.fill(panelX + 5, y, panelX + 7, y + 2, 0x807A5A2B);
        }
    }

    private void drawPageLabels(GuiGraphics graphics) {
        if (page == PAGE_CONTENT) {
            if (senderBox != null) {
                graphics.drawString(font, tr("field.sender"), senderBox.getX(), senderBox.getY() - 10, MUTED, false);
            }
            if (titleBox != null) {
                graphics.drawString(font, tr("field.title"), titleBox.getX(), titleBox.getY() - 10, MUTED, false);
            }
            if (bodyArea != null) {
                graphics.drawString(font, tr("field.body"), bodyArea.getX(), bodyArea.getY() - 10, MUTED, false);
            }
            return;
        }
        if (page == PAGE_RECIPIENTS) {
            graphics.drawString(font, tr("recipients.scope"), contentX, contentTop, MUTED, false);
            int detailTop = contentTop + 42;
            if (targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
                graphics.drawString(font, tr("recipients.online_selected", selectedOnline.size()),
                        contentX, detailTop, TEXT, false);
                if (onlineNames.isEmpty()) {
                    graphics.drawString(font, tr("recipients.no_online"),
                            contentX, detailTop + 54, MUTED, false);
                } else if (visibleOnlineNames().isEmpty()) {
                    graphics.drawString(font, tr("recipients.no_match"),
                            contentX, detailTop + 54, MUTED, false);
                }
            } else if (targetMode == MailComposeC2SPayload.MODE_OFFLINE_NAME) {
                graphics.drawString(font, tr("recipients.offline_hint"),
                        contentX, detailTop, TEXT, false);
            } else {
                graphics.drawCenteredString(font, tr("recipients.all_line1"),
                        contentX + contentWidth / 2, detailTop + 20, TEXT);
                graphics.drawCenteredString(font,
                        tr("recipients.all_line2", MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS),
                        contentX + contentWidth / 2, detailTop + 36, MUTED);
            }
            return;
        }

        if (page == PAGE_SKIN) {
            graphics.drawString(font, tr("skin.heading"), contentX, contentTop + 10, TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(
                    tr("summary.prefix", rewardSummary()).getString(), contentWidth),
                    contentX, contentTop + 60, BRASS, false);
            return;
        }
        if (page == PAGE_CRATES) {
            List<CrateCatalog.Entry> entries = CrateCatalog.publishedEntries();
            String kind = tr(keyAttachment ? "crates.kind_key" : "crates.kind_crate").getString();
            String selected = entries.isEmpty()
                    ? tr("crates.none").getString()
                    : entries.get(Mth.clamp(crateIdx, 0, entries.size() - 1)).id();
            graphics.drawString(font, tr("crates.heading", kind, selected), contentX, contentTop + 2, TEXT, false);
            if (entries.isEmpty()) {
                graphics.drawString(font, tr("crates.empty"), contentX, crateGridTop + 4, MUTED, false);
            }
            graphics.drawString(font, font.plainSubstrByWidth(
                    tr("summary.prefix", rewardSummary()).getString(), contentWidth),
                    contentX, cratePreviewY, BRASS, false);
            return;
        }

        graphics.drawString(font, tr("rewards.heading"), contentX, contentTop, MUTED, false);
        if (greenApplesBox != null) {
            graphics.drawString(font, tr("field.green_apples"),
                    greenApplesBox.getX(), greenApplesBox.getY() - 10, MUTED, false);
            graphics.drawString(font, tr("rewards.faction_heading"),
                    contentX, greenApplesBox.getY() + 26, MUTED, false);
        }
        if (expiresBox != null) {
            graphics.drawString(font, tr("field.expires_days"),
                    expiresBox.getX(), expiresBox.getY() - 10, MUTED, false);
        }
        if (cardAmountBox != null) {
            graphics.drawString(font, font.plainSubstrByWidth(
                    tr("field.card_amount").getString(), cardAmountBox.getWidth()),
                    cardAmountBox.getX(), cardAmountBox.getY() - 10, TEXT, false);
            String summary = font.plainSubstrByWidth(
                    tr("summary.prefix", rewardSummary()).getString(), contentWidth);
            graphics.drawString(font, summary, contentX, cardAmountBox.getY() + 29, BRASS, false);
        }
        if (selfSelectAmountBox != null) {
            graphics.drawString(font, font.plainSubstrByWidth(
                            Component.translatable("screen.habitrain_lottery.config.cards.self_select").getString(),
                            selfSelectAmountBox.getWidth()),
                    selfSelectAmountBox.getX(), selfSelectAmountBox.getY() - 10, TEXT, false);
        }
        if (limitBreakAmountBox != null) {
            graphics.drawString(font, font.plainSubstrByWidth(
                            Component.translatable("screen.habitrain_lottery.config.cards.limit_break").getString(),
                            limitBreakAmountBox.getWidth()),
                    limitBreakAmountBox.getX(), limitBreakAmountBox.getY() - 10, TEXT, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (MenuAccessBridge.isLocked()) {
            return AdminLockGuard.handleLockedMouseClicked(backButton, mouseX, mouseY, button);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        if (MenuAccessBridge.isLocked()) {
            return false;
        }
        if (page == PAGE_CONTENT && bodyArea != null
                && bodyArea.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        if (page == PAGE_RECIPIENTS
                && targetMode == MailComposeC2SPayload.MODE_OFFLINE_NAME
                && offlineArea != null
                && offlineArea.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        if (page == PAGE_RECIPIENTS && targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
            int before = onlineList.getScroll();
            if (onlineList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                if (onlineList.getScroll() != before) {
                    rebuildOnlineListRows(contentTop + 42);
                }
                return true;
            }
        }
        if (page == PAGE_CRATES && crateTotalRows > crateVisibleRows
                && mouseX >= contentX && mouseX <= contentX + contentWidth
                && mouseY >= crateGridTop && mouseY < crateGridBottom) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int max = Math.max(0, crateTotalRows - crateVisibleRows);
                int next = Mth.clamp(crateRowScroll + delta, 0, max);
                if (next != crateRowScroll) {
                    crateRowScroll = next;
                    rebuildPage();
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (MenuAccessBridge.isLocked()) {
            return AdminLockGuard.handleLockedKeyPressed(this, backButton, keyCode);
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
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
