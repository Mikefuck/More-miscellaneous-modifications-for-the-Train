package com.habitrain.lottery.client.record;

import com.habitrain.lottery.record.MatchDetailRecorder;
import io.wifi.starrailexpress.api.replay.ReplayDisplayUtils;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.client.RecordClientUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side presentation helper for {@code MatchRecordsScreen}, rendering the dark-gold detail toggle button
 * and building the expanded detailed match view (survival status, economy/task summary, and full timeline).
 */
public final class MatchRecordsDetailView {

    private static final String STORE_BUY = "STORE_BUY";
    private static final int BANNER_H = 26;
    public static final int PANEL_PAD = 6;
    public static final int SCROLL_W = 7;
    private static final int BTN_H = 16;
    private static final int BTN_PAD_X = 7;

    private static final int GOLD = 0xFFD4AF37;
    private static final int PANEL_BORDER = 0xFF8B6914;
    private static final int CREAM = 0xFFF5E8C8;

    private static final int[] FACTION_ORDER = { 1, 5, 2, 3, 4, 0 };

    private MatchRecordsDetailView() {
    }

    public static boolean isToggleHotkey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_D;
    }

    public static int[] computeButtonBounds(
            Font font,
            MatchRecord selectedRecord,
            int rightX,
            int rightW,
            int panelY
    ) {
        Component offLabel = getButtonLabel(false);
        Component onLabel = getButtonLabel(true);
        int textW = Math.max(font.width(offLabel), font.width(onLabel));
        int btnW = textW + BTN_PAD_X * 2;
        int btnH = BTN_H;
        int dateW = selectedRecord != null
                ? font.width(RecordClientUtil.formatDate(selectedRecord.createdAt)) + 8
                : 0;
        int maxRight = rightX + rightW - PANEL_PAD - dateW;
        int btnX = Math.max(rightX + PANEL_PAD + 40, maxRight - btnW);
        int btnY = panelY + (BANNER_H - btnH) / 2;
        return new int[] { btnX, btnY, btnW, btnH };
    }

    public static boolean isClickOnToggleButton(
            Font font,
            String selectedMatchId,
            MatchRecord selectedRecord,
            int rightX,
            int rightW,
            int panelY,
            double mouseX,
            double mouseY
    ) {
        if (selectedMatchId == null || selectedRecord == null || font == null) {
            return false;
        }
        int[] b = computeButtonBounds(font, selectedRecord, rightX, rightW, panelY);
        return mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
    }

    public static void renderToggleButton(
            GuiGraphics g,
            Font font,
            String selectedMatchId,
            MatchRecord selectedRecord,
            int rightX,
            int rightW,
            int panelY,
            int mouseX,
            int mouseY,
            boolean showAllDetails
    ) {
        if (selectedMatchId == null || selectedRecord == null || font == null) {
            return;
        }
        int[] b = computeButtonBounds(font, selectedRecord, rightX, rightW, panelY);
        int bx = b[0];
        int by = b[1];
        int bw = b[2];
        int bh = b[3];

        boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        int border = showAllDetails || hovered ? GOLD : PANEL_BORDER;
        g.fill(bx, by, bx + bw, by + bh, border);

        int bgTop;
        int bgBottom;
        if (showAllDetails) {
            bgTop = hovered ? 0xFF6B5328 : 0xFF5A4520;
            bgBottom = hovered ? 0xFF473415 : 0xFF3A2A10;
        } else {
            bgTop = hovered ? 0xFF332312 : 0xFF22160A;
            bgBottom = hovered ? 0xFF22160A : 0xFF150D06;
        }
        g.fillGradient(bx + 1, by + 1, bx + bw - 1, by + bh - 1, bgTop, bgBottom);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + 2, showAllDetails ? 0x44FFE8C0 : 0x18FFE8C0);

        Component label = getButtonLabel(showAllDetails);
        int textColor = showAllDetails ? 0xFFFFE8A0 : CREAM;
        g.drawCenteredString(font, label, bx + bw / 2, by + (bh - font.lineHeight) / 2, textColor);

        if (hovered) {
            Component tooltip = showAllDetails
                    ? Component.translatableWithFallback(
                            "screen.habitrain_lottery.records.detail_tooltip_on",
                            "当前展示全部详细记录 · 点击恢复精简视图 [快捷键 D]")
                    : Component.translatableWithFallback(
                            "screen.habitrain_lottery.records.detail_tooltip_off",
                            "点击展示全部详细记录（自然经济、任务金币、商店购买等）[快捷键 D]");
            g.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    /**
     * Populates {@code detailLines} with the expanded detailed match view and returns {@code maxDetailScroll}.
     */
    public static int buildDetailedContent(
            Font font,
            MatchRecord selectedRecord,
            List<FormattedCharSequence> detailLines,
            int textW,
            int contentH
    ) {
        detailLines.clear();
        if (selectedRecord == null) {
            return 0;
        }
        String dashes = dashes(font, textW);

        // 1. 玩家名单（含存活/阵亡标签）
        if (selectedRecord.players != null && !selectedRecord.players.isEmpty()) {
            for (int faction : FACTION_ORDER) {
                appendDetailedFactionGroup(font, selectedRecord, detailLines, faction, dashes, textW);
            }
            detailLines.add(FormattedCharSequence.EMPTY);
        }

        // 2. 分流事件列表：经济/任务统计 vs 完整时间线
        List<MatchRecord.MatchEvent> summaryEvents = new ArrayList<>();
        List<MatchRecord.MatchEvent> timelineEvents = new ArrayList<>();
        if (selectedRecord.events != null) {
            for (MatchRecord.MatchEvent ev : selectedRecord.events) {
                if (ev == null) {
                    continue;
                }
                if (MatchDetailRecorder.ECON_SUMMARY_TYPE.equals(ev.type)) {
                    summaryEvents.add(ev);
                } else {
                    timelineEvents.add(ev);
                }
            }
        }

        if (!summaryEvents.isEmpty()) {
            detailLines.addAll(font.split(
                    Component.translatableWithFallback(
                            "screen.habitrain_lottery.records.detail.summary_header",
                            "经济与任务统计"
                    ).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    textW
            ));
            detailLines.addAll(font.split(Component.literal(dashes).withStyle(ChatFormatting.DARK_GRAY), textW));
            for (MatchRecord.MatchEvent summary : summaryEvents) {
                Component body = parseEventText(summary);
                MutableComponent line = Component.literal("• ").withStyle(ChatFormatting.GOLD).append(body);
                detailLines.addAll(font.split(line, textW));
            }
            detailLines.add(FormattedCharSequence.EMPTY);
        }

        // 3. 完整时间线（包含所有 hidden 事件与 STORE_BUY）
        detailLines.addAll(font.split(
                Component.translatableWithFallback(
                        "screen.habitrain_lottery.records.detail.timeline_full",
                        "完整时间线（共 %s 条记录）",
                        timelineEvents.size()
                ).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                textW
        ));
        detailLines.addAll(font.split(Component.literal(dashes).withStyle(ChatFormatting.DARK_GRAY), textW));

        if (timelineEvents.isEmpty()) {
            detailLines.addAll(font.split(
                    Component.translatable("screen.sre.records.detail.no_events").withStyle(ChatFormatting.DARK_GRAY),
                    textW
            ));
        } else {
            for (MatchRecord.MatchEvent event : timelineEvents) {
                Component body = parseEventText(event);
                MutableComponent line = Component.literal(ReplayDisplayUtils.formatTime(event.relativeTimestamp) + " ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(body);
                detailLines.addAll(font.split(line, textW));
            }
        }

        int totalH = detailLines.size() * (font.lineHeight + 2);
        return Math.max(0, totalH - contentH);
    }

    private static Component getButtonLabel(boolean showAllDetails) {
        if (showAllDetails) {
            return Component.translatableWithFallback("screen.habitrain_lottery.records.detail_on", "收起详情");
        }
        return Component.translatableWithFallback("screen.habitrain_lottery.records.detail_off", "查看详情");
    }

    private static Component parseEventText(MatchRecord.MatchEvent event) {
        Component parsed = null;
        if (event.textJson != null && !event.textJson.isBlank()) {
            parsed = RecordClientUtil.parseComponent(event.textJson);
        }
        if (parsed != null && !parsed.getString().isBlank()) {
            return parsed;
        }
        if (STORE_BUY.equals(event.type)) {
            String actor = event.actorName != null && !event.actorName.isBlank() ? event.actorName : "?";
            return Component.translatableWithFallback(
                    "habitrain_lottery.records.event.store_buy_legacy",
                    "%1$s 在商店购买了物品",
                    Component.literal(actor).withStyle(ChatFormatting.WHITE)
            ).withStyle(ChatFormatting.YELLOW);
        }
        String fallbackType = event.type != null && !event.type.isBlank() ? event.type : "EVENT";
        if (event.actorName != null && !event.actorName.isBlank()) {
            return Component.literal(event.actorName + " (" + fallbackType + ")").withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.literal("[" + fallbackType + "]").withStyle(ChatFormatting.DARK_GRAY);
    }

    private static void appendDetailedFactionGroup(
            Font font,
            MatchRecord record,
            List<FormattedCharSequence> detailLines,
            int faction,
            String dashes,
            int textW
    ) {
        List<MatchRecord.MatchPlayer> members = new ArrayList<>();
        for (MatchRecord.MatchPlayer player : record.players) {
            if (factionBucket(player.faction) == faction) {
                members.add(player);
            }
        }
        if (members.isEmpty()) {
            return;
        }
        ChatFormatting color = factionColor(faction);
        MutableComponent header = Component.translatable(factionKey(faction)).withStyle(color, ChatFormatting.BOLD);
        header.append(Component.literal(" (" + members.size() + ")").withStyle(ChatFormatting.DARK_GRAY));
        detailLines.addAll(font.split(header, textW));
        detailLines.addAll(font.split(Component.literal(dashes).withStyle(ChatFormatting.DARK_GRAY), textW));

        for (MatchRecord.MatchPlayer player : members) {
            MutableComponent line = Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(player.name == null ? "?" : player.name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  "))
                    .append(ReplayDisplayUtils.getRoleDisplayName(player.roleId).withStyle(color));
            if (player.oldRoleId != null && !player.oldRoleId.isBlank()) {
                line.append(Component.literal("  "))
                        .append(Component.translatable("screen.sre.records.detail.old_role",
                                ReplayDisplayUtils.getRoleDisplayName(player.oldRoleId))
                                .withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(Component.literal("  "));
            if (player.alive) {
                line.append(Component.translatableWithFallback(
                        "screen.habitrain_lottery.records.detail.alive", "[存活]")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                line.append(Component.translatableWithFallback(
                        "screen.habitrain_lottery.records.detail.dead", "[阵亡]")
                        .withStyle(ChatFormatting.RED));
            }
            detailLines.addAll(font.split(line, textW));
        }
        detailLines.add(FormattedCharSequence.EMPTY);
    }

    private static int factionBucket(int faction) {
        return (faction >= 1 && faction <= 5) ? faction : 0;
    }

    private static String factionKey(int faction) {
        return switch (faction) {
            case 1 -> "display.type.role.innocent";
            case 2 -> "display.type.role.neutral";
            case 3 -> "display.type.role.neutral_for_killer";
            case 4 -> "display.type.role.killer";
            case 5 -> "display.type.role.vigilante";
            default -> "screen.sre.records.faction.unknown";
        };
    }

    private static ChatFormatting factionColor(int faction) {
        return switch (faction) {
            case 1 -> ChatFormatting.GREEN;
            case 2 -> ChatFormatting.YELLOW;
            case 3 -> ChatFormatting.LIGHT_PURPLE;
            case 4 -> ChatFormatting.RED;
            case 5 -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
    }

    private static String dashes(Font font, int textW) {
        int count = textW / Math.max(1, font.width("─"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("─");
        }
        return sb.toString();
    }
}
