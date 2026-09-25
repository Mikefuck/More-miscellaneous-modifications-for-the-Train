package com.habitrain.lottery.mail;

import com.habitrain.lottery.api.player.HabiMailApi;
import com.habitrain.lottery.network.MailComposeC2SPayload;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkinMailTest {
    @Test void skinAttachmentsRoundTripAlongsideGreenApples() {
        var rewards = List.of(HabiMailApi.skin("gun", "mail_test"), MailReward.greenApples(30));
        var encoded = MailCommandsCodec.encode(rewards);
        assertEquals("hltmail:v1:SKIN:revolver/mail_test", encoded.getFirst());
        assertEquals("hltmail:v1:GREEN_APPLES:30", encoded.get(1));
        assertEquals(rewards, MailCommandsCodec.decode(encoded));
    }
    @Test void composerUsesSameStructuredAttachment() {
        var packet = new MailComposeC2SPayload(0, List.of("Mike"), "system", "skin", "", 0,
                List.of(new MailComposeC2SPayload.RewardEntry(5, 1, "knife/mail_test")));
        assertEquals(List.of(MailReward.skin("knife", "mail_test")), packet.toDraft().rewards());
    }
    @Test void rejectsUnknownTypePathInjectionAndReservedSkin() {
        assertThrows(IllegalArgumentException.class, () -> MailReward.skinEntry("knife/x/y"));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skinEntry("unknown/test"));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skinEntry("knife/default"));
        assertTrue(MailCommandsCodec.decode(List.of("hltmail:v1:SKIN:knife/x/y")).isEmpty());
    }

    /** Audit F-10: the same literal that registers must also be mailable. */
    @Test void normalisesTypeAndIdTheSameWayRegistrationDoes() {
        assertEquals(new MailReward(MailReward.Kind.SKIN, 1, "knife/crystal_blade"),
                MailReward.skin("knife", " Crystal_Blade "));
        assertEquals(new MailReward(MailReward.Kind.SKIN, 1, "revolver/royal"),
                MailReward.skin("gun", "royal"));
        assertEquals(new MailReward(MailReward.Kind.SKIN, 1, "hat/party_hat"),
                MailReward.skin(" habitrain_lottery:hat ", "PARTY_HAT"));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skin("knife", null));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skin("knife", "   "));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skin("knife", "has/slash"));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skin("unknown", "valid"));
    }

    @Test void crateAndKeyAttachmentsUseTheSharedCatalogue() {
        var rewards = List.of(MailReward.crate("woodland", 2), MailReward.key("woodland", 2));
        var encoded = MailCommandsCodec.encode(rewards);
        assertEquals(List.of("hltmail:v1:CRATE:woodland:2", "hltmail:v1:KEY:woodland:2"), encoded);
        assertEquals(rewards, MailCommandsCodec.decode(encoded));
    }

    @Test void composerRecognisesCrateAndKeyKinds() {
        var packet = new MailComposeC2SPayload(0, List.of("Mike"), "system", "crate", "", 0,
                List.of(new MailComposeC2SPayload.RewardEntry(7, 3, "cobalt"),
                        new MailComposeC2SPayload.RewardEntry(8, 1, "cobalt")));
        assertEquals(List.of(MailReward.crate("cobalt", 3), MailReward.key("cobalt", 1)),
                packet.toDraft().rewards());
    }

    @Test void unknownCrateCannotBeMailed() {
        assertThrows(IllegalArgumentException.class, () -> MailReward.crate("missing", 1));
        assertThrows(IllegalArgumentException.class, () -> MailReward.key("missing", 1));
    }
}
