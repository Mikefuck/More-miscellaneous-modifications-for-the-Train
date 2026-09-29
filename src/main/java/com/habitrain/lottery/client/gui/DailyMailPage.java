package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import com.habitrain.lottery.mail.MailCommandsCodec;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.network.LotteryNetwork;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 每日任务终端的「邮箱」分页：左侧信件列表、右侧信件详情、底栏刷新 / 全部领取 / 领取所选。
 *
 * <p>数据仍走原邮箱的同步通道（{@code MailboxRequestC2S} → {@code MailboxListS2C} →
 * {@link LotteryNetwork.ClientLotteryState#mailboxJson}），领取仍发 {@code MailboxClaimC2S}，
 * 这里只负责终端风格的呈现与交互。</p>
 */
final class DailyMailPage {

    private static final String KEY = "screen.habitrain_lottery.daily.mail.";
    /** 领取后在收到新列表前最多锁这么久，服务端无响应时按钮自行复活。 */
    private static final long CLAIM_TIMEOUT_MILLIS = 6_000L;
    private static final int FOOTER_H = 22;

    private final Gson gson = new Gson();
    private final Runnable clickSound;
    /** 领取结果到达后回调：余额可能变化，终端据此重新要一次每日快照。 */
    private final Runnable afterClaim;

    private List<MailJson> mails = new ArrayList<>();
    private int lastVersion;
    private boolean received;
    private boolean parseFailed;
    private String selectedId;
    private double listScroll;
    private double detailScroll;
    private int detailMaxScroll;

    private final Set<String> pending = new HashSet<>();
    private boolean pendingAll;
    private long pendingSince;
    private Component status;
    private boolean refreshing;

    private Geometry geo;
    final MailButton refreshButton;
    final MailButton claimAllButton;
    final MailButton claimButton;

    DailyMailPage(Runnable clickSound, Runnable afterClaim) {
        this.clickSound = clickSound;
        this.afterClaim = afterClaim;
        // 先展示缓存，同时把版本号记下；随后到达的新列表才算「已同步」
        this.lastVersion = LotteryNetwork.ClientLotteryState.mailboxVersion;
        parse();
        refreshButton = new MailButton(Component.translatable(KEY + "refresh"), false, this::refresh);
        claimAllButton = new MailButton(Component.translatable(KEY + "claim_all"), false, this::claimAll);
        claimButton = new MailButton(Component.translatable(KEY + "claim"), true, this::claimSelected);
        refreshButtons();
    }

    List<AbstractWidget> widgets() {
        return List.of(refreshButton, claimAllButton, claimButton);
    }

    void setVisible(boolean visible) {
        for (AbstractWidget widget : widgets()) {
            widget.visible = visible;
        }
    }

    // =====================================================================
    // 布局
    // =====================================================================

    private record Geometry(BoardRect list, BoardRect detail, BoardRect footer,
                            BoardRect refresh, BoardRect claimAll, BoardRect claim,
                            int rowH, int stride, boolean compact) {
    }

    void layout(DailyBoardLayout l) {
        BoardRect body = l.body();
        BoardRect footer = new BoardRect(body.x(), body.bottom() - FOOTER_H, body.w(), FOOTER_H);
        int mainBottom = footer.y() - 8;
        int listW = Mth.clamp((int) (body.w() * 0.42F), Math.min(120, body.w() / 2), 300);
        BoardRect list = new BoardRect(body.x(), body.y(), listW, Math.max(20, mainBottom - body.y()));
        int detailX = list.right() + 10;
        BoardRect detail = new BoardRect(detailX, body.y(), Math.max(40, body.right() - detailX),
                list.h());
        int claimW = l.compact() ? 62 : 76;
        int allW = l.compact() ? 56 : 68;
        int refreshW = l.compact() ? 40 : 50;
        BoardRect claim = new BoardRect(footer.right() - claimW, footer.y(), claimW, FOOTER_H);
        BoardRect all = new BoardRect(claim.x() - 4 - allW, footer.y(), allW, FOOTER_H);
        BoardRect refresh = new BoardRect(all.x() - 4 - refreshW, footer.y(), refreshW, FOOTER_H);
        int rowH = l.compact() ? 30 : 36;
        geo = new Geometry(list, detail, footer, refresh, all, claim, rowH, rowH + 4, l.compact());
        place(refreshButton, refresh);
        place(claimAllButton, all);
        place(claimButton, claim);
        clampScroll();
    }

    private static void place(AbstractWidget widget, BoardRect r) {
        widget.setX(r.x());
        widget.setY(r.y());
        widget.setWidth(r.w());
        widget.setHeight(r.h());
    }

    private int maxListScroll() {
        if (geo == null || mails.isEmpty()) {
            return 0;
        }
        return Math.max(0, mails.size() * geo.stride - 4 - geo.list.h());
    }

    private void clampScroll() {
        listScroll = Mth.clamp(listScroll, 0, maxListScroll());
        detailScroll = Mth.clamp(detailScroll, 0, Math.max(0, detailMaxScroll));
    }

    // =====================================================================
    // 数据
    // =====================================================================

    /** 每帧/每 tick 调用：新列表到达时重新解析，并结算等待中的领取。 */
    void sync() {
        int version = LotteryNetwork.ClientLotteryState.mailboxVersion;
        if (version == lastVersion) {
            return;
        }
        lastVersion = version;
        received = true;
        boolean wasClaiming = pendingAll || !pending.isEmpty();
        Set<String> claimedIds = new HashSet<>(pending);
        boolean wasAll = pendingAll;
        parse();
        pending.clear();
        pendingAll = false;
        if (wasClaiming) {
            status = claimResult(claimedIds, wasAll);
            afterClaim.run();
        } else if (refreshing) {
            status = null;
        }
        refreshing = false;
        refreshButtons();
        clampScroll();
    }

    private Component claimResult(Set<String> ids, boolean all) {
        if (all) {
            return unclaimedCount() == 0 ? Component.translatable(KEY + "status.claim_all_done")
                    : Component.translatable(KEY + "status.claim_failed");
        }
        for (String id : ids) {
            MailJson mail = find(id);
            if (mail == null || !mail.claimed) {
                return Component.translatable(KEY + "status.claim_failed");
            }
        }
        MailJson one = ids.size() == 1 ? find(ids.iterator().next()) : null;
        return Component.translatable(KEY + "status.claim_done", one == null ? "" : titleOf(one));
    }

    private void parse() {
        try {
            String json = LotteryNetwork.ClientLotteryState.mailboxJson;
            List<MailJson> parsed = gson.fromJson(json == null || json.isBlank() ? "[]" : json,
                    new TypeToken<List<MailJson>>() {
                    }.getType());
            mails = new ArrayList<>();
            if (parsed != null) {
                for (MailJson mail : parsed) {
                    if (mail != null) {
                        mails.add(mail);
                    }
                }
            }
            parseFailed = false;
        } catch (RuntimeException error) {
            mails = new ArrayList<>();
            parseFailed = true;
            status = Component.translatable(KEY + "status.parse_failed");
        }
        if (selectedId != null && find(selectedId) == null) {
            selectedId = null;
            detailScroll = 0;
        }
        if (selectedId == null && !mails.isEmpty()) {
            // 默认选中第一封未领取的信，没有就选第一封
            selectedId = mails.stream().filter(m -> !m.claimed).map(m -> m.id).filter(Objects::nonNull)
                    .findFirst().orElse(mails.get(0).id);
        }
    }

    private MailJson find(String id) {
        if (id == null) {
            return null;
        }
        for (MailJson mail : mails) {
            if (id.equals(mail.id)) {
                return mail;
            }
        }
        return null;
    }

    private MailJson selected() {
        return find(selectedId);
    }

    int unclaimedCount() {
        return (int) mails.stream().filter(m -> !m.claimed).count();
    }

    void tick(long now) {
        if ((pendingAll || !pending.isEmpty()) && now - pendingSince > CLAIM_TIMEOUT_MILLIS) {
            pending.clear();
            pendingAll = false;
            refreshButtons();
        }
    }

    // =====================================================================
    // 操作
    // =====================================================================

    void refresh() {
        if (LotteryClientNetwork.clientRequestMailbox()) {
            refreshing = true;
            status = Component.translatable(KEY + "status.refreshing");
        } else {
            status = Component.translatable(KEY + "status.offline");
        }
    }

    /** 静默刷新：定时轮询用，不改状态栏。 */
    void poll() {
        LotteryClientNetwork.clientRequestMailbox();
    }

    void claimSelected() {
        MailJson mail = selected();
        if (mail == null) {
            status = Component.translatable(KEY + "status.pick_first");
            return;
        }
        if (mail.claimed) {
            status = Component.translatable(KEY + "status.already");
            return;
        }
        if (pendingAll || pending.contains(mail.id)) {
            return;
        }
        if (LotteryClientNetwork.clientClaimMail(mail.id)) {
            pending.add(mail.id);
            pendingSince = System.currentTimeMillis();
            status = Component.translatable(KEY + "status.claim_sent");
        } else {
            status = Component.translatable(KEY + "status.offline");
        }
        refreshButtons();
    }

    void claimAll() {
        if (pendingAll) {
            return;
        }
        if (LotteryClientNetwork.clientClaimMail("")) {
            pendingAll = true;
            pendingSince = System.currentTimeMillis();
            status = Component.translatable(KEY + "status.claim_all_sent");
        } else {
            status = Component.translatable(KEY + "status.offline");
        }
        refreshButtons();
    }

    private void refreshButtons() {
        MailJson mail = selected();
        boolean busy = pendingAll || (mail != null && pending.contains(mail.id));
        String key;
        if (mail == null) {
            key = "claim_pick";
        } else if (mail.claimed) {
            key = "claimed";
        } else if (busy) {
            key = "claiming";
        } else {
            key = "claim";
        }
        claimButton.setMessage(Component.translatable(KEY + key));
        claimButton.active = mail != null && !mail.claimed && !busy;
        claimAllButton.setMessage(Component.translatable(KEY + (pendingAll ? "claiming" : "claim_all")));
        claimAllButton.active = !pendingAll && unclaimedCount() > 0;
    }

    private void select(int index) {
        if (index < 0 || index >= mails.size()) {
            return;
        }
        String id = mails.get(index).id;
        if (!Objects.equals(id, selectedId)) {
            selectedId = id;
            detailScroll = 0;
            if (!refreshing) {
                status = null;
            }
            refreshButtons();
        }
        ensureVisible(index);
    }

    private void ensureVisible(int index) {
        if (geo == null) {
            return;
        }
        int top = index * geo.stride;
        int bottom = top + geo.rowH;
        if (top < listScroll) {
            listScroll = top;
        } else if (bottom > listScroll + geo.list.h()) {
            listScroll = bottom - geo.list.h();
        }
        clampScroll();
    }

    private int selectedIndex() {
        for (int i = 0; i < mails.size(); i++) {
            if (Objects.equals(mails.get(i).id, selectedId)) {
                return i;
            }
        }
        return -1;
    }

    // =====================================================================
    // 输入
    // =====================================================================

    boolean mouseClicked(double mx, double my) {
        if (geo == null || !geo.list.contains(mx, my)) {
            return false;
        }
        int local = (int) (my - geo.list.y() + listScroll);
        int index = local / geo.stride;
        if (local % geo.stride >= geo.rowH || index >= mails.size()) {
            return true;
        }
        clickSound.run();
        select(index);
        return true;
    }

    boolean mouseScrolled(double mx, double my, double scrollY) {
        if (geo == null) {
            return false;
        }
        if (geo.detail.contains(mx, my) && detailMaxScroll > 0) {
            detailScroll = Mth.clamp(detailScroll - scrollY * 20.0D, 0, detailMaxScroll);
            return true;
        }
        int max = maxListScroll();
        if (max > 0) {
            listScroll = Mth.clamp(listScroll - scrollY * geo.stride * 0.8D, 0, max);
            return true;
        }
        return false;
    }

    /** ↑ / ↓ 切换信件；返回是否已处理。 */
    boolean keyPressed(int keyCode) {
        if (keyCode != 265 && keyCode != 264 || mails.isEmpty()) {
            return false;
        }
        int index = selectedIndex();
        int next = index < 0 ? 0 : Mth.clamp(index + (keyCode == 264 ? 1 : -1), 0, mails.size() - 1);
        if (next != index) {
            clickSound.run();
        }
        select(next);
        return true;
    }

    void scrollPage(int direction) {
        if (geo != null) {
            listScroll = Mth.clamp(listScroll + direction * geo.list.h(), 0, maxListScroll());
        }
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    void render(GuiGraphics g, Font font, DailyBoardLayout l, int mouseX, int mouseY, long now) {
        if (geo == null) {
            layout(l);
        }
        drawHeader(g, font, l);
        drawList(g, font, mouseX, mouseY, now);
        drawDetail(g, font, now);
        drawFooter(g, font);
    }

    private void drawHeader(GuiGraphics g, Font font, DailyBoardLayout l) {
        BoardRect title = l.title();
        DailyBoardTheme.textScaled(g, font, Component.translatable(KEY + "title").getString(),
                title.x(), title.y(), l.compact() ? 1.15F : 1.5F, DailyBoardTheme.INK);
        BoardRect clock = l.clock();
        String unread = Component.translatable(KEY + "unclaimed_badge", unclaimedCount()).getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, unread, clock.w()),
                clock.right() - Math.min(clock.w(), font.width(unread)), clock.y(),
                unclaimedCount() > 0 ? DailyBoardTheme.AMBER_DEEP : DailyBoardTheme.INK_SOFT);
        String summary = !received && mails.isEmpty()
                ? Component.translatable(KEY + "subtitle.syncing").getString()
                : Component.translatable(KEY + "overview", mails.size(), unclaimedCount()).getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, summary, l.subtitle().w()),
                l.subtitle().x(), l.subtitle().y(), DailyBoardTheme.INK_SOFT);
        if (!l.compact()) {
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font,
                            Component.translatable(KEY + "subtitle.hint").getString(), l.subtitle().w()),
                    title.x(), l.subtitle().y() + 16, DailyBoardTheme.MUTED);
        }
        DailyBoardTheme.hairline(g, l.summary().x(), l.summary().right(), l.summary().bottom() - 1,
                DailyBoardTheme.PAPER_BORDER);
    }

    private void drawList(GuiGraphics g, Font font, int mouseX, int mouseY, long now) {
        BoardRect list = geo.list;
        if (mails.isEmpty()) {
            boolean syncing = !received && !parseFailed;
            placeholder(g, font, list, syncing ? KEY + "empty.syncing" : KEY + "empty.none",
                    syncing ? KEY + "empty.syncing.hint" : KEY + "empty.none.hint", syncing, now);
            return;
        }
        GuiFx.beginClip(g, list.x() - 1, list.y(), list.right() + 1, list.bottom());
        for (int i = 0; i < mails.size(); i++) {
            int y = list.y() + i * geo.stride - (int) Math.round(listScroll);
            if (y + geo.rowH < list.y() || y > list.bottom()) {
                continue;
            }
            BoardRect r = new BoardRect(list.x(), y, list.w(), geo.rowH);
            boolean hover = list.contains(mouseX, mouseY) && r.contains(mouseX, mouseY);
            drawRow(g, font, r, mails.get(i), hover);
        }
        GuiFx.endClip(g);
        DailyBoardTheme.scrollbar(g, list.right() + 3, list, listScroll, maxListScroll());
    }

    private void drawRow(GuiGraphics g, Font font, BoardRect r, MailJson mail, boolean hover) {
        boolean selected = Objects.equals(mail.id, selectedId);
        MailState state = stateOf(mail);
        int fill = selected ? DailyBoardTheme.BLUE_SOFT
                : hover ? DailyBoardTheme.CARD_BOTTOM : mail.claimed ? DailyBoardTheme.PAPER_TOP
                : DailyBoardTheme.CARD_TOP;
        DailyBoardTheme.box(g, r, fill, selected ? DailyBoardTheme.BLUE
                : hover ? GuiFx.mix(DailyBoardTheme.CARD_LINE, state.accent, 0.4F) : DailyBoardTheme.CARD_LINE);
        g.fill(r.x(), r.y(), r.x() + 3, r.bottom(), state.accent);

        String tag = Component.translatable(state.tagKey).getString();
        int tagW = font.width(tag) + 8;
        int tagX = r.right() - 6 - tagW;
        int textX = r.x() + 10;
        g.fill(tagX, r.y() + 5, tagX + tagW, r.y() + 16, state.soft);
        g.fill(tagX, r.y() + 5, tagX + 1, r.y() + 16, state.accent);
        DailyBoardTheme.text(g, font, tag, tagX + 4, r.y() + 7, state.accent);

        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, titleOf(mail), tagX - 6 - textX),
                textX, r.y() + 7, mail.claimed ? DailyBoardTheme.INK_SOFT : DailyBoardTheme.INK);
        String meta = senderOf(mail) + "  ·  " + formatTime(mail.sentAt);
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, meta, r.right() - 8 - textX),
                textX, r.y() + (geo.compact ? 19 : 22), DailyBoardTheme.MUTED);
    }

    private void drawDetail(GuiGraphics g, Font font, long now) {
        BoardRect d = geo.detail;
        DailyBoardTheme.card(g, d);
        MailJson mail = selected();
        if (mail == null) {
            detailMaxScroll = 0;
            placeholder(g, font, d, KEY + "detail.pick", KEY + "detail.pick.hint", false, now);
            return;
        }
        MailState state = stateOf(mail);
        int x = d.x() + 10;
        int w = d.w() - 20;
        g.fill(d.x(), d.y(), d.right(), d.y() + 2, state.accent);

        // 标题栏：标题 + 状态标签
        String tag = Component.translatable(state.labelKey).getString();
        int tagW = font.width(tag) + 10;
        BoardRect chip = new BoardRect(d.right() - 10 - tagW, d.y() + 9, tagW, 13);
        DailyBoardTheme.chip(g, font, chip, tag, state.soft,
                GuiFx.mix(DailyBoardTheme.CARD_LINE, state.accent, 0.5F), state.accent);
        float scale = geo.compact ? 1.0F : 1.25F;
        int titleRoom = (int) ((chip.x() - 8 - x) / scale);
        DailyBoardTheme.textScaled(g, font, DailyBoardTheme.fit(font, titleOf(mail), titleRoom),
                x, d.y() + 10, scale, DailyBoardTheme.INK);
        int y = d.y() + (geo.compact ? 24 : 28);
        String meta = Component.translatable(KEY + "detail.from", senderOf(mail)).getString()
                + "   " + Component.translatable(KEY + "detail.time", formatTime(mail.sentAt)).getString();
        DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, meta, w), x, y, DailyBoardTheme.INK_SOFT);
        y += 11;
        if (mail.expiresAt > 0) {
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, Component.translatable(
                    KEY + "detail.expires", formatTime(mail.expiresAt)).getString(), w), x, y,
                    DailyBoardTheme.MUTED);
            y += 11;
        }
        y += 3;

        // 附件奖励：金色胶囊，自动换行
        List<String> rewards = rewardLabels(mail);
        DailyBoardTheme.text(g, font, Component.translatable(KEY + "detail.rewards").getString(), x, y,
                DailyBoardTheme.MUTED);
        y += 11;
        if (rewards.isEmpty()) {
            DailyBoardTheme.text(g, font, Component.translatable(KEY + "detail.no_rewards").getString(),
                    x, y + 2, DailyBoardTheme.FAINT);
            y += 14;
        } else {
            int cx = x;
            int chipH = 14;
            int rewardFill = mail.claimed ? DailyBoardTheme.PAPER_TOP : DailyBoardTheme.AMBER_SOFT;
            int rewardText = mail.claimed ? DailyBoardTheme.MUTED : DailyBoardTheme.GOLD;
            for (String label : rewards) {
                int cw = Math.min(w, font.width(label) + 10);
                if (cx > x && cx + cw > x + w) {
                    cx = x;
                    y += chipH + 3;
                }
                if (y + chipH > d.bottom() - 8) {
                    break;
                }
                DailyBoardTheme.chip(g, font, new BoardRect(cx, y, cw, chipH), label, rewardFill,
                        GuiFx.mix(DailyBoardTheme.CARD_LINE, rewardText, 0.35F), rewardText);
                cx += cw + 4;
            }
            y += chipH + 6;
        }
        DailyBoardTheme.hairline(g, x, x + w, y, DailyBoardTheme.CARD_LINE);
        y += 7;

        // 正文：裁剪 + 滚动
        BoardRect body = new BoardRect(x, y, w, Math.max(0, d.bottom() - 6 - y));
        String content = mail.content == null || mail.content.isBlank()
                ? Component.translatable(KEY + "detail.no_content").getString() : mail.content;
        List<FormattedCharSequence> lines = wrap(font, content, w - 6);
        int totalH = lines.size() * 10;
        detailMaxScroll = Math.max(0, totalH - body.h());
        detailScroll = Mth.clamp(detailScroll, 0, detailMaxScroll);
        if (body.h() <= 0) {
            return;
        }
        int color = mail.content == null || mail.content.isBlank() ? DailyBoardTheme.FAINT
                : DailyBoardTheme.INK_SOFT;
        GuiFx.beginClip(g, body.x(), body.y(), body.right(), body.bottom());
        for (int i = 0; i < lines.size(); i++) {
            int ly = body.y() + i * 10 - (int) detailScroll;
            if (ly + 10 < body.y() || ly > body.bottom()) {
                continue;
            }
            g.drawString(font, lines.get(i), body.x(), ly, color, false);
        }
        GuiFx.endClip(g);
        DailyBoardTheme.scrollbar(g, body.right() - 2, body, detailScroll, detailMaxScroll);
    }

    private void drawFooter(GuiGraphics g, Font font) {
        BoardRect f = geo.footer;
        DailyBoardTheme.softCard(g, f, 6, DailyBoardTheme.PAPER_TOP, DailyBoardTheme.PAPER_TOP,
                DailyBoardTheme.CARD_LINE);
        int textY = f.y() + (f.h() - 8) / 2;
        int room = geo.refresh.x() - 6 - (f.x() + 8);
        if (status != null) {
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, status.getString(), room),
                    f.x() + 8, textY, DailyBoardTheme.AMBER_DEEP);
        } else {
            String title = Component.translatable(KEY + "footer.title").getString();
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font, title, room), f.x() + 8, textY,
                    DailyBoardTheme.INK);
            int hintX = f.x() + 8 + font.width(title) + 8;
            DailyBoardTheme.text(g, font, DailyBoardTheme.fit(font,
                            Component.translatable(KEY + "footer.hint").getString(), geo.refresh.x() - 6 - hintX),
                    hintX, textY, DailyBoardTheme.MUTED);
        }
    }

    private static void placeholder(GuiGraphics g, Font font, BoardRect area, String titleKey,
                                    String hintKey, boolean spinner, long now) {
        int cx = area.cx();
        int cy = area.cy();
        int active = (int) ((now / 220) % 4);
        for (int i = 0; i < 4; i++) {
            g.fill(cx - 19 + i * 10, cy - 24, cx - 12 + i * 10, cy - 20,
                    spinner && i == active ? DailyBoardTheme.AMBER_DEEP : DailyBoardTheme.TRACK);
        }
        DailyBoardTheme.textCentered(g, font, Component.literal(DailyBoardTheme.fit(font,
                Component.translatable(titleKey).getString(), area.w() - 8)), cx, cy - 6, DailyBoardTheme.INK_SOFT);
        DailyBoardTheme.textCentered(g, font, Component.literal(DailyBoardTheme.fit(font,
                Component.translatable(hintKey).getString(), area.w() - 8)), cx, cy + 6, DailyBoardTheme.MUTED);
    }

    // =====================================================================
    // 文案
    // =====================================================================

    private enum MailState {
        NEW(DailyBoardTheme.VIOLET, DailyBoardTheme.VIOLET_SOFT, "tag.new", "state.new"),
        OPEN(DailyBoardTheme.AMBER_DEEP, DailyBoardTheme.AMBER_SOFT, "tag.unclaimed", "state.unclaimed"),
        DONE(DailyBoardTheme.GREEN, DailyBoardTheme.GREEN_SOFT, "tag.claimed", "state.claimed");

        private final int accent;
        private final int soft;
        private final String tagKey;
        private final String labelKey;

        MailState(int accent, int soft, String tag, String label) {
            this.accent = accent;
            this.soft = soft;
            this.tagKey = KEY + tag;
            this.labelKey = KEY + label;
        }
    }

    private static MailState stateOf(MailJson mail) {
        return mail.claimed ? MailState.DONE : mail.read ? MailState.OPEN : MailState.NEW;
    }

    private static String titleOf(MailJson mail) {
        return mail.title == null || mail.title.isBlank()
                ? Component.translatable(KEY + "untitled").getString() : mail.title;
    }

    private static String senderOf(MailJson mail) {
        return mail.sender == null || mail.sender.isBlank()
                ? Component.translatable(KEY + "system").getString() : mail.sender;
    }

    private static List<String> rewardLabels(MailJson mail) {
        List<String> out = new ArrayList<>();
        for (MailReward r : MailCommandsCodec.decode(mail.commands)) {
            if (r == null) {
                continue;
            }
            String label = switch (r.kind()) {
                case SKIN -> I18n.get(KEY + "reward.skin", r.factionType());
                case GREEN_APPLES -> I18n.get(KEY + "reward.green_apples", r.amount());
                case SELF_SELECT_CARD -> I18n.get(KEY + "reward.self_select", r.amount());
                case LIMIT_BREAK_CARD -> I18n.get(KEY + "reward.limit_break", r.amount());
                case FACTION_CARD -> {
                    String cardKey = "screen.habitrain_lottery.daily.card." + r.factionType();
                    yield r.factionType() != null && I18n.exists(cardKey)
                            ? I18n.get(cardKey) + " ×" + r.amount()
                            : I18n.get(KEY + "reward.faction", r.factionType() == null ? "?" : r.factionType(),
                            r.amount());
                }
                case CRATE -> I18n.get(KEY + "reward.crate", crateName(r.factionType()), r.amount());
                case KEY -> I18n.get(KEY + "reward.key", keyName(r.factionType()), r.amount());
            };
            out.add(label);
        }
        return out;
    }

    private static String crateName(String id) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        if (entry == null || entry.nameKey() == null || entry.nameKey().isBlank()) {
            return id == null ? "?" : id;
        }
        return I18n.get(entry.nameKey());
    }

    private static String keyName(String id) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        if (entry != null && entry.keyName() != null && !entry.keyName().isBlank()) {
            return I18n.get(entry.keyName());
        }
        return crateName(id);
    }

    private static List<FormattedCharSequence> wrap(Font font, String text, int maxWidth) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            if (raw.isEmpty()) {
                out.add(FormattedCharSequence.EMPTY);
                continue;
            }
            out.addAll(font.split(Component.literal(raw), Math.max(20, maxWidth)));
        }
        return out;
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "—";
        }
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
    }

    // =====================================================================
    // 控件：底栏按钮
    // =====================================================================

    final class MailButton extends AbstractWidget {

        private final boolean primary;
        private final Runnable action;
        private float hoverAnim;
        private long lastFrame = System.currentTimeMillis();

        private MailButton(Component label, boolean primary, Runnable action) {
            super(0, 0, 1, 1, label);
            this.primary = primary;
            this.action = action;
        }

        void press() {
            if (active && visible) {
                clickSound.run();
                action.run();
            }
        }

        @Override
        public void onClick(double mx, double my) {
            press();
        }

        @Override
        public void playDownSound(net.minecraft.client.sounds.SoundManager manager) {
            // press() 已经播过点击音
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            long now = System.currentTimeMillis();
            float frame = Mth.clamp(now - lastFrame, 0.0F, 120.0F);
            lastFrame = now;
            boolean hot = active && (isHovered || isFocused());
            hoverAnim = GuiFx.approach(hoverAnim, hot ? 1.0F : 0.0F, frame, 70.0F);
            BoardRect r = new BoardRect(getX(), getY(), getWidth(), getHeight());
            Font font = net.minecraft.client.Minecraft.getInstance().font;
            Component label = Component.literal(DailyBoardTheme.fit(font, getMessage().getString(), r.w() - 6));
            if (!active) {
                DailyBoardTheme.button(g, font, r, label, DailyBoardTheme.PAPER_TOP, DailyBoardTheme.CARD_LINE,
                        DailyBoardTheme.FAINT, 0);
            } else if (primary) {
                DailyBoardTheme.button(g, font, r, label, DailyBoardTheme.TEAL,
                        isFocused() ? DailyBoardTheme.AMBER : DailyBoardTheme.TEAL, 0xFFFFFFFF, hoverAnim);
            } else {
                DailyBoardTheme.button(g, font, r, label, 0xFFF0F5F4,
                        isFocused() ? DailyBoardTheme.AMBER_DEEP
                                : GuiFx.mix(DailyBoardTheme.CARD_LINE, DailyBoardTheme.GOLD, 0.35F + 0.3F * hoverAnim),
                        GuiFx.mix(DailyBoardTheme.INK_SOFT, DailyBoardTheme.GOLD, hoverAnim), hoverAnim);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
