package com.habitrain.lottery.mail;

/**
 * Structured mail reward applied on claim (encoded into claimCommands).
 */
public record MailReward(Kind kind, int amount, String factionType) {
    public enum Kind {
        DRAWS,
        COINS,
        FACTION_CARD,
        SELF_SELECT_CARD,
        LIMIT_BREAK_CARD
    }

    public static MailReward draws(int amount) {
        return new MailReward(Kind.DRAWS, amount, null);
    }

    public static MailReward coins(int amount) {
        return new MailReward(Kind.COINS, amount, null);
    }

    public static MailReward factionCard(String typeKey, int amount) {
        return new MailReward(Kind.FACTION_CARD, amount, typeKey);
    }

    public static MailReward selfSelectCard(int amount) {
        return new MailReward(Kind.SELF_SELECT_CARD, amount, null);
    }

    public static MailReward limitBreakCard(int amount) {
        return new MailReward(Kind.LIMIT_BREAK_CARD, amount, null);
    }
}
