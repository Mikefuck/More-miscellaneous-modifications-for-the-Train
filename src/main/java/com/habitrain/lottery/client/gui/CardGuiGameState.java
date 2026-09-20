package com.habitrain.lottery.client.gui;

import com.habitrain.core.api.match.MatchStateApi;
import net.minecraft.client.Minecraft;

/**
 * 客户端对局状态守卫：当游戏一旦离开大厅进入开场（STARTING）或更后的状态，
 * 正在选择角色的窗口应当自动关闭，并且此时的选择一律不生效（服务端
 * {@code CardUseService} 也会同步拒绝）。
 *
 * <p>走 {@link MatchStateApi#hasLeftLobby}，不要用 {@code GameMode.isActive}
 * 或仅 ACTIVE 的 {@code SREClient.isGameRunning()}。
 *
 * <h2>审核 B-24：不要每渲染帧查询核心</h2>
 * <p>本方法在 GUI 的 {@code render} 路径上被调用。旧实现每次都直接穿透到核心的
 * {@code MatchStateApi}（内部再走一遍 SPI 桥接 + 异常包装），即<b>每渲染帧一次</b>
 * 跨模组调用。现在把结果缓存约 5 tick（100ms）：对「离开大厅后自动关窗」这个用途
 * 完全够快（人的反应时间远大于 100ms），却把每帧调用降到每 5 tick 一次。
 *
 * <p>缓存只在客户端主线程读写（GUI 渲染与 tick 都在主线程），因此不需要加锁。
 * 维度变化（{@code level} 实例不同）会立即失效缓存，避免把上一局的判定带进新维度。
 */
public final class CardGuiGameState {

    /** 审核 B-24：约 5 tick（100ms）的结果缓存窗口。 */
    private static final long CACHE_TTL_NANOS = 100_000_000L;

    private static long cachedAtNanos = 0L;
    private static boolean cachedValue = false;
    private static Object cachedLevel = null;

    private CardGuiGameState() {
    }

    /** 游戏是否已经离开大厅状态（STARTING / INITIATING / ACTIVE / STOPPING）。 */
    public static boolean gameActiveOrStarting() {
        Minecraft mc = Minecraft.getInstance();
        Object level = mc == null ? null : mc.level;
        long now = System.nanoTime();
        if (level == cachedLevel && now - cachedAtNanos < CACHE_TTL_NANOS) {
            return cachedValue;
        }
        boolean value;
        try {
            value = MatchStateApi.hasLeftLobby(mc == null ? null : mc.level);
        } catch (Throwable t) {
            // 读不到状态时按「已经开始」处理（fail-closed：不让人在开局后继续选角色）。
            value = true;
        }
        cachedLevel = level;
        cachedValue = value;
        cachedAtNanos = now;
        return value;
    }

    /** 测试/退出世界时清空缓存（避免跨会话残留）。 */
    public static void invalidate() {
        cachedLevel = null;
        cachedAtNanos = 0L;
        cachedValue = false;
    }
}
