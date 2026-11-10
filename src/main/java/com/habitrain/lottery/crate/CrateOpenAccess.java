package com.habitrain.lottery.crate;

import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.bridge.RestAreaStateBridge;
import com.habitrain.lottery.card.ServerMatchGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Account crates have their own spectator exception; card/mail gates stay independent. */
public final class CrateOpenAccess {
    private CrateOpenAccess() {}

    public static String rejection(ServerPlayer player) {
        if (player == null) {
            return ServerMatchGate.hasActiveMatch((ServerPlayer) null) ? "crates.in_match" : "";
        }
        try {
            if (player.hasDisconnected() || !player.isAlive() || RestAreaStateBridge.isResting(player)) {
                return "crates.unavailable";
            }
            boolean spectator = player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
            return rejection(spectator, PlayerStateGate.spectatorRestOrDead(player),
                    !spectator && ServerMatchGate.hasActiveMatch(player));
        } catch (Throwable failure) {
            return "crates.unavailable";
        }
    }

    static String rejection(boolean physicalSpectator, boolean blockedState, boolean activeMatch) {
        if (physicalSpectator) return "";
        if (blockedState) return "crates.unavailable";
        return activeMatch ? "crates.in_match" : "";
    }
}
