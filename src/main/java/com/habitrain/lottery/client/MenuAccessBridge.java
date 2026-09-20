package com.habitrain.lottery.client;

import com.habitrain.core.api.client.menu.MenuGateClientApi;

/**
 * 客户端 Mod 菜单访问门控：编译期调用 {@link MenuGateClientApi}。
 *
 * <p>2.0.11 起 core 把 {@code MenuGateClientApi} 从 {@code api.menu} 迁到
 * {@code api.client.menu}（审核 B23：{@code @Environment(CLIENT)} 对非入口点没有加载期
 * 强制力，客户端专用 API 必须放在 {@code api.client.*} 下）。</p>
 *
 * <h2>审核 A-11 / N-01：客户端门控不是安全边界</h2>
 * <p>核心客户端桥接的默认值是 {@code isScreenAllowed() == true}（fail-open），
 * 原因是「状态未知」时锁死配置页比放行更糟（局域网客人会看到一片锁死的页面）。
 * 因此本桥接<b>不</b>把客户端判定当成安全边界，只做 UX：
 * 真正的门禁由服务端 {@code MenuGateServerBridge} 判定，且那里已经改为
 * 「核心能力缺失即收紧」。</p>
 *
 * <p>唯一需要收紧的情形是：客户端已知「服务端自报为专用服务器」
 * （{@code isServerDedicated()} 由服务端随 {@code MenuGatePayload} 下发）却读不到授权状态。
 * 此时锁屏是正确方向——客人不该在局域网房主的整合服上被误锁，而真正的专用服应当锁。</p>
 */
public final class MenuAccessBridge {
    private MenuAccessBridge() {}

    /** true=允许访问；false=当前为未授权的访问（专用服务器 + 门控开启 + 未授权）。 */
    public static boolean isScreenAllowed() {
        try {
            return MenuGateClientApi.isScreenAllowed();
        } catch (Throwable t) {
            // core 客户端状态不可用：只有在「已知服务端是专用服」时才收紧。
            // 审核 A-11：客户端不能自行推断专用服（B19 的误锁屏根因），
            // 因此这里的降级方向取决于服务端已经下发的标志，拿不到就放行。
            return !serverDedicated();
        }
    }

    /**
     * 服务端是否自报为专用服务器。读取失败按 {@code false} 处理（客户端不做自身推断）。
     */
    public static boolean serverDedicated() {
        try {
            return MenuGateClientApi.isServerDedicated();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isLocked() {
        return !isScreenAllowed();
    }
}
