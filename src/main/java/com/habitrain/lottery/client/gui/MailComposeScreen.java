package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.mail.MailTargetParse;
import com.habitrain.lottery.network.MailComposeC2SPayload;
import com.habitrain.lottery.network.MailComposeC2SPayload.RewardEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OP mail composer. The three-step layout keeps content, recipients, and rewards
 * visually separate so the full feature set remains usable at small GUI scales.
 */
public class MailComposeScreen extends Screen {
    private static final int PAGE_CONTENT = 0;
    private static final int PAGE_RECIPIENTS = 1;
    private static final int PAGE_REWARDS = 2;
    private static final int PAGE_SKIN = 3;
    private EditBox skinBox;
    private String skinEntry = "";

    private static final String[] PAGE_LABELS = {"1 写邮件", "2 收件人", "3 附件", "4 皮肤"};
    private static final String[] FACTIONS = {"killer", "civilian", "neutral", "neutral_for_killer"};
    private static final String[] FACTION_LABELS = {"杀手", "平民", "中立", "杀手中立"};

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
    private EditBox goldBox;
    private EditBox drawsBox;
    private EditBox cardAmountBox;
    private EditBox selfSelectAmountBox;
    private EditBox limitBreakAmountBox;
    private EditBox expiresBox;

    private int page = PAGE_CONTENT;
    private int targetMode = MailComposeC2SPayload.MODE_ONLINE_LIST;
    private int factionIdx;

    private final List<String> selectedOnline = new ArrayList<>();
    private final List<String> onlineNames = new ArrayList<>();
    private final ScrollableButtonList onlineList = new ScrollableButtonList();
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

