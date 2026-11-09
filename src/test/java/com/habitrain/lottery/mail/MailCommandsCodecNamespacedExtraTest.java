package com.habitrain.lottery.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for {@code I-10}: {@link MailCommandsCodec} encodes canonical
 * {@code hltmail:v1:<kind>:<amount>[:<extra>]} tokens and splits with {@code limit = 5} so
 * namespaced IDs containing colons (e.g. {@code modid:entry_id}) survive round-trips intact,
 * while legacy {@code v1.1.63} mailbox tokens still decode cleanly.
 */
class MailCommandsCodecNamespacedExtraTest {

    @Test
    void roundTripsNamespacedIdsContainingColonsInExtraSegment() {
        List<MailReward> rewards = List.of(
                new MailReward(MailReward.Kind.CRATE, 3, "habitrain_lottery:season_crate"),
                new MailReward(MailReward.Kind.KEY, 2, "other_mod:gold:key_v2"),
                MailReward.factionCard("habitrain_core:detective_plus", 4),
                MailReward.skin("knife", "crimson_blade"),
                MailReward.greenApples(160),
                MailReward.selfSelectCard(2),
                MailReward.limitBreakCard(1)
        );

        List<String> encoded = MailCommandsCodec.encode(rewards);
        assertEquals(List.of(
                "hltmail:v1:CRATE:3:habitrain_lottery:season_crate",
                "hltmail:v1:KEY:2:other_mod:gold:key_v2",
                "hltmail:v1:FACTION_CARD:4:habitrain_core:detective_plus",
                "hltmail:v1:SKIN:knife/crimson_blade",
                "hltmail:v1:GREEN_APPLES:160",
                "hltmail:v1:SELF_SELECT_CARD:2",
                "hltmail:v1:LIMIT_BREAK_CARD:1"
        ), encoded);

        List<MailReward> decodedStrict = MailCommandsCodec.decodeStrict(encoded);
        assertEquals(rewards, decodedStrict);
        assertEquals(rewards, MailCommandsCodec.decode(encoded));
    }

    @Test
    void numericCrateAndKeyIdsKeepLegacyOrderIncludingNegativeAmounts() {
        List<MailReward> rewards = List.of(
                new MailReward(MailReward.Kind.CRATE, 2, "2026"),
                new MailReward(MailReward.Kind.KEY, -3, "2"));
        assertEquals(List.of("hltmail:v1:CRATE:2026:2", "hltmail:v1:KEY:2:-3"),
                MailCommandsCodec.encode(rewards));
        assertEquals(rewards, MailCommandsCodec.decodeStrict(MailCommandsCodec.encode(rewards)));
        assertEquals(rewards, MailCommandsCodec.decodeStrict(
                List.of("hltmail:v1:CRATE:2026:2", "hltmail:v1:KEY:2:-3")));
    }

    @Test
    void decodesLegacyV1163SavedMailboxTokensBackwardCompatibly() {
        List<String> legacySavedCommands = List.of(
                "hltmail:v1:CRATE:deleted_crate:1",
                "hltmail:v1:KEY:woodland:5",
                "hltmail:v1:FACTION_CARD:killer:3",
                "hltmail:v1:SKIN:knife/crimson_blade",
                "hltmail:v1:GREEN_APPLES:80"
        );

        List<MailReward> decoded = MailCommandsCodec.decodeStrict(legacySavedCommands);
        assertEquals(List.of(
                new MailReward(MailReward.Kind.CRATE, 1, "deleted_crate"),
                new MailReward(MailReward.Kind.KEY, 5, "woodland"),
                MailReward.factionCard("killer", 3),
                MailReward.skin("knife", "crimson_blade"),
                MailReward.greenApples(80)
        ), decoded);
    }
}
