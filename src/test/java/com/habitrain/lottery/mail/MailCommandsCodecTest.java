package com.habitrain.lottery.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailCommandsCodecTest {
    @Test
    void roundTripAllKinds() {
        List<MailReward> in = List.of(
                new MailReward(MailReward.Kind.DRAWS, 3, null),
                new MailReward(MailReward.Kind.COINS, 160, null),
                new MailReward(MailReward.Kind.FACTION_CARD, 2, "killer")
        );
        List<String> cmds = MailCommandsCodec.encode(in);
        assertTrue(cmds.stream().allMatch(s -> s.startsWith("hltmail:v1:")));
        List<MailReward> out = MailCommandsCodec.decode(cmds);
        assertEquals(3, out.size());
        assertEquals(MailReward.Kind.DRAWS, out.get(0).kind());
        assertEquals(3, out.get(0).amount());
        assertEquals("killer", out.get(2).factionType());
    }

    @Test
    void ignoresNonPrefixedCommands() {
        List<MailReward> out = MailCommandsCodec.decode(List.of(
                "say hello",
                "hltmail:v1:COINS:10"
        ));
        assertEquals(1, out.size());
        assertEquals(10, out.get(0).amount());
    }
}
