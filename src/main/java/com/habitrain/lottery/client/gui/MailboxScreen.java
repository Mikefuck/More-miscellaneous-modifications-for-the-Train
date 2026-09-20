package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.mail.LocalMailboxStore;
import com.habitrain.lottery.mail.MailCommandsCodec;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.network.LotteryNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Complete player-facing mailbox: mail list on the left, full detail of the
 * selected mail on the right (sender / time / title / rewards / word-wrapped
 * content), plus claim actions.
 *
 * <p>Replaces the SRE mailbox claim surface removed in the new StarRailExpress
 * (the old {@code MailboxScreen} + {@code MailboxComponent} are gone).
 */
public class MailboxScreen extends Screen {
    private static final int PANEL_BORDER = 0xFF8B6835;
    private static final int PANEL_BG = 0xF0101720;
    private static final int PANEL_HEADER = 0xFF182531;
    private static final int BRASS = 0xFFD8A441;
    private static final int TEXT = 0xFFE8EDF1;
    private static final int MUTED = 0xFF98A4AF;
    private static final int ACCENT = 0xFF6EC7A0;
    private static final int WARN = 0xFFFFB454;

    private final Screen parent;
    private final Gson gson = new Gson();

    private List<LocalMailboxStore.MailJson> mails = new ArrayList<>();
    private int lastVersion = -1;
    private final ScrollableButtonList mailList = new ScrollableButtonList();
    private String status = "邮箱已打开";
    private LocalMailboxStore.MailJson selected;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelBottom;
    private int contentX;
    private int contentWidth;
    private int contentTop;
    private int contentBottom;
    private int footerY;

    // Detail panel geometry.
    private int listWidth;
    private int detailX;
    private int detailWidth;
    private int detailTop;
    private int detailBottom;

