package com.habitrain.lottery.network;

import com.habitrain.lottery.meta.MenuGateServerBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Unified server-side admin authorization check across all management C2S handlers and commands.
 * Grants operator status to permission-level >= 2 players AND singleplayer world owners,
 * subject to {@link MenuGateServerBridge} on dedicated servers (fail-closed).
 */
public final class AdminAuth {
    private AdminAuth() {
    }

    public static boolean isOpOrSingleplayerOwner(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            if (player.hasPermissions(2)) {
                return true;
            }
            MinecraftServer server = player.getServer();
            return server != null && server.isSingleplayerOwner(player.getGameProfile());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAuthorizedOp(ServerPlayer player) {
        if (!isOpOrSingleplayerOwner(player)) {
            return false;
        }
        try {
            return !MenuGateServerBridge.gateBlocked(player);
        } catch (Throwable t) {
            return false;
        }
    }
}
