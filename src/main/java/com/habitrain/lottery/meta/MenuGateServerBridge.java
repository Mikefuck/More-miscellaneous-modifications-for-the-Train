package com.habitrain.lottery.meta;

import com.habitrain.core.api.menu.MenuGateApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 Mod 菜单门控：编译期调用 {@link MenuGateApi}。
 */
public final class MenuGateServerBridge {
    private MenuGateServerBridge() {}

    /** 门控启用且该玩家未授权时返回 true（拒绝配置写入）。 */
    public static boolean isBlocked(ServerPlayer player, MinecraftServer server) {
        try {
            return MenuGateApi.isBlocked(player, server);
        } catch (Throwable t) {
            return server != null && server.isDedicatedServer();
        }
    }
}
