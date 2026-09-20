package com.habitrain.lottery;

import com.habitrain.lottery.bridge.RestAreaStateBridge;
import com.habitrain.lottery.card.CardUseGates;
import net.minecraft.server.level.ServerPlayer;

/**
 * Spectator / rest-area / dead gate for lottery economy actions that must stay
 * available to living in-match players (mail claim, coin-to-draw, loot roll, open).
 *
 * <p><b>审核 B-01</b>：休息区判定不再越层 import 核心实现层的
 * {@code game.sre.EliminatedRestAreaService}，而是经
 * {@link RestAreaStateBridge} 调用核心<b>公开层</b>的
 * {@code api.MatchRestStateApi}。旧写法把 {@code NoClassDefFoundError} 吞进
 * {@code catch (Throwable ignored)} 并当成「没在休息」，使门禁静默 fail-open；
 * 新桥接在核心能力缺失时<b>保守判定为休息中</b>并打出 ERROR 日志。
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
        // 审核 B-01：经公开层桥接查询；桥接自身保证 fail-closed，这里不需要再 catch。
        boolean resting = RestAreaStateBridge.isResting(player);
        boolean dead = false;
        try {
            dead = !player.isAlive();
        } catch (Throwable ignored) {
        }
        return CardUseGates.spectatorRestOrDead(spectator, resting, dead);
    }
}
