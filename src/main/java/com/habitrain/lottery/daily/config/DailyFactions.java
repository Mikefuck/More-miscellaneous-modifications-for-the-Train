package com.habitrain.lottery.daily.config;

import java.util.List;

/**
 * Faction vocabulary shared by the editor, the validator and the tracker. Kept free of upstream
 * role classes so it can be unit-tested; {@code DailyRoleFacts} maps a live role onto it.
 */
public final class DailyFactions {
    public static final String CIVILIAN = "civilian";
    public static final String VIGILANTE = "vigilante";
    public static final String KILLER = "killer";
    public static final String NEUTRAL = "neutral";
    public static final String NEUTRAL_FOR_KILLER = "neutral_for_killer";
    public static final List<String> ALL = List.of(CIVILIAN, VIGILANTE, KILLER, NEUTRAL, NEUTRAL_FOR_KILLER);

    private DailyFactions() { }

    public static boolean isFaction(String id) {
        return id != null && ALL.contains(id);
    }

    /**
     * The side a faction fights for: passengers (civilian + vigilante), the killer side (killer +
     * neutral-for-killer) and independent neutrals, who are hostile to everyone.
     */
    public static String side(String faction) {
        if (CIVILIAN.equals(faction) || VIGILANTE.equals(faction)) return "passengers";
        if (KILLER.equals(faction) || NEUTRAL_FOR_KILLER.equals(faction)) return "killers";
        return "neutral";
    }

    /** True when a kill of {@code victim} by {@code killer} is against the other side. */
    public static boolean hostile(String killer, String victim) {
        if (killer == null || victim == null) return false;
        String a = side(killer);
        String b = side(victim);
        return "neutral".equals(a) || "neutral".equals(b) || !a.equals(b);
    }
}
