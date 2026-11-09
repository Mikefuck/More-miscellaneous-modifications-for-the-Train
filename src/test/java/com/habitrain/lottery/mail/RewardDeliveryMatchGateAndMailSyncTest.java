package com.habitrain.lottery.mail;

import com.google.gson.Gson;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import com.habitrain.lottery.title.LocalTitleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RewardDeliveryMatchGateAndMailSyncTest {
    @TempDir
    Path root;

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        LotteryDayClock.resetAll();
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(root);
        LocalTitleStore.get().reset();
    }

    @AfterEach
    void tearDown() {
        MailService.onServerStopping();
        LotteryDayClock.resetAll();
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        LocalTitleStore.get().reset();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void negativeMailPrechecksEveryBalanceAndNeverLeavesAnUnpayableIntent() {
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, d -> d.greenApples = 100));
        assertTrue(LocalBackpackStore.setSelfSelectCards(player, 1));
        String key = "mail:" + UUID.randomUUID();
        assertFalse(RewardDelivery.deliver(player, key, () -> new RewardDelivery.Plan(
                List.of(MailReward.greenApples(-40), MailReward.selfSelectCard(-2)), List.of(), null)));
        assertEquals(100, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(1, LocalBackpackStore.selfSelectCards(player));
        assertFalse(Files.exists(RewardDelivery.path(player, key)));
    }

    @Test
    void interruptedNegativeMailCannotDebitAgainAfterCompletedReceiptWindowEvictsItsKey() throws Exception {
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, d -> d.greenApples = 100));
        assertTrue(LocalBackpackStore.setSelfSelectCards(player, 4));
        Path blocked = AtomicJsonFiles.bakPath(MetaFeaturePaths.backpackPlayer(player));
        Files.deleteIfExists(blocked);
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("blocked"), "IO failure");
        String key = "mail:" + UUID.randomUUID();
        assertFalse(RewardDelivery.deliver(player, key, () -> new RewardDelivery.Plan(
                List.of(MailReward.greenApples(-40), MailReward.selfSelectCard(-2)), List.of(), null)));
        assertEquals(60, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(4, LocalBackpackStore.selfSelectCards(player));
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, data -> {
            for (int i = 0; i < 600; i++) data.rewardReceipts.add("later:" + i);
        }));
        assertFalse(PlayerLotteryStore.get().getOrLoad(player).rewardReceipts.contains(key));
        assertTrue(PlayerLotteryStore.get().getOrLoad(player).pendingRewardReceipts.contains(key));
        Files.delete(blocked.resolve("blocked"));
        Files.delete(blocked);
        PlayerLotteryStore.get().reset();
        assertTrue(RewardDelivery.deliver(player, key, () -> null));
        assertTrue(RewardDelivery.deliver(player, key, () -> null));
        assertEquals(60, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
        RewardDelivery.cleanupCompleted(player, key);
        assertFalse(PlayerLotteryStore.get().getOrLoad(player).pendingRewardReceipts.contains(key));
    }

    @Test
    void failedPreconditionsDoNotWriteDeadIntentFile() {
        String unknownSkinKey = "test:unknown_skin";
        assertFalse(RewardDelivery.deliver(player, unknownSkinKey,
                () -> new RewardDelivery.Plan(List.of(MailReward.skinEntry("knife/does_not_exist")), List.of(), null)));
        assertFalse(Files.exists(RewardDelivery.path(player, unknownSkinKey)),
                "Failed precondition must not leave a dead pending intent file");

        String negativeApplesKey = "test:negative_apples";
        assertFalse(RewardDelivery.deliver(player, negativeApplesKey,
                () -> new RewardDelivery.Plan(List.of(MailReward.greenApples(-50)), List.of(), null)));
        assertFalse(Files.exists(RewardDelivery.path(player, negativeApplesKey)));
        assertEquals(0, PlayerLotteryStore.get().getGreenApples(player));
    }

    @Test
    void activeMatchDefersCardPlansWhileCompletingNonCardPlansAndDeletingCompletedFiles() {
        Gson gson = new Gson();
        String cardKey = "daily:card_plan";
        RewardDelivery.Plan cardPlan = new RewardDelivery.Plan(
                List.of(MailReward.selfSelectCard(3)), List.of(), null);
        cardPlan.key = cardKey;
        assertTrue(AtomicJsonFiles.writeJson(RewardDelivery.path(player, cardKey), cardPlan, gson, true));

        String appleKey = "daily:apple_plan";
        RewardDelivery.Plan applePlan = new RewardDelivery.Plan(
                List.of(MailReward.greenApples(45)), List.of(), null);
        applePlan.key = appleKey;
        assertTrue(AtomicJsonFiles.writeJson(RewardDelivery.path(player, appleKey), applePlan, gson, true));

        // Simulate recovery during an active match (deferCards = true)
        RewardDelivery.recoverPending(player, true);

        assertEquals(45, PlayerLotteryStore.get().getGreenApples(player));
        assertFalse(Files.exists(RewardDelivery.path(player, appleKey)),
                "Completed non-card intent file must be deleted (I-02)");
        assertFalse(Files.exists(AtomicJsonFiles.bakPath(RewardDelivery.path(player, appleKey))));

        assertEquals(0, LocalBackpackStore.selfSelectCards(player),
                "Card plan must be deferred while match is active");
        assertTrue(Files.isRegularFile(RewardDelivery.path(player, cardKey)),
                "Deferred card plan must stay on disk for lobby recovery");

        // Now recover in lobby (deferCards = false)
        RewardDelivery.recoverPending(player, false);
        assertEquals(3, LocalBackpackStore.selfSelectCards(player));
        assertFalse(Files.exists(RewardDelivery.path(player, cardKey)),
                "Completed card intent file must be deleted after lobby recovery (I-02)");
    }

    @Test
    void recoverPendingMarksMailClaimedInMailboxAndDeletesIntentFiles() {
        var mail = LocalMailboxStore.fromDraft(
                new MailDraft("system", "补偿邮件", "内容", 0L, List.of(MailReward.greenApples(75))));
        assertTrue(LocalMailboxStore.save(player, List.of(mail)));

        String mailKey = "mail:" + mail.id;
        RewardDelivery.Plan plan = new RewardDelivery.Plan(
                MailCommandsCodec.decodeStrict(mail.commands), List.of(), null);
        plan.key = mailKey;
        assertTrue(AtomicJsonFiles.writeJson(RewardDelivery.path(player, mailKey), plan, new Gson(), true));

        RewardDelivery.recoverPending(player);

        assertEquals(75, PlayerLotteryStore.get().getGreenApples(player));
        var reloadedMails = LocalMailboxStore.load(player);
        assertEquals(1, reloadedMails.size());
        assertTrue(reloadedMails.getFirst().claimed, "recoverPending must mark mail:* as claimed in LocalMailboxStore");
        assertTrue(reloadedMails.getFirst().read);
        assertFalse(Files.exists(RewardDelivery.path(player, mailKey)),
                "Completed mail:* intent must be deleted once mailbox claimed flag is persisted");
        assertFalse(Files.exists(AtomicJsonFiles.bakPath(RewardDelivery.path(player, mailKey))));
    }

    @Test
    void lazyMailboxPruningRemovesLongExpiredClaimedOrEmptyMailsButKeepsUnclaimedRewards() throws Exception {
        long t0 = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
        LotteryDayClock.setTestClock(Clock.fixed(Instant.ofEpochMilli(t0), ZoneOffset.UTC));

        var oldClaimed = LocalMailboxStore.fromDraft(
                new MailDraft("sys", "old-claimed", "", t0 + 1_000L, List.of(MailReward.greenApples(10))));
        oldClaimed.claimed = true;

        var oldNoReward = LocalMailboxStore.fromDraft(
                new MailDraft("sys", "old-notice", "", t0 + 1_000L, List.of()));

        var oldUnclaimedReward = LocalMailboxStore.fromDraft(
                new MailDraft("sys", "old-unclaimed-reward", "", t0 + 1_000L, List.of(MailReward.greenApples(50))));

        var recentExpiredClaimed = LocalMailboxStore.fromDraft(
                new MailDraft("sys", "recent-claimed", "", t0 + 5L * 24 * 3600 * 1000L, List.of()));
        recentExpiredClaimed.claimed = true;

        List<LocalMailboxStore.MailJson> all = new ArrayList<>(
                List.of(oldClaimed, oldNoReward, oldUnclaimedReward, recentExpiredClaimed));

        // Advance clock by 8 days (> 7 days past t0 + 1_000L, but only 3 days past recentExpiredClaimed)
        long t8d = t0 + 8L * 24 * 3600 * 1000L;
        LotteryDayClock.setTestClock(Clock.fixed(Instant.ofEpochMilli(t8d), ZoneOffset.UTC));

        assertTrue(LocalMailboxStore.save(player, all));
        var loaded = LocalMailboxStore.load(player);
        List<String> titles = loaded.stream().map(m -> m.title).toList();
        assertEquals(List.of("old-unclaimed-reward", "recent-claimed"), titles);

        // Also verify backup recovery healing on mailbox
        Path mailPath = MetaFeaturePaths.mailPlayer(player);
        assertTrue(LocalMailboxStore.save(player, loaded)); // creates .bak
        Files.writeString(mailPath, "{corrupt-primary", StandardCharsets.UTF_8);
        var fromBak = LocalMailboxStore.loadWithStatus(player);
        assertTrue(fromBak.usedBackup());
        assertTrue(LocalMailboxStore.save(player, fromBak.mails()));
        assertTrue(LocalMailboxStore.loadWithStatus(player).ok());
    }
}
