package com.habitrain.lottery.card;

import com.habitrain.core.api.match.MatchStateApi;
import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

/**
 * Unified multi-dimension, fail-closed match state gate across card activation,
 * crate opening, mail reward claiming, daily shop purchases, and reconnect recovery.
 */
public final class ServerMatchGate {
    public static final String MATCH_ACTIVE_BLOCKED_KEY = "message.habitrain_lottery.gate.match_active";
    public static final String MATCH_ACTIVE_BLOCKED = "§c[提示] 对局进行中不能执行此操作";

    public static Component matchActiveBlockedComponent() {
        return Component.translatable(MATCH_ACTIVE_BLOCKED_KEY);
    }

    private static volatile Boolean testOverride;

    private ServerMatchGate() {
    }

    public static void setTestOverride(Boolean active) {
        testOverride = active;
    }

    public static void clearTestOverride() {
        testOverride = null;
    }

    /**
     * Checks all dimensions on {@code server}. Returns {@code false} only when {@code server == null}
     * (offline/unit-test context without a live server) or every loaded {@link ServerLevel} reports
     * lobby/inactive state. Any exception while inspecting a live server fails closed ({@code true}).
     */
    public static boolean hasActiveMatch(MinecraftServer server) {
        Boolean override = testOverride;
        if (override != null) {
            return override;
        }
        if (server == null) {
            return false;
        }
        try {
            ArrayList<Boolean> flags = new ArrayList<>();
            Iterable<ServerLevel> levels = server.getAllLevels();
            if (levels == null) {
                return true;
            }
            for (ServerLevel level : levels) {
                flags.add(MatchStateApi.hasLeftLobby(level));
            }
            boolean[] perLevel = new boolean[flags.size()];
            for (int i = 0; i < flags.size(); i++) {
                perLevel[i] = Boolean.TRUE.equals(flags.get(i));
            }
            return CardUseGates.gameNotInactive(false, CardUseGates.anyLevelNotInactive(perLevel));
        } catch (Throwable t) {
            return CardUseGates.gameNotInactive(true, false);
        }
    }

    /**
     * Resolves {@link MinecraftServer} from {@code player} (falling back to {@link HabiLotteryMod#getServer()}
     * or {@code player.level()}) and checks all levels fail-closed.
     */
    public static boolean hasActiveMatch(ServerPlayer player) {
        Boolean override = testOverride;
        if (override != null) {
            return override;
        }
        if (player == null) {
            return hasActiveMatch(HabiLotteryMod.getServer());
        }
        try {
            MinecraftServer server = player.getServer();
            if (server != null) {
                return hasActiveMatch(server);
            }
            ServerLevel level = player.serverLevel();
            if (level == null) {
                return hasActiveMatch(HabiLotteryMod.getServer());
            }
            return CardUseGates.gameNotInactive(false, MatchStateApi.hasLeftLobby(level));
        } catch (Throwable t) {
            return CardUseGates.gameNotInactive(true, false);
        }
    }
}
