package com.habitrain.lottery;

import com.habitrain.lottery.bridge.RestAreaStateBridge;
import com.habitrain.lottery.card.CardUseGates;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Spectator / rest-area / dead gate for mail, crate, shop, daily-task and skin actions
 * that must stay restricted to living lobby/valid-state players.
 *
 * <p>Fail-closed: any unexpected exception when querying player state conservatively
 * treats the player as blocked ({@code true}).
 */
public final class PlayerStateGate {
    public static final String MAIL_BLOCKED_KEY = "message.habitrain_lottery.gate.mail_blocked";
    public static final String ACTION_BLOCKED_KEY = "message.habitrain_lottery.gate.action_blocked";
    public static final String MAIL_BLOCKED = "§c[邮箱] 旁观、休息或死亡时不能领取邮件";
    public static final String ACTION_BLOCKED = "§c[提示] 旁观、休息或死亡时不能执行该操作";

    public static Component mailBlockedComponent() {
        return Component.translatable(MAIL_BLOCKED_KEY);
    }

    public static Component actionBlockedComponent() {
        return Component.translatable(ACTION_BLOCKED_KEY);
    }

    private static volatile Boolean testOverride;

    private PlayerStateGate() {
    }

    public static void setTestOverride(Boolean blocked) {
        testOverride = blocked;
    }

    public static void clearTestOverride() {
        testOverride = null;
    }

    public static boolean spectatorRestOrDead(ServerPlayer player) {
        Boolean override = testOverride;
        if (override != null) {
            return override;
        }
        if (player == null) {
            return true;
        }
        boolean spectator;
        try {
            spectator = player.isSpectator();
        } catch (Throwable t) {
            spectator = true;
        }
        boolean resting = RestAreaStateBridge.isResting(player);
        boolean dead;
        try {
            dead = !player.isAlive();
        } catch (Throwable t) {
            dead = true;
        }
        return CardUseGates.spectatorRestOrDead(spectator, resting, dead);
    }
}
