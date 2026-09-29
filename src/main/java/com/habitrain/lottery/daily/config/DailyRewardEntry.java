package com.habitrain.lottery.daily.config;

import java.util.Locale;

/**
 * One reward line of a daily task.
 *
 * <p>Crate and key rewards reference the live crate catalogue by id, so crates an OP adds later
 * are immediately selectable; {@link #RANDOM} picks one published, enabled crate at claim time.</p>
 */
public final class DailyRewardEntry {
    public static final String GREEN_APPLES = "green_apples";
    public static final String CARD = "card";
    public static final String SELF_SELECT = "self_select";
    public static final String LIMIT_BREAK = "limit_break";
    public static final String SKIN = "skin";
    public static final String CRATE = "crate";
    public static final String KEY = "key";
    public static final String TITLE = "title";
    /** Crate/key id meaning "any enabled crate, chosen on claim". */
    public static final String RANDOM = "*";
    public static final String[] KINDS = {GREEN_APPLES, CARD, SELF_SELECT, LIMIT_BREAK, SKIN, CRATE, KEY, TITLE};
    public static final String[] CARD_TYPES = {"civilian", "killer", "neutral", "neutral_for_killer"};

    public String kind = GREEN_APPLES;
    /** Card type, skin {@code type/id}, crate id (or {@link #RANDOM}) or title text; unused for currency. */
    public String id = "";
    public int amount = 1;

    public DailyRewardEntry() { }

    public DailyRewardEntry(String kind, String id, int amount) {
        this.kind = kind;
        this.id = id;
        this.amount = amount;
    }

    public DailyRewardEntry copy() {
        return new DailyRewardEntry(kind, id, amount);
    }

    public static boolean isKind(String kind) {
        if (kind == null) return false;
        for (String k : KINDS) if (k.equals(kind)) return true;
        return false;
    }

    /** Kinds that are single unlocks rather than stackable balances. */
    public static boolean isUnique(String kind) {
        return SKIN.equals(kind) || TITLE.equals(kind);
    }

    /** Kinds that must carry an {@link #id}. */
    public static boolean needsId(String kind) {
        return CARD.equals(kind) || SKIN.equals(kind) || CRATE.equals(kind) || KEY.equals(kind) || TITLE.equals(kind);
    }

    public static boolean isCardType(String id) {
        if (id == null) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        for (String type : CARD_TYPES) if (type.equals(lower)) return true;
        return false;
    }
}
