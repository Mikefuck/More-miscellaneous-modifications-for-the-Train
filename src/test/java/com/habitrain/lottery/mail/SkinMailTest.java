package com.habitrain.lottery.mail;

import com.habitrain.lottery.api.player.HabiMailApi;
import com.habitrain.lottery.network.MailComposeC2SPayload;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkinMailTest {
    @Test void skinAttachmentsRoundTripAlongsideCurrency() {
        var rewards = List.of(HabiMailApi.skin("gun", "mail_test"), MailReward.coins(30));
        var encoded = MailCommandsCodec.encode(rewards);
        assertEquals("hltmail:v1:SKIN:revolver/mail_test", encoded.getFirst());
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
        assertThrows(IllegalArgumentException.class, () -> MailReward.skin("knife", "coin"));
        assertThrows(IllegalArgumentException.class, () -> MailReward.skin("unknown", "valid"));
    }
}
