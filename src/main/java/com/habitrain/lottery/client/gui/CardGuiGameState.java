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
 */
public final class CardGuiGameState {
    private CardGuiGameState() {
    }

    /** 游戏是否已经离开大厅状态（STARTING / INITIATING / ACTIVE / STOPPING）。 */
    public static boolean gameActiveOrStarting() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return MatchStateApi.hasLeftLobby(mc == null ? null : mc.level);
        } catch (Throwable t) {
            return true;
        }
    }
}
