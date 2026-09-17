package com.habitrain.lottery;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
import com.habitrain.lottery.card.CardUseGates;
import net.minecraft.server.level.ServerPlayer;

/**
 * Spectator / rest-area / dead gate for lottery economy actions that must stay
 * available to living in-match players (mail claim, coin-to-draw, loot roll, open).
 */
public final class PlayerStateGate {
    public static final String MAIL_BLOCKED = "§c[邮箱] 旁观、休息或死亡时不能领取邮件";
    public static final String DRAW_BLOCKED = "§c[抽奖] 旁观、休息或死亡时不能抽奖或兑换";
    public static final String OPEN_BLOCKED = "旁观、休息或死亡时不能打开抽奖界面";

    private PlayerStateGate() {
    }

    public static boolean spectatorRestOrDead(ServerPlayer player) {
        if (player == null) {
            return true;
        }
        boolean spectator = false;
        try {
            spectator = player.isSpectator();
        } catch (Throwable ignored) {
        }
        boolean resting = false;
        try {
            resting = EliminatedRestAreaService.isResting(player);
        } catch (Throwable ignored) {
        }
        boolean dead = false;
        try {
            dead = !player.isAlive();
        } catch (Throwable ignored) {
        }
        return CardUseGates.spectatorRestOrDead(spectator, resting, dead);
    }
}