    public MailboxScreen(Screen parent) {
        super(Component.literal("列车邮箱"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        refreshFromCache();
        rebuildPage();
    }

    private void refreshFromCache() {
        int version = LotteryNetwork.ClientLotteryState.mailboxVersion;
        if (version == lastVersion && !mails.isEmpty()) {
            return;
        }
        lastVersion = version;
        try {
            String json = LotteryNetwork.ClientLotteryState.mailboxJson;
            mails = gson.fromJson(json == null || json.isBlank() ? "[]" : json,
                    new TypeToken<List<LocalMailboxStore.MailJson>>() {
                    }.getType());
            if (mails == null) {
                mails = new ArrayList<>();
            }
            if (selected != null) {
                selected = mails.stream()
                        .filter(m -> m != null && m.id != null && m.id.equals(selected.id))
                        .findFirst().orElse(null);
            }
        } catch (Throwable t) {
            mails = new ArrayList<>();
            status = "邮箱数据解析失败";
        }
    }

    private void rebuildPage() {
        clearWidgets();
        calculateLayout();

        List<String> labels = new ArrayList<>();
        for (LocalMailboxStore.MailJson m : mails) {
            String state = m.claimed ? "§7[已领] " : (m.read ? "§e[未领] " : "§a[新] ");
            String title = m.title == null || m.title.isBlank() ? "(无标题)" : m.title;
            String sender = m.sender == null || m.sender.isBlank() ? "系统" : m.sender;
            String time = formatTime(m.sentAt);
            labels.add(state + title + " §7· " + sender + " §8" + time);
        }
        // 列表与右侧详情区同高：顶部对齐 detailTop，高度填满到 contentBottom，
        // 随窗口高度自适应（不要再用固定 34px 边距把可用高度吃掉）。
        int listTop = contentTop + 6;
        int viewportHeight = Math.max(20, contentBottom - listTop);
        int savedScroll = mailList.getScroll();
        mailList.setBounds(contentX, listTop, listWidth, viewportHeight);
        mailList.setRowHeight(22);
        mailList.setItems(labels, 0);
        mailList.setScroll(savedScroll);
        mailList.setOnSelect(index -> {
            if (index < 0 || index >= mails.size()) {
                return;
            }
            selected = mails.get(index);
            status = "";
            rebuildPage();
        });
        mailList.rebuildWidgets(this::addRenderableWidget, null);

        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> {
            LotteryClientNetwork.clientRequestMailbox();
            status = "正在刷新…";
        }).bounds(contentX, footerY, 64, 20).build());

        addRenderableWidget(Button.builder(Component.literal("全部领取"), b -> claimAll())
                .bounds(contentX + 72, footerY, 88, 20).build());

        String claimLabel;
        boolean canClaim;
        if (selected == null) {
            claimLabel = "领取所选";
            canClaim = false;
        } else if (selected.claimed) {
            claimLabel = "已领取";
            canClaim = false;
        } else {
            claimLabel = "领取奖励";
            canClaim = true;
        }
        Button claimBtn = Button.builder(Component.literal(claimLabel), b -> claimSelected())
                .bounds(detailX + detailWidth - 92, footerY, 92, 20)
                .build();
        claimBtn.active = canClaim;
        if (selected != null && selected.claimed) {
            claimBtn.setTooltip(Tooltip.create(Component.literal("这封邮件已经领取过奖励")));
        }
        addRenderableWidget(claimBtn);

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(detailX + detailWidth - 156, footerY, 56, 20)
                .build());
    }

    private void claimSelected() {
        if (selected == null) {
            status = "请先在左侧选择一封邮件";
            return;
        }
        if (selected.claimed) {
            status = "该邮件已领取";
            return;
        }
        LotteryClientNetwork.clientClaimMail(selected.id);
        status = "领取请求已提交";
        // Refresh on next snapshot bump.
        int version = LotteryNetwork.ClientLotteryState.mailboxVersion;
        lastVersion = version;
    }

    private void claimAll() {
        LotteryClientNetwork.clientClaimMail("");
        status = "已提交全部领取请求";
    }

    private void calculateLayout() {
        panelWidth = Math.max(1, Math.min(760, width - 16));
        panelX = (width - panelWidth) / 2;
        panelY = 8;
        panelBottom = Math.max(panelY + 120, height - 8);
        contentX = panelX + 12;
        contentWidth = Math.max(1, panelWidth - 24);
        contentTop = panelY + 40;
        footerY = panelBottom - 26;
        contentBottom = Math.max(contentTop + 36, footerY - 17);

        // Detail panel: right 58% of the content area, list on the left 42%.
        int gap = 8;
        listWidth = Math.max(1, (int) (contentWidth * 0.42f));
        detailX = contentX + listWidth + gap;
        detailWidth = Math.max(1, contentWidth - listWidth - gap);
        detailTop = contentTop + 6;
        detailBottom = contentBottom;
    }

    private void drawFrame(GuiGraphics graphics) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelBottom, PANEL_BORDER);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelBottom - 1, PANEL_BG);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 27, PANEL_HEADER);
        graphics.fill(panelX + 1, panelY + 26, panelX + panelWidth - 1, panelY + 28, BRASS);

        graphics.drawString(font, "列车邮局 · 邮箱", contentX, panelY + 10, TEXT, false);
        int unclaimed = (int) mails.stream().filter(m -> m != null && !m.claimed).count();
        graphics.drawString(font, "共 " + mails.size() + " 封（未领 " + unclaimed + "）",
                contentX + contentWidth - 110, panelY + 10, MUTED, false);

        graphics.fill(contentX, footerY - 6, contentX + contentWidth, footerY - 5, 0x407C8994);
        String clipped = font.plainSubstrByWidth(status == null ? "" : status, contentWidth);
        graphics.drawString(font, clipped, contentX, footerY - 16, BRASS, false);

        // Detail divider.
        graphics.fill(detailX - 5, detailTop, detailX - 4, detailBottom, 0x307C8994);

        drawDetail(graphics);
    }

    private void drawDetail(GuiGraphics graphics) {
        if (selected == null) {
            graphics.drawString(font, "← 从左侧选择一封邮件查看详情",
                    detailX, detailTop + 12, MUTED, false);
            return;
        }
        String title = selected.title == null || selected.title.isBlank() ? "(无标题)" : selected.title;
        String sender = selected.sender == null || selected.sender.isBlank() ? "系统" : selected.sender;
        String state = selected.claimed ? "已领取" : (selected.read ? "未领取" : "新邮件");

        int y = detailTop + 4;
        graphics.drawString(font, title, detailX, y, BRASS, false);
        y += 14;
        graphics.drawString(font, font.plainSubstrByWidth(
                "§7发件人：§f" + sender + "   §7时间：§f" + formatTime(selected.sentAt), detailWidth),
                detailX, y, TEXT, false);
        y += 12;
        graphics.drawString(font, "状态：§" + (selected.claimed ? "7" : "e") + state, detailX, y,
                selected.claimed ? MUTED : WARN, false);
        y += 18;

        List<MailReward> rewards = MailCommandsCodec.decode(selected.commands);
        if (!rewards.isEmpty()) {
            graphics.drawString(font, "奖励：", detailX, y, ACCENT, false);
            y += 12;
            graphics.drawString(font, font.plainSubstrByWidth(rewardText(rewards), detailWidth),
                    detailX, y, TEXT, false);
            y += 18;
        }

        graphics.fill(detailX, y, detailX + detailWidth, y + 1, 0x407C8994);
        y += 10;

        String content = selected.content == null || selected.content.isBlank()
                ? "(这封邮件没有正文内容，奖励见上方。)"
                : selected.content;
        for (FormattedCharSequence line : wrapText(content, detailWidth)) {
            if (y + 9 > detailBottom - 4) {
                graphics.drawString(font, "…", detailX, y, MUTED, false);
                break;
            }
            graphics.drawString(font, line, detailX, y, TEXT, false);
            y += 10;
        }
    }

    /** Wrap free-text into lines that fit the detail panel width. */
    private List<FormattedCharSequence> wrapText(String text, int maxWidth) {
        List<FormattedCharSequence> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String rawLine : text.split("\n", -1)) {
            if (rawLine.isEmpty()) {
                out.add(FormattedCharSequence.EMPTY);
                continue;
            }
            for (FormattedCharSequence line : font.split(Component.literal(rawLine), maxWidth)) {
                out.add(line);
            }
        }
        return out;
    }

    private String rewardText(List<MailReward> rewards) {
        List<String> parts = new ArrayList<>();
        for (MailReward r : rewards) {
            if (r == null) {
                continue;
            }
            switch (r.kind()) {
                case SKIN -> parts.add("皮肤 " + r.factionType());
                case DRAWS -> parts.add("抽数 " + r.amount());
                case COINS -> parts.add("金币 " + r.amount());
                case SELF_SELECT_CARD -> parts.add("自选卡 x" + r.amount());
                case LIMIT_BREAK_CARD -> parts.add(Component.translatable(
                        "screen.habitrain_lottery.config.cards.limit_break").getString() + " x" + r.amount());
                case FACTION_CARD -> parts.add("阵营卡 " + (r.factionType() == null ? "?" : r.factionType()) + " x" + r.amount());
            }
        }
        return parts.isEmpty() ? "" : String.join("、", parts);
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "—";
        }
        try {
            return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
        } catch (Throwable t) {
            return "—";
        }
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
        suppressNestedBackground = true;
        try {
            super.render(graphics, mouseX, mouseY, delta);
        } finally {
            suppressNestedBackground = false;
        }
        mailList.renderScrollbar(graphics);
    }

    @Override
    public void tick() {
        super.tick();
        // Re-render when a fresh snapshot arrives.
        if (LotteryNetwork.ClientLotteryState.mailboxVersion != lastVersion) {
            refreshFromCache();
            rebuildPage();
            if (status == null || status.equals("正在刷新…")) {
                status = "";
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        int before = mailList.getScroll();
        if (mailList.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            if (mailList.getScroll() != before) {
                rebuildPage();
            }
            return true;
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
