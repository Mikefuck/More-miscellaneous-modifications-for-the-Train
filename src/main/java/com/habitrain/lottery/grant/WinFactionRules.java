package com.habitrain.lottery.grant;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;

/**
 * Pure win-faction mapping used by {@link GrantEventHooks} (no Minecraft types).
 */
public final class WinFactionRules {
    private WinFactionRules() {
    }

    /**
     * Blackout end-faction → grant bucket.
     * BAD → killer; SIN_KILLER_SHARE + killer win → killer; independent → neutral; GOOD → passenger.
     *
     * <p><b>审核 B-25：本方法不自己维护阵营名表，直接委托核心的
     * {@link MatchWinFaction#fromBlackoutName(String, boolean)}。</b>
     * 旧实现手抄了一张 {@code switch} 表，于是丢掉了核心的
     * {@code trim() + toUpperCase(Locale.ROOT)} 归一化：{@code " bad "} / {@code "good"}
     * 会被抽奖侧判成「未知阵营」（返回 {@code null}，玩家拿不到胜利奖），
     * 而核心侧判成 {@code KILLER}/{@code PASSENGER}。归一化只能有一处，留在 core。</p>
     *
     * @param factionName 结算快照里的停电局阵营名（大小写/首尾空白由核心归一化）
     * @param killerWon   本局杀手方是否获胜（只影响 {@code SIN_KILLER_SHARE}）
     * @return 发奖桶；未知 / {@code null} / 空白阵营名返回 {@code null}（与核心一致，调用方必须判空）
     */
    public static GrantEventHooks.WinFaction fromBlackoutName(String factionName, boolean killerWon) {
        MatchWinFaction faction = MatchWinFaction.fromBlackoutName(factionName, killerWon);
        return faction == null ? null : GrantEventHooks.WinFaction.from(faction);
    }

    /**
     * Spectators / unassigned players have neither a match role nor blackout
     * faction history and must not inherit the global {@code winStatus} bucket.
     */
    public static boolean isUnassignedForWinGrant(boolean hasRole, boolean hasBlackoutFactionHistory) {
        return !hasRole && !hasBlackoutFactionHistory;
    }

    /**
     * Frozen-profile classification. Profile has no {@code isKiller}/{@code isKillerTeam};
     * those live bits are omitted here so overlay mutation cannot retarget the grant.
     */
    public static GrantEventHooks.WinFaction fromProfile(
            EffectiveRoleProfile profile, boolean customWinner) {
        if (profile == null) {
            return null;
        }
        return fromProfile(
                profile.neutral(),
                customWinner,
                profile.neutralForInnocent(),
                profile.neutralForKiller(),
                profile.canUseKiller(),
                profile.mafiaTeam());
    }

    /** Profile-like booleans: killer / killerTeam are never taken from a live handle. */
    public static GrantEventHooks.WinFaction fromProfile(
            boolean neutral,
            boolean customWinner,
            boolean neutralForInnocent,
            boolean neutralForKiller,
            boolean canUseKiller,
            boolean mafiaTeam) {
        return fromRoleFlags(
                neutral,
                customWinner,
                neutralForInnocent,
                neutralForKiller,
                false,
                false,
                canUseKiller,
                mafiaTeam);
    }

    /** Murder / REPLACE classification from role flags (catalog-effective or raw). */
    public static GrantEventHooks.WinFaction fromRoleFlags(
            boolean neutrals,
            boolean customWinner,
            boolean neutralForInnocent,
            boolean neutralForKiller,
            boolean killer,
            boolean killerTeam,
            boolean canUseKiller,
            boolean mafiaTeam) {
        if (neutrals
                || customWinner
                || (neutralForInnocent && neutralForKiller && !killerTeam && !canUseKiller)) {
            return GrantEventHooks.WinFaction.NEUTRAL;
        }
        if (killer || killerTeam || canUseKiller || mafiaTeam) {
            return GrantEventHooks.WinFaction.KILLER;
        }
        return GrantEventHooks.WinFaction.PASSENGER;
    }
}
