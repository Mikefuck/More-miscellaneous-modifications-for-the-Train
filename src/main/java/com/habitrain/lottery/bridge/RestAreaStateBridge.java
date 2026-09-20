package com.habitrain.lottery.bridge;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * 淘汰休息区状态查询（本模组唯一的收口点）。
 *
 * <h2>审核 B-01：为什么必须有这个类</h2>
 * <p>此前本模组在<b>三处</b>直接越层 import 核心实现层的
 * {@code com.habitrain.core.game.sre.EliminatedRestAreaService}，并且都写成
 * {@code try { ... } catch (Throwable ignored) { resting = false; }}。
 * 于是核心一旦重构或移除该类，{@code NoClassDefFoundError} 会被吞掉，
 * 结果就是<b>门禁静默 fail-open</b>：旁观 / 休息中的玩家可以抽奖、领邮件、用卡。
 * 而这条路径是全模组最热的（每次抽奖、领邮件、金币兑换、开界面、用卡，
 * 以及每个玩家每次名牌渲染都会走）。
 *
 * <h2>现在的做法</h2>
 * <p>只调用核心<b>公开层</b>的 {@code com.habitrain.core.api.MatchRestStateApi}
 * （core 2.0.12 新增，见审核报告 §7.1(4)）。该调用仍通过 {@link Class#forName} 探针完成，
 * 目的是：核心版本过旧（≤ 2.0.11 没有这个类）时能在<b>不触发 NoClassDefFoundError</b>
 * 的前提下探测到，并走明确的降级分支 + ERROR 日志。
 *
 * <h2>降级方向（重要）</h2>
 * <p>探针失败时本类返回 {@code true}（判定为「正在休息」），即<b>fail-closed</b>：
 * 宁可在核心缺失时让抽奖 / 领邮件 / 用卡整体停摆并打出可读的错误日志，
 * 也不要静默放行。这与旧实现的方向<b>完全相反</b>，也是修复 B-01 的核心。
 * 名牌渲染等非门禁路径可以调用 {@link #isAvailable()} 自行选择更温和的降级。
 */
public final class RestAreaStateBridge {

    private static final String CORE_API_CLASS = "com.habitrain.core.api.MatchRestStateApi";

    /** 探针结果：{@code null} 表示未探测过。 */
    private static volatile Boolean coreApiAvailable;
    /** 缓存的核心 API 静态方法；探测成功时非 null。 */
    private static volatile Method isRestingMethod;
    /** fail-closed 的 ERROR 日志只打一次，避免每 tick / 每帧刷屏。 */
    private static volatile boolean degradedLogged;

    private RestAreaStateBridge() {}

    /**
     * 玩家是否正在淘汰休息区。
     *
     * @param player 目标玩家；{@code null} 返回 {@code false}
     * @return {@code true} 表示必须按「旁观、休息或死亡」处理（拒绝抽奖 / 领邮件 / 用卡）
     */
    public static boolean isResting(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Method method = isRestingMethod();
        if (method == null) {
            return true;
        }
        try {
            Object result = method.invoke(null, player);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            // 核心存在但调用失败：同样 fail-closed，并记录一次可读日志。
            logDegraded("MatchRestStateApi.isResting(...) threw", t);
            return true;
        }
    }

    /** 核心是否提供休息区公开 API（core ≥ 2.0.12）。用于非门禁路径选择降级方向。 */
    public static boolean isAvailable() {
        return isRestingMethod() != null;
    }

    private static Method isRestingMethod() {
        Boolean cached = coreApiAvailable;
        if (cached != null) {
            return isRestingMethod;
        }
        synchronized (RestAreaStateBridge.class) {
            if (coreApiAvailable != null) {
                return isRestingMethod;
            }
            Method resolved = null;
            Throwable failure = null;
            try {
                Class<?> api = Class.forName(CORE_API_CLASS, false, RestAreaStateBridge.class.getClassLoader());
                resolved = api.getMethod("isResting", ServerPlayer.class);
                if (!java.lang.reflect.Modifier.isStatic(resolved.getModifiers())) {
                    resolved = null;
                }
            } catch (Throwable t) {
                failure = t;
            }
            if (resolved == null) {
                logDegraded("core does not provide " + CORE_API_CLASS
                        + " (needs habitrain_core >= 2.0.12); rest-area gate now FAIL-CLOSED", failure);
            }
            isRestingMethod = resolved;
            coreApiAvailable = Boolean.TRUE;
            return resolved;
        }
    }

    private static void logDegraded(String message, Throwable cause) {
        if (degradedLogged) {
            return;
        }
        degradedLogged = true;
        if (cause != null) {
            com.habitrain.lottery.HabiLotteryMod.LOGGER.error(
                    "[抽奖] {} — 抽奖/邮件/用卡门禁将按“休息中”保守拒绝，请升级 habitrain_core",
                    message, cause);
        } else {
            com.habitrain.lottery.HabiLotteryMod.LOGGER.error(
                    "[抽奖] {} — 抽奖/邮件/用卡门禁将按“休息中”保守拒绝，请升级 habitrain_core",
                    message);
        }
    }
}
