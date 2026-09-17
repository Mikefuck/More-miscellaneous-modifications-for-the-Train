package com.habitrain.lottery.client;

import com.habitrain.core.api.menu.MenuGateClientApi;
import net.minecraft.client.Minecraft;

/**
 * 客户端 Mod 菜单访问门控：编译期调用 {@link MenuGateClientApi}。
 * 失败时专用服 fail-closed（锁 UI），单机/局域网放行。
 */
public final class MenuAccessBridge {
    private MenuAccessBridge() {}

    /** true=允许访问；false=当前为未授权的访问（专用服务器 + 门控开启 + 未授权）。 */
    public static boolean isScreenAllowed() {
        try {
            return MenuGateClientApi.isScreenAllowed();
        } catch (Throwable t) {
            Minecraft mc = Minecraft.getInstance();
            boolean dedicated = mc != null && mc.getConnection() != null && mc.getSingleplayerServer() == null;
            return !dedicated;
        }
    }

    public static boolean isLocked() {
        return !isScreenAllowed();
    }
}
