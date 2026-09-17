package com.habitrain.lottery.mail;

import com.habitrain.lottery.network.MailComposeC2SPayload;
import com.habitrain.lottery.network.MailComposeC2SPayload.RewardEntry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SelfSelectMailTest {
    @Test void mixedCardMailRoundTripsWithoutConvertingCardKinds() {
        var rewards = List.of(MailReward.factionCard("killer", 3), MailReward.selfSelectCard(2));
        assertEquals(rewards, MailCommandsCodec.decode(MailCommandsCodec.encode(rewards)));
    }

    @Test void selfSelectMailFollowsFactionCardInGameClaimRestriction() {
        var rewards = List.of(MailReward.selfSelectCard(2));
        assertTrue(MailService.refuseFactionCardInGame(rewards, true));
        assertFalse(MailService.refuseFactionCardInGame(rewards, false));
    }

    @Test void modMenuDraftPreservesBothCardKindsForEveryTargetMode() {
        for (int mode : new int[]{MailComposeC2SPayload.MODE_ONLINE_LIST,
                MailComposeC2SPayload.MODE_OFFLINE_NAME, MailComposeC2SPayload.MODE_ALL_PLAYERS}) {
            var packet = new MailComposeC2SPayload(mode, List.of("Mike"), "system", "cards", "", 0,
                    List.of(new RewardEntry(RewardEntry.FACTION_CARD, 3, "killer"),
                            new RewardEntry(RewardEntry.SELF_SELECT_CARD, 2, "")));
            assertEquals(List.of(MailReward.factionCard("killer", 3), MailReward.selfSelectCard(2)),
                    packet.toDraft().rewards());
        }
    }
}
