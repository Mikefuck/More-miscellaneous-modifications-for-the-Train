package com.habitrain.lottery.record;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.replay.GameReplayData;
import io.wifi.starrailexpress.api.replay.GameReplayManager;
import io.wifi.starrailexpress.api.replay.GameReplayUtils;
import io.wifi.starrailexpress.api.replay.ReplayDisplayUtils;
import io.wifi.starrailexpress.api.replay.ReplayEvent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Formats detailed match replay events (economy, tasks, shop purchases, and upstream unformatted events).
 * Lives outside Mixin packages and depends only on public {@code com.habitrain.core.api.*} and SRE APIs.
 */
public final class MatchReplayEventFormatter {

    private static final Map<String, String> BUILTIN_TASK_NAMES = Map.ofEntries(
            Map.entry("sleep", "睡一觉"),
            Map.entry("outside", "呼吸新鲜空气"),
            Map.entry("read_book", "读书"),
            Map.entry("raed_book", "读书"),
            Map.entry("eat", "吃零食"),
            Map.entry("drink", "喝饮料"),
            Map.entry("exercise", "锻炼"),
            Map.entry("meditate", "冥想"),
            Map.entry("bathe", "洗澡"),
            Map.entry("toilet", "上厕所"),
            Map.entry("chair", "坐下休息"),
            Map.entry("note_block", "玩音符盒"),
            Map.entry("be_alone", "一个人静静"),
            Map.entry("breathe", "深呼吸"),
            Map.entry("light_stove", "取暖"),
            Map.entry("clean_dust", "清扫灰尘"),
            Map.entry("transport", "运送物资"),
            Map.entry("pray", "祷告"),
            Map.entry("prune_bush", "修剪灌木"),
            Map.entry("harvest_crop", "活动筋骨"),
            Map.entry("manic", "狂躁任务"),
            Map.entry("dance", "跳舞")
    );

    private MatchReplayEventFormatter() {
    }