    public MailComposeScreen(Screen parent) {
        super(Component.literal("撰写系统邮件"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        rebuildPage();
    }

    private void rebuildPage() {
        clearWidgets();
        calculateLayout();
        buildStepButtons();

        switch (page) {
            case PAGE_RECIPIENTS -> buildRecipientsPage();
            case PAGE_REWARDS -> buildRewardsPage();
            case PAGE_SKIN -> {
                skinBox = new EditBox(font, contentX, contentTop + 24, contentWidth, 20, Component.literal("皮肤附件 type/id"));
                skinBox.setMaxLength(64); skinBox.setValue(skinEntry);
                skinBox.setHint(Component.literal("例如 knife/my_skin（留空不附加）"));
                skinBox.setResponder(value -> skinEntry = value.trim());
                addRenderableWidget(skinBox);
                skinBox.setTooltip(Tooltip.create(Component.literal("填写新皮肤组件已注册的 type/id；领取后解锁，重复领取不叠加")));
            }
            default -> buildContentPage();
        }

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(contentX, footerY, 72, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("发送邮件"), b -> send())
                .bounds(contentX + contentWidth - 104, footerY, 104, 20)
                .build());
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
        int baseWidth = Math.max(1, (contentWidth - gap * (PAGE_LABELS.length - 1)) / PAGE_LABELS.length);
        int x = contentX;
        for (int i = 0; i < PAGE_LABELS.length; i++) {
            int buttonWidth = i == PAGE_LABELS.length - 1
                    ? contentX + contentWidth - x
                    : baseWidth;
            final int nextPage = i;
            Button button = Button.builder(Component.literal(PAGE_LABELS[i]), b -> {
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
            senderBox = new EditBox(font, contentX, fieldY, fieldWidth, 18, Component.literal("发件人"));
            senderBox.setMaxLength(64);
            senderBox.setValue("系统");
        }
        place(senderBox, contentX, fieldY, fieldWidth);
        addRenderableWidget(senderBox);

        if (titleBox == null) {
            titleBox = new EditBox(font, contentX + fieldWidth + gap, fieldY, fieldWidth, 18,
                    Component.literal("标题"));
            titleBox.setMaxLength(128);
            titleBox.setValue("系统邮件");
        }
        place(titleBox, contentX + fieldWidth + gap, fieldY, fieldWidth);
        addRenderableWidget(titleBox);

        int bodyY = fieldY + 34;
        int bodyHeight = Math.max(36, contentBottom - bodyY);
        if (bodyArea == null) {
            bodyArea = new MultilineTextArea(font, contentX, bodyY, contentWidth, bodyHeight);
            bodyArea.setMaxLength(8000);
            bodyArea.setValue("请查收附件奖励。");
        } else {
            bodyArea.setX(contentX);
            bodyArea.setY(bodyY);
            bodyArea.setWidth(contentWidth);
            bodyArea.setHeight(bodyHeight);
        }
        addRenderableWidget(bodyArea);
    }

    private void buildRecipientsPage() {
        int gap = 4;
        int modeY = contentTop + 12;
        int modeWidth = Math.max(1, (contentWidth - gap * 2) / 3);
        addModeButton("在线多选", MailComposeC2SPayload.MODE_ONLINE_LIST,
                contentX, modeY, modeWidth);
        addModeButton("所有玩家", MailComposeC2SPayload.MODE_ALL_PLAYERS,
                contentX + modeWidth + gap, modeY, modeWidth);
        addModeButton("按名字发送", MailComposeC2SPayload.MODE_OFFLINE_NAME,
                contentX + (modeWidth + gap) * 2, modeY,
                contentX + contentWidth - (contentX + (modeWidth + gap) * 2));

        int detailTop = modeY + 30;
        if (targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
            refreshOnlineNames();
            selectedOnline.removeIf(name -> !onlineNames.contains(name));
            List<String> visible = visibleOnlineNames();

            int smallButtonWidth = Math.min(58, Math.max(42, contentWidth / 5));
            addRenderableWidget(Button.builder(Component.literal("全选"), b -> {
                refreshOnlineNames();
                selectedOnline.clear();
                selectedOnline.addAll(visibleOnlineNames());
                status = "已选择 " + selectedOnline.size() + " 名在线玩家";
                rebuildPage();
            }).bounds(contentX + contentWidth - smallButtonWidth * 2 - gap, detailTop,
                    smallButtonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("清空"), b -> {
                selectedOnline.clear();
                status = "已清空在线玩家选择";
                rebuildPage();
            }).bounds(contentX + contentWidth - smallButtonWidth, detailTop,
                    smallButtonWidth, 20).build());

            onlineSearchBox = new EditBox(font, contentX, detailTop + 24, contentWidth, 16,
                    Component.literal("搜索"));
            onlineSearchBox.setMaxLength(64);
            onlineSearchBox.setValue(onlineSearchQuery == null ? "" : onlineSearchQuery);
            onlineSearchBox.setHint(Component.literal("搜索玩家名"));
            onlineSearchBox.setResponder(s -> {
                String next = s == null ? "" : s;
                if (next.equals(onlineSearchQuery)) {
                    return;
                }
                onlineSearchQuery = next;
                onlineList.setScroll(0);
                rebuildPage();
                if (onlineSearchBox != null) {
                    onlineSearchBox.setFocused(true);
                    setFocused(onlineSearchBox);
                    onlineSearchBox.setCursorPosition(onlineSearchQuery.length());
                }
            });
            addRenderableWidget(onlineSearchBox);

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
                rebuildPage();
            });
            onlineList.rebuildWidgets(this::addRenderableWidget, null);
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

    private void addModeButton(String label, int mode, int x, int y, int buttonWidth) {
        Button button = Button.builder(Component.literal(label), b -> {
            targetMode = mode;
            if (mode == MailComposeC2SPayload.MODE_ALL_PLAYERS && expiresBox != null) {
                expiresBox.setValue(String.valueOf(MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS));
                status = "已启用对所有玩家发放，邮件默认限时 "
                        + MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS + " 天";
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
        int fieldWidth = Math.max(1, (contentWidth - gap * 2) / 3);
        int fieldY = contentTop + 12;

        int drawsButtonWidth = Math.min(34, Math.max(24, fieldWidth / 3));
        int drawsInputWidth = Math.max(1, fieldWidth - drawsButtonWidth - 4);
        drawsBox = prepareNumberBox(drawsBox, contentX, fieldY, drawsInputWidth, true, "抽数附件");
        Button addDrawButton = Button.builder(Component.literal("+1"), b -> addOneDraw())
                .bounds(contentX + drawsInputWidth + 4, fieldY, drawsButtonWidth, 18)
                .build();
        addDrawButton.setTooltip(Tooltip.create(Component.literal("向邮件附件增加 1 次抽奖机会")));
        goldBox = prepareNumberBox(goldBox, contentX + fieldWidth + gap, fieldY, fieldWidth, true, "金币");
        boolean freshExpiresBox = expiresBox == null;
        expiresBox = prepareNumberBox(expiresBox, contentX + (fieldWidth + gap) * 2, fieldY,
                contentX + contentWidth - (contentX + (fieldWidth + gap) * 2), false, "有效期");
        if (freshExpiresBox && targetMode == MailComposeC2SPayload.MODE_ALL_PLAYERS) {
            expiresBox.setValue(String.valueOf(MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS));
        }
        addRenderableWidget(drawsBox);
        addRenderableWidget(addDrawButton);
        addRenderableWidget(goldBox);
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
            String label = (i == factionIdx ? "◆ " : "") + FACTION_LABELS[i];
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
                true, "阵营卡数量");
        addRenderableWidget(cardAmountBox);
        selfSelectAmountBox = prepareNumberBox(selfSelectAmountBox, contentX + amountWidth + gap, cardAmountY, amountWidth,
                true, "自选卡数量");
        addRenderableWidget(selfSelectAmountBox);
        Component limitBreakLabel = Component.translatable("screen.habitrain_lottery.config.cards.limit_break");
        limitBreakAmountBox = prepareNumberBox(limitBreakAmountBox,
                contentX + (amountWidth + gap) * 2, cardAmountY, amountWidth, true, limitBreakLabel.getString());
        limitBreakAmountBox.setTooltip(Tooltip.create(limitBreakLabel));
        addRenderableWidget(limitBreakAmountBox);
    }

    private void addOneDraw() {
        if (drawsBox == null) {
            return;
        }
        int current = parseIntSafe(drawsBox.getValue(), 0);
        if (current == Integer.MAX_VALUE) {
            status = "抽数已达到可填写的最大值";
            return;
        }
        drawsBox.setValue(String.valueOf(current + 1));
        status = "已向附件添加 1 次抽奖机会，当前共 " + (current + 1) + " 次";
    }

    private EditBox prepareNumberBox(EditBox box, int x, int y, int boxWidth,
                                     boolean allowNegative, String narration) {
        if (box == null) {
            box = new EditBox(font, x, y, Math.max(1, boxWidth), 18, Component.literal(narration));
            box.setMaxLength(8);
            box.setValue("0");
            box.setFilter(value -> value.isEmpty()
                    || (allowNegative ? value.matches("-?\\d*") : value.matches("\\d*")));
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
        int draws = parseIntSafe(drawsBox != null ? drawsBox.getValue() : "0", 0);
        int gold = parseIntSafe(goldBox != null ? goldBox.getValue() : "0", 0);
        int cards = parseIntSafe(cardAmountBox != null ? cardAmountBox.getValue() : "0", 0);
        int selfCards = parseIntSafe(selfSelectAmountBox != null ? selfSelectAmountBox.getValue() : "0", 0);
        int limitBreakCards = parseIntSafe(limitBreakAmountBox != null ? limitBreakAmountBox.getValue() : "0", 0);
        if (draws != 0) {
            built.add(new RewardEntry(RewardEntry.DRAWS, draws, ""));
        }
        if (gold != 0) {
            built.add(new RewardEntry(RewardEntry.COINS, gold, ""));
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
        if (!skinEntry.isBlank()) built.add(new RewardEntry(RewardEntry.SKIN, 1, skinEntry));
        return built;
    }

    private void send() {
        List<String> targets = new ArrayList<>();
        if (targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
            targets.addAll(selectedOnline);
            if (targets.isEmpty()) {
                status = "请先在“选收件人”中选择在线玩家";
                page = PAGE_RECIPIENTS;
                rebuildPage();
                return;
            }
            if (targets.size() > MailTargetParse.MAX_TARGETS) {
                status = "最多可选择 " + MailTargetParse.MAX_TARGETS + " 个目标";
                return;
            }
        } else if (targetMode == MailComposeC2SPayload.MODE_OFFLINE_NAME) {
            String raw = offlineArea != null ? offlineArea.getValue() : "";
            List<String> names = MailTargetParse.splitNames(raw);
            if (names.isEmpty()) {
                status = "请至少输入一个玩家名";
                page = PAGE_RECIPIENTS;
                rebuildPage();
                return;
            }
            targets.addAll(names);
        }
        if (titleBox == null || titleBox.getValue().isBlank()) {
            status = "邮件标题不能为空";
            page = PAGE_CONTENT;
            rebuildPage();
            return;
        }

        int expiresDays = parseIntSafe(expiresBox != null ? expiresBox.getValue() : "0", 0);
        if (targetMode == MailComposeC2SPayload.MODE_ALL_PLAYERS && expiresDays <= 0) {
            // 所有玩家模式未填写有效期时兜底为 30 天（与服务端一致）。
            expiresDays = MailComposeC2SPayload.DEFAULT_ALL_PLAYER_EXPIRY_DAYS;
            if (expiresBox != null) {
                expiresBox.setValue(String.valueOf(expiresDays));
            }
        }

        MailComposeC2SPayload payload = new MailComposeC2SPayload(
                targetMode,
                targets,
                senderBox == null ? "系统" : senderBox.getValue(),
                titleBox.getValue(),
                bodyArea == null ? "" : bodyArea.getValue(),
                expiresDays,
                buildRewards()
        );
        try {
            ClientPlayNetworking.send(payload);
            status = "邮件已提交到服务器";
        } catch (Throwable t) {
            status = "发送失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
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
            return "当前无附件";
        }
        List<String> parts = new ArrayList<>();
        for (RewardEntry reward : rewards) {
            switch (reward.kind()) {
                case RewardEntry.SKIN -> parts.add("皮肤 " + reward.factionType());
                case RewardEntry.DRAWS -> parts.add("抽数 " + reward.amount());
                case RewardEntry.COINS -> parts.add("金币 " + reward.amount());
                case RewardEntry.FACTION_CARD ->
                        parts.add(FACTION_LABELS[factionIdx] + "阵营卡 x" + reward.amount());
                case RewardEntry.SELF_SELECT_CARD -> parts.add("自选卡 x" + reward.amount());
                case RewardEntry.LIMIT_BREAK_CARD -> parts.add(Component.translatable(
                        "screen.habitrain_lottery.config.cards.limit_break").getString() + " x" + reward.amount());
                default -> {
                }
            }
        }
        return String.join("  ·  ", parts);
    }

    private boolean suppressNestedBackground;

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (suppressNestedBackground) {
            return;
        }
        super.renderBackground(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
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
        }
    }

    private void drawFrame(GuiGraphics graphics) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelBottom, PANEL_BORDER);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelBottom - 1, PANEL_BG);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 27, PANEL_HEADER);
        graphics.fill(panelX + 1, panelY + 26, panelX + panelWidth - 1, panelY + 28, BRASS);

        graphics.drawString(font, "列车邮局 · 系统邮件", contentX, panelY + 10, TEXT, false);
        String opStamp = "OP 专用";
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
            graphics.drawString(font, "发件人", senderBox.getX(), senderBox.getY() - 10, MUTED, false);
            graphics.drawString(font, "邮件标题", titleBox.getX(), titleBox.getY() - 10, MUTED, false);
            graphics.drawString(font, "正文", bodyArea.getX(), bodyArea.getY() - 10, MUTED, false);
            return;
        }
        if (page == PAGE_RECIPIENTS) {
            graphics.drawString(font, "发送范围", contentX, contentTop, MUTED, false);
            int detailTop = contentTop + 42;
            if (targetMode == MailComposeC2SPayload.MODE_ONLINE_LIST) {
                graphics.drawString(font, "在线玩家 · 已选 " + selectedOnline.size() + " 人",
                        contentX, detailTop, TEXT, false);
                if (onlineNames.isEmpty()) {
                    graphics.drawString(font, "当前没有可选择的在线玩家",
                            contentX, detailTop + 54, MUTED, false);
                } else if (visibleOnlineNames().isEmpty()) {
                    graphics.drawString(font, "无匹配玩家",
                            contentX, detailTop + 54, MUTED, false);
                }
            } else if (targetMode == MailComposeC2SPayload.MODE_OFFLINE_NAME) {
                graphics.drawString(font, "玩家名（支持换行、空格、逗号或分号分隔）",
                        contentX, detailTop, TEXT, false);
            } else {
                graphics.drawCenteredString(font, "邮件将发送给服务器内的所有玩家",
                        contentX + contentWidth / 2, detailTop + 20, TEXT);
                graphics.drawCenteredString(font, "含离线与从未进服的玩家 · 默认限时 30 天",
                        contentX + contentWidth / 2, detailTop + 36, MUTED);
            }
            return;
        }

        if (page == PAGE_SKIN) {
            graphics.drawString(font, "皮肤附件（type/id）", contentX, contentTop + 10, TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth("附件预览：" + rewardSummary(), contentWidth), contentX, contentTop + 60, BRASS, false);
            return;
        }
        graphics.drawString(font, "基础奖励", contentX, contentTop, MUTED, false);
        graphics.drawString(font, "抽数附件", drawsBox.getX(), drawsBox.getY() - 10, MUTED, false);
        graphics.drawString(font, "金币", goldBox.getX(), goldBox.getY() - 10, MUTED, false);
        graphics.drawString(font, "有效期（天）",
                expiresBox.getX(), expiresBox.getY() - 10, MUTED, false);
        graphics.drawString(font, "阵营卡类型（四选一）", contentX, drawsBox.getY() + 26, MUTED, false);
        graphics.drawString(font, font.plainSubstrByWidth("阵营卡数量", cardAmountBox.getWidth()),
                cardAmountBox.getX(), cardAmountBox.getY() - 10, TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("screen.habitrain_lottery.config.cards.self_select").getString(), selfSelectAmountBox.getWidth()),
                selfSelectAmountBox.getX(), selfSelectAmountBox.getY() - 10, TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("screen.habitrain_lottery.config.cards.limit_break").getString(), limitBreakAmountBox.getWidth()),
                limitBreakAmountBox.getX(), limitBreakAmountBox.getY() - 10, TEXT, false);
        String summary = font.plainSubstrByWidth("附件预览：" + rewardSummary(), contentWidth);
        graphics.drawString(font, summary, contentX, cardAmountBox.getY() + 29, BRASS, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
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
                    rebuildPage();
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
