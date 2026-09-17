package com.habitrain.lottery.mail;

import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MailClaimTxnTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void rewardsFailureDoesNotPersistClaimed() {
        MailJson mail = new MailJson();
        mail.id = "a";
        mail.claimed = false;
        mail.read = false;
        boolean[] persistCalled = {false};
        assertFalse(MailService.finishClaim(mail, false, () -> {
            persistCalled[0] = true;
            return true;
        }));
        assertFalse(persistCalled[0]);
        assertFalse(mail.claimed);
    }

    @Test
    void claimedFlagSaveThenRevertOnDisk() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        MailJson mail = new MailJson();
        mail.id = "rollback";
        mail.title = "coins";
        mail.claimed = false;
        mail.read = false;
        mail.commands = new ArrayList<>(List.of("hltmail:v1:COINS:10"));
        List<MailJson> list = new ArrayList<>();
        list.add(mail);
        assertTrue(LocalMailboxStore.save(id, list));

        MailJson live = LocalMailboxStore.loadWithStatus(id).mails().get(0);
        MailService.markClaimed(live);
        assertTrue(LocalMailboxStore.save(id, List.of(live)));
        assertTrue(LocalMailboxStore.loadWithStatus(id).mails().get(0).claimed);

        MailService.unmarkClaimed(live, false);
        assertTrue(LocalMailboxStore.save(id, List.of(live)));
        assertFalse(LocalMailboxStore.loadWithStatus(id).mails().get(0).claimed);
        assertTrue(Files.isRegularFile(MetaFeaturePaths.mailPlayer(id)));
        String body = Files.readString(MetaFeaturePaths.mailPlayer(id), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"claimed\": false") || body.contains("\"claimed\":false"));
    }

    @Test
    void mixedFactionCardMailRefusedWhileGameActive() {
        List<MailReward> mixed = List.of(
                MailReward.draws(2),
                MailReward.factionCard("killer", 1)
        );
        assertTrue(MailService.containsFactionCard(mixed));
        assertTrue(MailService.refuseFactionCardInGame(mixed, true));
        assertFalse(MailService.refuseFactionCardInGame(mixed, false));
        assertFalse(MailService.refuseFactionCardInGame(List.of(MailReward.coins(60)), true));
    }

    @Test
    void findClaimableSkipsClaimedAndMismatchedIds() {
        MailJson a = new MailJson();
        a.id = "a";
        a.claimed = true;
        MailJson b = new MailJson();
        b.id = "b";
        b.claimed = false;
        assertNull(MailService.findClaimable(List.of(a, b), "a"));
        assertSame(b, MailService.findClaimable(List.of(a, b), "b"));
        assertNull(MailService.findClaimable(List.of(a, b), "nope"));
    }
}
