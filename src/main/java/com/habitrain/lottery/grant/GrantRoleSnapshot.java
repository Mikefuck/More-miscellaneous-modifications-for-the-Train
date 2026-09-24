package com.habitrain.lottery.grant;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import io.wifi.starrailexpress.api.SRERole;

import java.util.Optional;

/**
 * Ended-round catalog lookup for grants.
 *
 * <p>{@link EffectiveRole#profile()} is the frozen authority. {@link EffectiveRole#role()}
 * is a live handle and may be null on archives — grants must not read mutable
 * SRERole flags from it.
 */
public final class GrantRoleSnapshot {
    private GrantRoleSnapshot() {
    }

    /**
     * Assigned match-role identity — <b>identity / no-op pass-through（审核 B-21）</b>。
     *
     * <p><b>本方法不做任何 remap：方法体就是 {@code return raw;}。</b>
     * 旧 javadoc 声称它「解析成当前生效角色」，与实际行为不符（审核 B-21）；
     * 这里把文档改成事实：它只是一个恒等透传，保留公开签名是为了
     * {@link GrantEventHooks} 等调用点稳定，而不是因为这里存在解析逻辑。
     * <b>不要</b>因为文档而把它改成真的去查 catalog——发奖链路依赖它是恒等且无副作用的。</p>
     *
     * <p><b>为什么透传 {@code raw} 在发奖链路里是安全的：</b>调用方
     * （{@code GrantEventHooks.classifyWinFaction}）只把返回值用作
     * <b>catalog 查询键与兜底 flags 来源</b>：阵营归属优先走冻结的
     * {@link EffectiveRoleProfile}（{@link #endedProfile(SRERole)}，取自
     * {@code RoleCatalogApi.lastEndedSnapshot()}），并且明确<b>不</b>从 live handle 读
     * {@code isKiller}/{@code isKillerTeam}（见 {@link WinFactionRules#fromProfile}），
     * 因此 overlay 之后的变更不会经由这个句柄影响发奖结果。</p>
     *
     * <p><b>需要 live handle / 生效角色 / 可见性的消费方不要在这里加逻辑</b>，
     * 请走 v2 {@code RoleCatalogApi}（快照与解析）与 {@code RoleVisibilityApi}
     * （{@code com.habitrain.core.api.role.v2}）。真正读 live handle 的例子是客户端
     * {@code client/gui/WarehouseRole#resolveRoleDisplayName}（审核提到的
     * {@code WarehouseRole}），那是纯 UI 显示路径，与发奖无关。</p>
     */
    public static SRERole remap(SRERole raw) {
        return raw;
    }

    /**
     * Prefer {@link RoleCatalogApi#lastEndedSnapshot()}, then
     * {@link RoleCatalogApi#currentSnapshot()}. Does not call
     * {@code RoleCatalogApi.resolve(raw)} (that can be the next-lobby overlay).
     */
    static Optional<RoleSnapshot> endedSnapshot() {
        try {
            RoleCatalogApi api = RoleCatalogApi.instance();
            Optional<RoleSnapshot> snap = api.lastEndedSnapshot();
            if (snap == null || snap.isEmpty()) {
                snap = api.currentSnapshot();
            }
            return snap == null ? Optional.empty() : snap;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    static Optional<EffectiveRole> find(RoleSnapshot snapshot, SRERole raw) {
        if (raw == null || raw.identifier() == null) {
            return Optional.empty();
        }
        return find(snapshot, RoleKey.of(raw.identifier()));
    }

    static Optional<EffectiveRole> find(RoleSnapshot snapshot, RoleKey key) {
        if (snapshot == null || key == null) {
            return Optional.empty();
        }
        try {
            Optional<EffectiveRole> found = snapshot.find(key);
            return found == null ? Optional.empty() : found;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    static Optional<EffectiveRole> endedEffective(SRERole raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return endedSnapshot().flatMap(s -> find(s, raw));
    }

    static Optional<EffectiveRoleProfile> endedProfile(SRERole raw) {
        return endedEffective(raw).map(EffectiveRole::profile);
    }
}