    public static MutableComponent translatable(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    public static Component resolveTaskDisplayName(String quest) {
        if (quest == null || quest.isBlank()) {
            return Component.literal("未知任务");
        }
        String trimmed = quest.trim();
        TaskDefinition matched = lookupCustomTaskDefinition(trimmed);
        if (matched != null && matched.isPoolEligible()) {
            String display = matched.getDisplayName();
            if (display != null && !display.isBlank() && !display.equalsIgnoreCase(matched.getTaskId())) {
                return Component.literal(stripTaskDescriptionSuffix(display));
            }
        }

        String normalized = normalizeTaskKey(trimmed);
        String builtinName = BUILTIN_TASK_NAMES.get(normalized);
        if (builtinName != null) {
            return Component.translatableWithFallback(
                    "habitrain_lottery.records.task." + normalized,
                    builtinName
            );
        }

        if (matched != null) {
            String display = matched.getDisplayName();
            if (display != null && !display.isBlank() && !display.equalsIgnoreCase(matched.getTaskId())) {
                return Component.literal(stripTaskDescriptionSuffix(display));
            }
        }

        return Component.translatableWithFallback("task." + normalized, trimmed);
    }

    static String normalizeTaskKey(String quest) {
        String key = quest == null ? "" : quest.trim();
        int colon = key.indexOf(':');
        if (colon >= 0 && colon + 1 < key.length()) {
            key = key.substring(colon + 1);
        }
        if (key.startsWith("task.")) {
            key = key.substring("task.".length());
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private static String stripTaskDescriptionSuffix(String name) {
        int sep = name.indexOf(" - ");
        if (sep > 0) {
            return name.substring(0, sep).trim();
        }
        return name;
    }

    private static TaskDefinition lookupCustomTaskDefinition(String quest) {
        try {
            if (quest.contains(":")) {
                TaskDefinition direct = TaskRegistry.get(quest);
                if (direct != null) {
                    return direct;
                }
            }
            TaskDefinition core = TaskRegistry.get("habitrain_core:" + quest.toLowerCase(Locale.ROOT));
            if (core != null) {
                return core;
            }
            for (TaskDefinition def : TaskRegistry.getAll()) {
                if (def.getTaskId().equalsIgnoreCase(quest)
                        || def.getFullId().equalsIgnoreCase(quest)
                        || def.getDisplayName().equals(quest)) {
                    return def;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static MutableComponent formatPlayerWithRole(UUID playerId, GameReplayManager manager, GameReplayData replayData) {
        if (playerId == null) {
            return Component.translatableWithFallback("sre.replay.event.unknown_player", "未知玩家")
                    .withStyle(ChatFormatting.GRAY);
        }
        String rawName = GameReplayManager.playerNames.get(playerId);
        if ((rawName == null || rawName.isBlank()) && manager != null) {
            rawName = manager.getPlayerName(playerId).getString();
        }
        if (rawName == null || rawName.isBlank()) {
            rawName = playerId.toString().substring(0, 8);
        }

        String roleId = resolveCurrentRoleId(playerId, replayData);
        ChatFormatting color = GameReplayUtils.getTMMRoleColor(roleId);
        MutableComponent out = Component.literal(rawName).withStyle(color);
        if (roleId != null && !roleId.isBlank()) {
            Component roleName = ReplayDisplayUtils.getRoleDisplayName(roleId);
            out.append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                    .append(roleName.copy().withStyle(color))
                    .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }

    private static String resolveCurrentRoleId(UUID playerId, GameReplayData replayData) {
        try {
            if (SRE.SERVER != null) {
                ServerLevel overworld = SRE.SERVER.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.maybeGet(overworld).orElse(null);
                    if (gameWorld != null) {
                        SRERole role = gameWorld.getRole(playerId);
                        if (role != null) {
                            return role.identifier().toString();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (replayData != null && replayData.getPlayerRoles() != null) {
            return replayData.getPlayerRoles().get(playerId);
        }
        return null;
    }

    /**
     * Provides localized readable text for upstream replay event types whose {@code toText()} returns {@code null}
     * (such as {@code STORE_BUY}, {@code TRAP_TRIGGERED}, {@code LOCKPICK_ATTEMPT}, {@code DOOR_*}, etc.).
     */
    public static Component resolveTimelineText(
            GameReplayManager manager,
            GameReplayData.ReplayEvent dataEvent,
            ReplayEvent replayEvent,
            Component existingText
    ) {
        if (existingText != null || dataEvent == null || dataEvent.getType() == null) {
            return existingText;
        }
        GameReplayData data = manager != null ? manager.currentReplayData : null;
        return switch (dataEvent.getType()) {
            case STORE_BUY -> formatStoreBuy(manager, data, dataEvent);
            case TRAP_TRIGGERED -> formatTrapTriggered(manager, data, dataEvent);
            case LOCKPICK_ATTEMPT -> formatLockpickAttempt(manager, data, dataEvent);
            case DOOR_LOCK -> translatable(
                    "habitrain_lottery.records.event.door_lock",
                    "%1$s 锁上了门",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data)
            ).withStyle(ChatFormatting.GRAY);
            case DOOR_UNLOCK -> translatable(
                    "habitrain_lottery.records.event.door_unlock",
                    "%1$s 解锁了门",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data)
            ).withStyle(ChatFormatting.GRAY);
            case DOOR_OPEN -> translatable(
                    "habitrain_lottery.records.event.door_open",
                    "%1$s 打开了门",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data)
            ).withStyle(ChatFormatting.GRAY);
            case DOOR_CLOSE -> translatable(
                    "habitrain_lottery.records.event.door_close",
                    "%1$s 关上了门",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data)
            ).withStyle(ChatFormatting.GRAY);
            case MOOD_CHANGE -> translatable(
                    "habitrain_lottery.records.event.mood_change",
                    "%1$s 情绪阶段变化（%2$s）",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data),
                    Component.literal(safeText(dataEvent.getMessage())).withStyle(ChatFormatting.AQUA)
            ).withStyle(ChatFormatting.GRAY);
            case PSYCHO_STATE_CHANGE -> translatable(
                    "habitrain_lottery.records.event.psycho_change",
                    "%1$s 狂暴阶段变化（%2$s）",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data),
                    Component.literal(safeText(dataEvent.getMessage())).withStyle(ChatFormatting.RED)
            ).withStyle(ChatFormatting.GRAY);
            case TASK_COMPLETE -> translatable(
                    "habitrain_lottery.records.event.task_done",
                    "%1$s 完成了「%2$s」任务",
                    formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data),
                    resolveTaskDisplayName(dataEvent.getItemUsed()).copy().withStyle(ChatFormatting.YELLOW)
            ).withStyle(ChatFormatting.GRAY);
            default -> existingText;
        };
    }

    private static Component formatStoreBuy(
            GameReplayManager manager,
            GameReplayData data,
            GameReplayData.ReplayEvent dataEvent
    ) {
        Component buyer = formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data);
        String rawItemUsed = dataEvent.getItemUsed();
        String itemIdStr = "minecraft:air";
        int amount = 1;
        if (rawItemUsed != null && !rawItemUsed.isBlank()) {
            int lastColon = rawItemUsed.lastIndexOf(':');
            if (lastColon > 0) {
                String tail = rawItemUsed.substring(lastColon + 1);
                try {
                    amount = Math.max(1, Integer.parseInt(tail));
                    itemIdStr = rawItemUsed.substring(0, lastColon);
                } catch (NumberFormatException ignored) {
                    itemIdStr = rawItemUsed;
                }
            } else {
                itemIdStr = rawItemUsed;
            }
        }
        ResourceLocation itemId = ResourceLocation.tryParse(itemIdStr);
        Component itemName = (itemId != null
                ? GameReplayUtils.getItemDisplayName(itemId)
                : Component.literal(itemIdStr)).copy().withStyle(ChatFormatting.AQUA);

        int price = parseNonNegativeInt(dataEvent.getMessage());
        Component priceComp = Component.literal(String.valueOf(price)).withStyle(ChatFormatting.GOLD);
        if (amount > 1) {
            return translatable(
                    "habitrain_lottery.records.event.store_buy_multi",
                    "%1$s 在商店购买了 %2$s ×%3$s，花费 %4$s 金币",
                    buyer,
                    itemName,
                    Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.WHITE),
                    priceComp
            ).withStyle(ChatFormatting.YELLOW);
        }
        return translatable(
                "habitrain_lottery.records.event.store_buy_one",
                "%1$s 在商店购买了 %2$s，花费 %3$s 金币",
                buyer,
                itemName,
                priceComp
        ).withStyle(ChatFormatting.YELLOW);
    }

    private static Component formatTrapTriggered(
            GameReplayManager manager,
            GameReplayData data,
            GameReplayData.ReplayEvent dataEvent
    ) {
        UUID trapperId = dataEvent.getSourcePlayer();
        UUID victimId = dataEvent.getTargetPlayer();
        Component victim = formatPlayerWithRole(victimId != null ? victimId : trapperId, manager, data);
        if (trapperId != null && victimId != null) {
            Component trapper = formatPlayerWithRole(trapperId, manager, data);
            return translatable(
                    "habitrain_lottery.records.event.trap_triggered",
                    "%1$s 触发了 %2$s 布下的陷阱",
                    victim,
                    trapper
            ).withStyle(ChatFormatting.GOLD);
        }
        return translatable(
                "habitrain_lottery.records.event.trap_triggered_unknown",
                "%1$s 触发了陷阱",
                victim
        ).withStyle(ChatFormatting.GOLD);
    }

    private static Component formatLockpickAttempt(
            GameReplayManager manager,
            GameReplayData data,
            GameReplayData.ReplayEvent dataEvent
    ) {
        Component actor = formatPlayerWithRole(dataEvent.getSourcePlayer(), manager, data);
        boolean success = Boolean.parseBoolean(dataEvent.getMessage());
        if (success) {
            return translatable(
                    "habitrain_lottery.records.event.lockpick_success",
                    "%1$s 撬锁成功",
                    actor
            ).withStyle(ChatFormatting.GREEN);
        }
        return translatable(
                "habitrain_lottery.records.event.lockpick_failed",
                "%1$s 撬锁失败",
                actor
        ).withStyle(ChatFormatting.GRAY);
    }

    private static int parseNonNegativeInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String safeText(String raw) {
        return raw == null || raw.isBlank() ? "?" : raw;
    }
}
