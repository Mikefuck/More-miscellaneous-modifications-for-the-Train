package com.habitrain.lottery.meta;

import com.habitrain.core.api.menu.MenuGateApi;
import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端 Mod 菜单门控：编译期调用 {@link MenuGateApi}。
 *
 * <h2>审核 N-01：能力缺失必须收紧，不能放行</h2>
 * <p>旧实现是
 * <pre>{@code
 * try { return MenuGateApi.isBlocked(player, server); }
 * catch (Throwable t) { return server != null && server.isDedicatedServer(); }
 * }</pre>
 * 这段代码的 {@code catch} 分支<b>永远进不去</b>：核心的
 * {@code CoreSpi.NOOP_MENU_GATE}（{@code api/spi/MenuGateBridge} 的全 {@code default} 实现）
 * 让 {@code isBlocked} 返回 {@code false} 而<b>不抛异常</b>。于是「核心存在但门控桥接未装配」
 * 时，专用服务器上的门控<b>静默失效</b>，10 处管理写路径只剩 OP 判定。
 *
 * <p>现在在调用前显式检查装配探针 {@link CoreSpi#isMenuGateInstalled()}：
 * <ul>
 *   <li>非专用服务器 → 不阻断（沿用核心语义）；</li>
 *   <li>专用服务器 + 桥接未装配 → <b>阻断</b>（能力缺失即视为已阻断，fail-closed）；</li>
 *   <li>装配正常 → 按核心判定。</li>
 * </ul>
 *
 * <p>探针查询本身也包了 {@code try/catch}：{@code api/spi} 的桥接查询约定要求如此
 * （审核 A-18，避免 {@code AbstractMethodError} 打断网络线程），失败时同样收紧。
 */
public final class MenuGateServerBridge {
    private MenuGateServerBridge() {}

    /** 门控启用且该玩家未授权时返回 true（拒绝配置写入）。 */
    public static boolean isBlocked(ServerPlayer player, MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) {
            // 非专用服没有「未授权」概念，核心语义即放行。
            return false;
        }
        if (!menuGateInstalled()) {
            return true;
        }
        try {
            return MenuGateApi.isBlocked(player, server);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 核心门控桥接是否已装配。
     *
     * <p>这是「核心能力是否可用」的唯一可信来源：核心的
     * {@code CoreSpi.install*} 在装配失败时<b>不会</b>置位（审核 A-01），
     * 因此本探针不会谎报。
     */
    public static boolean menuGateInstalled() {
        try {
            return CoreSpi.isMenuGateInstalled();
        } catch (Throwable t) {
            return false;
        }
    }
}
