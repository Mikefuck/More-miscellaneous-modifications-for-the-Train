package com.habitrain.lottery.mail;

import com.habitrain.lottery.crate.CrateCatalog;

/**
 * Structured mail reward applied on claim (encoded into claimCommands).
 */
public record MailReward(Kind kind, int amount, String factionType) {
    public enum Kind {
        GREEN_APPLES,
        FACTION_CARD,
        SELF_SELECT_CARD,
        SKIN,
        LIMIT_BREAK_CARD,
        CRATE,
        KEY
    }

    /**
     * The existing optional key field carries canonical type/id for a skin attachment.
     *
     * <p>Id and type go through the same normalisation the catalogue uses
     * ({@link com.habitrain.lottery.api.skin.SkinDefinition#normalizeSkinId} plus the
     * {@code gun -> revolver} type alias). This factory used to validate the raw
     * literal instead, so a value that registered and unlocked fine — a capitalised id
     * or one with a trailing space — threw only when it was first mailed, inside the
     * extension's own call (audit F-10).</p>
     */
    public static MailReward skin(String type, String id) {
        String canonical = com.habitrain.lottery.storage.SkinTypeKeys.canonical(type);
        if (!com.habitrain.lottery.api.skin.SkinDefinition.SUPPORTED_TYPES.contains(canonical))
            throw new IllegalArgumentException("Invalid skin attachment type: " + type);
        String normalizedId;
        try {
            normalizedId = com.habitrain.lottery.api.skin.SkinDefinition.normalizeSkinId(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid skin attachment: " + e.getMessage(), e);
        }
        if (normalizedId == null) throw new IllegalArgumentException("Missing skin attachment");
        return new MailReward(Kind.SKIN, 1, canonical + "/" + normalizedId);
    }

    public static MailReward skinEntry(String entry) {
        if (entry == null) throw new IllegalArgumentException("Missing skin attachment");
        String[] parts = entry.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Expected type/id");
        return skin(parts[0], parts[1]);
    }

    public static MailReward greenApples(int amount) {
        return new MailReward(Kind.GREEN_APPLES, amount, null);
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

    /** A crate from the shared crate catalogue. */
    public static MailReward crate(String id, int amount) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        if (entry == null) throw new IllegalArgumentException("Unknown crate: " + id);
        return new MailReward(Kind.CRATE, amount, entry.id());
    }

    /** The key matching a crate from the shared crate catalogue. */
    public static MailReward key(String id, int amount) {
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        if (entry == null) throw new IllegalArgumentException("Unknown crate key: " + id);
        return new MailReward(Kind.KEY, amount, entry.id());
    }

    public static MailReward crateEntry(String entry, int amount) {
        return crate(entry, amount);
    }

    public static MailReward keyEntry(String entry, int amount) {
        return key(entry, amount);
    }
}
