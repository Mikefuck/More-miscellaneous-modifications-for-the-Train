package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.daily.config.DailyTaskTracker;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrateRecoveryTest {
    @TempDir Path root;
    private final UUID uuid = UUID.randomUUID();
    private final Gson gson = new Gson();
    private Field stateField;
    private Object oldState;
    private Method finish;
    private CrateService.PendingOpen pending;

    @BeforeEach void setup() throws Exception {
        WorldLotteryPaths.initForTests(root);
        PlayerLotteryStore.get().reset();
        stateField = CrateService.class.getDeclaredField("state");
        stateField.setAccessible(true);
        oldState = stateField.get(null);
        finish = CrateService.class.getDeclaredMethod("finishPending", CrateService.PendingOpen.class,
                net.minecraft.server.level.ServerPlayer.class);
        finish.setAccessible(true);
        pending = new CrateService.PendingOpen();
        pending.rewardProtocol = 1;
        pending.openId = UUID.randomUUID().toString();
        pending.playerId = uuid.toString();
        pending.crateId = "woodland";
        pending.keyId = CrateService.keyItemId("woodland");
        pending.primaryQualityId = "white";
        pending.rewards = List.of(new CrateService.Reward("card", "self_select", 1));
        pending.cardBefore.put("self_select", 0);
        pending.taskEpochDay = LoginRewardService.todayEpochDayUtc();
        pending.dailyTaskTargets.put("habitrain_lottery:open_test", 1);
        var state = new CrateService.State();
        CrateOutputQuota.resetWindows(state, LocalDate.now(ZoneOffset.UTC));
        pending.weekKey = state.weekKey;
        pending.monthKey = state.monthKey;
        state.pendingOpens.put(pending.openId, pending);
        stateField.set(null, state);
        assertTrue(AtomicJsonFiles.writeJson(WorldLotteryPaths.configFile("crates.json"), state, gson, true));
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> {
            data.systemItems.put(CrateService.crateItemId("woodland"), 1);
            data.systemItems.put(pending.keyId, 1);
        }));
    }

    @AfterEach void cleanup() throws Exception {
        stateField.set(null, oldState);
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test void committedCardsCanBeSpentBeforeFinalStateRecovery() throws Exception {
        Path blocker = blockBackup(WorldLotteryPaths.configFile("crates.json"));
        assertFalse(finish().success());
        assertEquals(1, LocalBackpackStore.selfSelectCards(uuid));
        assertTrue(LocalBackpackStore.addSelfSelectCards(uuid, -1));
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(uuid).dailyTaskProgress.get("habitrain_lottery:open_test"));
        restart();
        unblock(blocker);
        assertTrue(finish().success());
        assertTrue(finish().success());
        assertEquals(0, LocalBackpackStore.selfSelectCards(uuid));
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(uuid).dailyTaskProgress.get("habitrain_lottery:open_test"));
        assertEquals(0, PlayerLotteryStore.get().getOrLoad(uuid).systemItems.getOrDefault(pending.keyId, 0));
    }

    @Test void cardReceiptSurvivesPlayerWriteFailureAndSpending() throws Exception {
        Path blocker = blockBackup(WorldLotteryPaths.playerFile(uuid));
        assertFalse(finish().success());
        assertEquals(1, LocalBackpackStore.selfSelectCards(uuid));
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(uuid).systemItems.get(pending.keyId));
        assertTrue(LocalBackpackStore.addSelfSelectCards(uuid, -1));
        restart();
        unblock(blocker);
        assertTrue(finish().success());
        assertEquals(0, LocalBackpackStore.selfSelectCards(uuid));
        assertEquals(0, PlayerLotteryStore.get().getOrLoad(uuid).systemItems.getOrDefault(pending.keyId, 0));
    }

    @Test void callbackOverflowRestoresDebitAndRetryDoesNotRegrantCards() throws Exception {
        pending.apples = 1;
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> data.greenApples = Integer.MAX_VALUE));
        assertFalse(finish().success());
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(uuid).systemItems.get(pending.keyId));
        assertFalse(PlayerLotteryStore.get().isDirty(uuid));
        assertTrue(LocalBackpackStore.addSelfSelectCards(uuid, -1));
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> data.greenApples = 0));
        assertTrue(finish().success());
        assertEquals(1, PlayerLotteryStore.get().getGreenApples(uuid));
        assertEquals(0, LocalBackpackStore.selfSelectCards(uuid));
    }

    @Test void legacyUncommittedCardIntentDoesNotGuessFromCurrentBalance() throws Exception {
        pending.rewardProtocol = 0;
        assertFalse(finish().success());
        assertEquals(0, LocalBackpackStore.selfSelectCards(uuid));
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(uuid).systemItems.get(pending.keyId));
    }

    @Test void crateGrantPreservesRewardBalancesBeyondTheSetLimit() throws Exception {
        assertTrue(LocalBackpackStore.setSelfSelectCards(uuid, 200_000));
        assertTrue(finish().success());
        assertEquals(200_001, LocalBackpackStore.selfSelectCards(uuid));
        assertTrue(finish().success());
        assertEquals(200_001, LocalBackpackStore.selfSelectCards(uuid));
    }

    @Test void overflowingCardRewardKeepsCrateAndKeyUntilRetrySucceeds() throws Exception {
        assertTrue(LocalBackpackStore.setSelfSelectCards(uuid, Integer.MAX_VALUE));
        assertFalse(finish().success());
        assertEquals(Integer.MAX_VALUE, LocalBackpackStore.selfSelectCards(uuid));
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(uuid).systemItems.get(pending.keyId));
        assertTrue(LocalBackpackStore.addSelfSelectCards(uuid, -1));
        assertTrue(finish().success());
        assertEquals(Integer.MAX_VALUE, LocalBackpackStore.selfSelectCards(uuid));
    }

    @Test void legacyCommittedReceiptSkipsCardBalanceRepair() throws Exception {
        pending.rewardProtocol = 0;
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> {
            data.crateOpenHistory.add(pending.openId);
            data.crateReceipts.put(pending.openId, gson.toJson(pending));
            data.systemItems.clear();
        }));
        assertTrue(finish().success());
        assertEquals(0, LocalBackpackStore.selfSelectCards(uuid));
    }

    @Test void oldDayPendingDoesNotAdvanceTodaysTask() {
        pending.taskEpochDay--;
        var data = PlayerLotteryStore.get().getOrLoad(uuid);
        DailyTaskTracker.commitCrateProgress(data, pending);
        assertTrue(data.dailyTaskProgress.isEmpty());
    }

    private CrateService.OpenResult finish() throws Exception {
        return (CrateService.OpenResult) finish.invoke(null, pending, null);
    }

    private void restart() throws Exception {
        PlayerLotteryStore.get().reset();
        var state = gson.fromJson(Files.readString(WorldLotteryPaths.configFile("crates.json")), CrateService.State.class);
        stateField.set(null, state);
        pending = state.pendingOpens.get(pending.openId);
    }

    private static Path blockBackup(Path file) throws Exception {
        Path path = AtomicJsonFiles.bakPath(file);
        Files.deleteIfExists(path);
        Files.createDirectories(path);
        Files.writeString(path.resolve("blocked"), "block replacement");
        return path;
    }

    private static void unblock(Path path) throws Exception {
        Files.delete(path.resolve("blocked"));
        Files.delete(path);
    }
}
