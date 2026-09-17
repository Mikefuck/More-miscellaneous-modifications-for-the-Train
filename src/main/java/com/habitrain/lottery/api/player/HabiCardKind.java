package com.habitrain.lottery.api.player;

import java.util.List;
import java.util.Locale;

/**
 * The six card balances owned by one player.
 *
 * <p>The first four map onto SRE {@code FactionCardType} via their
 * {@code questKey}; the last two are virtual cards that only exist in this
 * mod's local backpack store.</p>
 */
public enum HabiCardKind {
    /** 平民阵营卡. */
    CIVILIAN("civilian", true),
    /** 中立阵营卡. */
    NEUTRAL("neutral", true),
    /** 杀手方中立阵营卡. */
    NEUTRAL_FOR_KILLER("neutral_for_killer", true),
    /** 杀手阵营卡. */
    KILLER("killer", true),
    /** 自选卡（虚拟卡，仅保存在本模组存档）. */
    SELF_SELECT("self_select", false),
    /** 突破上限卡（虚拟卡，仅保存在本模组存档）. */
    LIMIT_BREAK("limit_break", false);

    /** Stable display / JSON ordering used by the admin UI and snapshots. */
    private static final List<HabiCardKind> ORDERED = List.of(
            CIVILIAN, NEUTRAL, NEUTRAL_FOR_KILLER, KILLER, SELF_SELECT, LIMIT_BREAK);

    private final String id;
    private final boolean factionCard;

    HabiCardKind(String id, boolean factionCard) {
        this.id = id;
        this.factionCard = factionCard;
    }

    /** Lowercase storage key; matches SRE {@code questKey} for faction cards. */
    public String id() {
        return id;
    }

    /** True for the four SRE {@code FactionCardType} balances. */
    public boolean isFactionCard() {
        return factionCard;
    }

    /** True for the two virtual cards stored only in this mod's backpack JSON. */
    public boolean isVirtualCard() {
        return !factionCard;
    }

    /** Stable ordered list of every kind. */
    public static List<HabiCardKind> ordered() {
        return ORDERED;
    }

    /** Lenient parser accepting the storage key or the enum name; {@code null} when unknown. */
    public static HabiCardKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (HabiCardKind kind : ORDERED) {
            if (kind.id.equals(key)) {
                return kind;
            }
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
