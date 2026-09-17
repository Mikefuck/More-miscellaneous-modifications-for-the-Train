package com.habitrain.lottery.grant;

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
     */
    public static GrantEventHooks.WinFaction fromBlackoutName(String factionName, boolean killerWon) {
        if (factionName == null || factionName.isBlank()) {
            return null;
        }
        return switch (factionName) {
            case "BAD" -> GrantEventHooks.WinFaction.KILLER;
            case "SIN_KILLER_SHARE" -> killerWon
                    ? GrantEventHooks.WinFaction.KILLER
                    : GrantEventHooks.WinFaction.NEUTRAL;
            case "SIN_INDEPENDENT" -> GrantEventHooks.WinFaction.NEUTRAL;
            case "GOOD" -> GrantEventHooks.WinFaction.PASSENGER;
            default -> null;
        };
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
