package com.habitrain.lottery.daily.config;

import io.wifi.starrailexpress.api.SRERole;

/** Maps a live upstream role onto the {@link DailyFactions} vocabulary. */
public final class DailyRoleFacts {
    private DailyRoleFacts() { }

    public static String faction(SRERole role) {
        if (role == null) return null;
        try {
            if (role.isNeutralForKiller()) return DailyFactions.NEUTRAL_FOR_KILLER;
            if (role.isNeutrals()) return DailyFactions.NEUTRAL;
            if (role.isVigilanteTeam()) return DailyFactions.VIGILANTE;
            if (role.isKillerTeam() || role.canUseKiller() || role.isMafiaTeam()) return DailyFactions.KILLER;
            return DailyFactions.CIVILIAN;
        } catch (RuntimeException | LinkageError unexpected) {
            return DailyFactions.CIVILIAN;
        }
    }

    public static String id(SRERole role) {
        return role == null || role.identifier() == null ? null : role.identifier().toString();
    }
}
