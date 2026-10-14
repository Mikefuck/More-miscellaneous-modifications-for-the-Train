package com.habitrain.lottery.mail;

import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.TitlePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RewardDeliveryTest {
    @TempDir Path root;
    private final UUID player = UUID.randomUUID();
    private final String key = "daily:test:100";

    @BeforeEach void setup() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(root);
        LocalTitleStore.get().reset();
    }

    @AfterEach void cleanup() {
        LocalTitleStore.get().reset();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test void playerCommitSurvivesBackpackFailureAndRestartWithoutSecondAppleGrant() throws Exception {
        assertTrue(LocalBackpackStore.save(player, LocalBackpackStore.defaultCards()));
        Path blocker = blockBackup(MetaFeaturePaths.backpackPlayer(player));
        var rewards = List.of(MailReward.greenApples(160), MailReward.selfSelectCard(2));
        assertFalse(RewardDelivery.deliver(player, key, () -> plan(rewards)));
        assertEquals(160, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(0, LocalBackpackStore.selfSelectCards(player));
        PlayerLotteryStore.get().reset();
        unblock(blocker);
        assertTrue(RewardDelivery.deliver(player, key, () -> { fail("Retry must use saved intent"); return null; }));
        assertEquals(160, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
    }

    @Test void finalIntentFailureDoesNotRegrantSpentAssetsOrRevokedTitle() throws Exception {
        var rewards = List.of(MailReward.greenApples(160), MailReward.selfSelectCard(2), MailReward.limitBreakCard(3));
        saveIntent(new RewardDelivery.Plan(rewards, List.of("reward-title"), null));
        Path blocker = blockBackup(RewardDelivery.path(player, key));
        assertFalse(RewardDelivery.deliver(player, key, () -> null));
        assertEquals(160, PlayerLotteryStore.get().getGreenApples(player));
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, data -> data.greenApples -= 100));
        assertTrue(LocalBackpackStore.addSelfSelectCards(player, -1));
        assertTrue(LocalTitleStore.get().revoke(player, "reward-title"));
        PlayerLotteryStore.get().reset();
        LocalTitleStore.get().reset();
        unblock(blocker);
        assertTrue(RewardDelivery.deliver(player, key, () -> null));
        assertTrue(RewardDelivery.deliver(player, key, () -> null));
        assertEquals(60, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(1, LocalBackpackStore.selfSelectCards(player));
        assertEquals(3, LocalBackpackStore.limitBreakCards(player));
        assertFalse(LocalTitleStore.get().loadPlayer(player).owned.contains("reward-title"));
    }

    @Test void titleFailureRetainsIntentAndLoginRecoveryDoesNotNeedOldDailyConfig() throws Exception {
        assertTrue(LocalTitleStore.get().grant(player, "existing"));
        Path blocker = blockBackup(TitlePaths.playerFile(player));
        assertFalse(RewardDelivery.deliver(player, key,
                () -> new RewardDelivery.Plan(List.of(MailReward.greenApples(10)), List.of("new"), null)));
        assertEquals(List.of("existing"), LocalTitleStore.get().loadPlayer(player).owned);
        assertFalse(RewardDelivery.deliver(player, key, () -> null));
        PlayerLotteryStore.get().reset();
        LocalTitleStore.get().reset();
        unblock(blocker);
        RewardDelivery.recoverPending(player);
        assertEquals(10, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(List.of("existing", "new"), LocalTitleStore.get().loadPlayer(player).owned);
        assertTrue(RewardDelivery.deliver(player, key, () -> null));
    }

    @Test void stableMailIdDeduplicatesAfterClaimAndFinalIntentFailure() throws Exception {
        var letter = new MailDraft("test", "daily", "reward", 0, List.of(MailReward.greenApples(160)));
        saveIntent(new RewardDelivery.Plan(letter.rewards(), List.of(), letter));
        Path blocker = blockBackup(RewardDelivery.path(player, key));
        assertFalse(RewardDelivery.deliver(player, key, () -> null));
        var mails = LocalMailboxStore.load(player);
        assertEquals(1, mails.size());
        mails.getFirst().claimed = true;
        assertTrue(LocalMailboxStore.save(player, mails));
        unblock(blocker);
        PlayerLotteryStore.get().reset();
        assertTrue(RewardDelivery.deliver(player, key, () -> null));
        assertEquals(1, LocalMailboxStore.load(player).size());
        assertTrue(LocalMailboxStore.load(player).getFirst().claimed);
    }

    @Test void deletedCrateAttachmentIsKeptAndPreventsPartialPlayerCommit() {
        var rewards = MailCommandsCodec.decodeStrict(List.of("hltmail:v1:GREEN_APPLES:12", "hltmail:v1:CRATE:deleted_crate:1"));
        assertEquals(2, rewards.size());
        assertEquals("deleted_crate", rewards.get(1).factionType());
        assertFalse(RewardDelivery.deliver(player, "mail:deleted", () -> plan(rewards)));
        assertEquals(0, PlayerLotteryStore.get().getGreenApples(player));
        assertFalse(PlayerLotteryStore.get().getOrLoad(player).rewardReceipts.contains("mail:deleted"));
    }

    @Test void brokenStructuredAttachmentIsNotSilentlyClaimable() {
        assertThrows(IllegalArgumentException.class,
                () -> MailCommandsCodec.decodeStrict(List.of("hltmail:v1:CRATE:broken")));
        assertTrue(MailCommandsCodec.decodeStrict(List.of("hltmail:v1:COIN:100")).isEmpty());
    }

    @Test void oldInFlightMailRemainsLockedOnRepeatedLoads() {
        var mail = LocalMailboxStore.fromDraft(new MailDraft("system", "old", "", 0, List.of()));
        mail.commands.add(MailService.CLAIMING_PREFIX + mail.id);
        assertTrue(MailService.reconcileClaimMarkers(player, List.of(mail)));
        assertTrue(mail.claimed);
        assertNull(MailService.findClaimable(List.of(mail), mail.id));
        assertFalse(MailService.reconcileClaimMarkers(player, List.of(mail)));
    }

    @Test void deliveredMailFinalSaveFailureRetriesThroughReceipt() throws Exception {
        var mail = LocalMailboxStore.fromDraft(new MailDraft("system", "reward", "", 0, List.of(MailReward.greenApples(20))));
        var mails = List.of(mail);
        assertTrue(LocalMailboxStore.save(player, mails));
        Path blocker = blockBackup(MetaFeaturePaths.mailPlayer(player));
        assertTrue(RewardDelivery.deliver(player, "mail:" + mail.id,
                () -> plan(MailCommandsCodec.decodeStrict(mail.commands))));
        assertFalse(MailService.finishClaim(mail, true, () -> LocalMailboxStore.save(player, mails)));
        assertFalse(LocalMailboxStore.load(player).getFirst().claimed);
        PlayerLotteryStore.get().reset();
        unblock(blocker);
        assertTrue(RewardDelivery.deliver(player, "mail:" + mail.id, () -> null));
        assertTrue(MailService.finishClaim(mail, true, () -> LocalMailboxStore.save(player, mails)));
        assertEquals(20, PlayerLotteryStore.get().getGreenApples(player));
        assertTrue(LocalMailboxStore.load(player).getFirst().claimed);
    }

    private static RewardDelivery.Plan plan(List<MailReward> rewards) {
        return new RewardDelivery.Plan(rewards, List.of(), null);
    }

    private void saveIntent(RewardDelivery.Plan plan) {
        plan.key = key;
        assertTrue(AtomicJsonFiles.writeJson(RewardDelivery.path(player, key), plan, new com.google.gson.Gson(), true));
    }

    private static Path blockBackup(Path file) throws Exception {
        Path directory = AtomicJsonFiles.bakPath(file);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("blocked"), "force backup replacement to fail");
        return directory;
    }

    private static void unblock(Path directory) throws Exception {
        Files.delete(directory.resolve("blocked"));
        Files.delete(directory);
    }
}
