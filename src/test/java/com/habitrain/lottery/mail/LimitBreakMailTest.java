package com.habitrain.lottery.mail;

import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.network.MailComposeC2SPayload;
import com.habitrain.lottery.network.MailComposeC2SPayload.RewardEntry;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LimitBreakMailTest {
    @TempDir Path temp;

    @Test void mixedAttachmentsRoundTripForEveryRecipientMode() {
        var expected = List.of(MailReward.draws(1), MailReward.coins(10),
                MailReward.factionCard("killer", 3), MailReward.selfSelectCard(2),
                MailReward.limitBreakCard(4));
        for (int mode : new int[]{MailComposeC2SPayload.MODE_ONLINE_LIST,
                MailComposeC2SPayload.MODE_OFFLINE_NAME, MailComposeC2SPayload.MODE_ALL_PLAYERS}) {
            var packet = new MailComposeC2SPayload(mode, List.of("Mike"), "system", "cards", "", 0,
                    List.of(new RewardEntry(RewardEntry.DRAWS, 1, ""),
                            new RewardEntry(RewardEntry.COINS, 10, ""),
                            new RewardEntry(RewardEntry.FACTION_CARD, 3, "killer"),
                            new RewardEntry(RewardEntry.SELF_SELECT_CARD, 2, ""),
                            new RewardEntry(RewardEntry.LIMIT_BREAK_CARD, 4, "")));
            assertEquals(expected, packet.toDraft().rewards());
            assertEquals(expected, MailCommandsCodec.decode(MailCommandsCodec.encode(packet.toDraft().rewards())));
        }
    }

    @Test void limitBreakAttachmentsFollowCardClaimRestriction() {
        var rewards = List.of(MailReward.limitBreakCard(2));
        assertTrue(MailService.refuseFactionCardInGame(rewards, true));
        assertFalse(MailService.refuseFactionCardInGame(rewards, false));
        assertFalse(MailService.refuseFactionCardInGame(List.of(MailReward.limitBreakCard(0)), true));
    }

    @Test void failedMixedRewardCanRestoreLimitBreakBalanceBeforeReopeningClaim() {
        WorldLotteryPaths.initForTests(temp);
        try {
            UUID id = UUID.randomUUID();
            assertTrue(LocalBackpackStore.setLimitBreakCards(id, 3));
            assertTrue(LocalBackpackStore.setSelfSelectCards(id, 7));
            int before = LocalBackpackStore.limitBreakCards(id);
            var result = MailClaimTransaction.run(() -> true, () -> {
                assertTrue(LocalBackpackStore.addLimitBreakCards(id, 4));
                throw new IllegalStateException("Later attachment failed");
            }, () -> LocalBackpackStore.setLimitBreakCards(id, before), () -> {
                assertEquals(3, LocalBackpackStore.limitBreakCards(id));
                return true;
            });
            assertEquals(MailClaimTransaction.Result.ROLLED_BACK, result);
            assertEquals(3, LocalBackpackStore.limitBreakCards(id));
            assertEquals(7, LocalBackpackStore.selfSelectCards(id));
        } finally {
            WorldLotteryPaths.clear();
        }
    }
}
