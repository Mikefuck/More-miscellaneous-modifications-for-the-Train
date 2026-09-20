package com.habitrain.lottery.backpack;

import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SelfSelectCardStoreTest {
    @TempDir Path temp;
    @BeforeEach void setup() { PlayerLotteryStore.get().reset(); WorldLotteryPaths.initForTests(temp); }
    @AfterEach void cleanup() { PlayerLotteryStore.get().reset(); WorldLotteryPaths.clear(); }

    @Test void independentBalancesSurviveBothWritePathsAndReload() {
        UUID id = UUID.randomUUID();
        var cards = LocalBackpackStore.defaultCards();
        cards.put("killer", 7);
        assertTrue(LocalBackpackStore.save(id, cards));
        assertTrue(LocalBackpackStore.addSelfSelectCards(id, 3));
        assertEquals(7, LocalBackpackStore.load(id).get("killer"));
        assertTrue(LocalBackpackStore.addSelfSelectCards(id, -1));
        cards.put("killer", 6);
        assertTrue(LocalBackpackStore.save(id, cards));
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
        assertEquals(6, LocalBackpackStore.load(id).get("killer"));
        assertEquals(2, PlayerCardAdminService.snapshotOffline(id).cards().get("self_select"));
    }

    @Test void insufficientDebitAndOverflowNeverChangeBalance() {
        UUID id = UUID.randomUUID();
        assertFalse(LocalBackpackStore.addSelfSelectCards(id, -1));
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, Integer.MAX_VALUE));
        assertFalse(LocalBackpackStore.addSelfSelectCards(id, 1));
        assertEquals(Integer.MAX_VALUE, LocalBackpackStore.selfSelectCards(id));
    }

    @Test void unchangedAdminSetSucceedsAndValidatesInput() {
        UUID id = UUID.randomUUID();
        assertTrue(PlayerCardAdminService.mutateSelfSelect(id, CardOperation.SET, 0).ok());
        assertTrue(PlayerCardAdminService.mutateSelfSelect(id, CardOperation.SET, 2).ok());
        assertTrue(PlayerCardAdminService.mutateSelfSelect(id, CardOperation.SET, 2).ok());
        assertFalse(PlayerCardAdminService.mutateSelfSelect(id, null, 2).ok());
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
    }

    @Test void pathSaveAndRootParserPreserveSelfSelectBalance() {
        Path path = temp.resolve("cards.json");
        assertTrue(LocalBackpackStore.changeSelfSelectCards(path, 8, true));
        assertTrue(LocalBackpackStore.saveTo(path, LocalBackpackStore.defaultCards()));
        assertEquals(8, LocalBackpackStore.loadFrom(path).selfSelectCards());
        var root = new LocalBackpackStore.FileRoot();
        root.selfSelectCards = 6;
        assertEquals(6, LocalBackpackStore.parseRoot(root).selfSelectCards());
    }

    @Test void failedAtomicWriteDoesNotConsumeCard() throws Exception {
        Path path = temp.resolve("cards.json");
        assertTrue(LocalBackpackStore.changeSelfSelectCards(path, 2, true));
        Files.createDirectory(temp.resolve("cards.json.bak"));
        assertFalse(LocalBackpackStore.changeSelfSelectCards(path, -1, false));
        assertEquals(2, LocalBackpackStore.loadFrom(path).selfSelectCards());
    }

    @Test void selfSelectQuotaIsIndependentAndSurvivesStoreReload() {
        UUID id = UUID.randomUUID();
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryStore.get().update(id, d -> {
            d.lastFactionCardUseEpochDay = today;
            d.factionCardUsesToday = DailyFactionCardService.MAX_DAILY_USES;
        });
        assertEquals(4, DailySelfSelectService.remaining(id));
        DailySelfSelectService.recordSuccessfulUse(id);
        PlayerLotteryStore.get().reset();
        assertEquals(3, DailySelfSelectService.remaining(id));
        assertEquals(4, PlayerLotteryStore.get().getOrLoad(id).factionCardUsesToday);
        DailySelfSelectService.refundSuccessfulUse(id);
        assertEquals(4, DailySelfSelectService.remaining(id));
        PlayerLotteryStore.get().update(id, d -> { d.lastSelfSelectUseEpochDay = today - 1; d.selfSelectUsesToday = 4; });
        assertEquals(4, DailySelfSelectService.remaining(id));
    }

    @Test void virtualLimitBreakCardsSurviveFactionAndSelfSelectWrites() {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.addLimitBreakCards(id, 3));
        assertTrue(LocalBackpackStore.addSelfSelectCards(id, 2));
        var cards = LocalBackpackStore.defaultCards();
        cards.put("killer", 5);
        assertTrue(LocalBackpackStore.save(id, cards));
        assertEquals(3, LocalBackpackStore.limitBreakCards(id));
        assertTrue(LocalBackpackStore.addLimitBreakCards(id, -1));
        assertFalse(LocalBackpackStore.addLimitBreakCards(id, -3));
        assertEquals(2, LocalBackpackStore.limitBreakCards(id));
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
        assertEquals(5, LocalBackpackStore.load(id).get("killer"));
    }

    @Test void limitBreakAdminCreatesPendingBalanceWithoutOverwritingFactionCards() {
        UUID id = UUID.randomUUID();
        assertTrue(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 2).ok());
        assertEquals(2, LocalBackpackStore.limitBreakCards(id));
        assertEquals(2, PlayerCardAdminService.snapshotOffline(id).cards().get("limit_break"));
        assertTrue(LocalBackpackStore.loadResult(id).factionCardsPendingJoin());
        var cards = LocalBackpackStore.defaultCards();
        cards.put("killer", 4);
        assertTrue(LocalBackpackStore.save(id, cards));
        // 「保护写入」语义统一后，非 SRE 内存来源的写入（save/mutate/addCards）不再自行清掉标志：
        // 文件本来是 missing，写进去的阵营卡表只是伪造的全零 + 1 项，清掉标志会让进服用 0 覆盖内存。
        assertTrue(LocalBackpackStore.loadResult(id).factionCardsPendingJoin());
        // 只有进服 overlay 之后的权威写入（内容直接来自 SRE 内存）才清零。
        assertTrue(LocalBackpackStore.saveFromEnumMap(id,
                java.util.Map.of(io.wifi.starrailexpress.progression.ProgressionState.FactionCardType.KILLER, 4)));
        assertFalse(LocalBackpackStore.loadResult(id).factionCardsPendingJoin());
        assertTrue(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.ADD, 3).ok());
        assertTrue(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.ADD, -1).ok());
        assertEquals(4, PlayerCardAdminService.snapshotOffline(id).cards().get("limit_break"));
        assertEquals(4, LocalBackpackStore.load(id).get("killer"));
    }

    @Test void limitBreakAdminValidatesAndAllowsUnchangedAndZeroBalances() {
        UUID id = UUID.randomUUID();
        assertTrue(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 2).ok());
        assertTrue(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 2).ok());
        assertFalse(PlayerCardAdminService.mutateLimitBreak(id, null, 2).ok());
        assertFalse(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.ADD, 1001).ok());
        assertFalse(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, -1).ok());
        assertFalse(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 100001).ok());
        assertEquals(2, LocalBackpackStore.limitBreakCards(id));
        assertTrue(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 0).ok());
        assertEquals(0, PlayerCardAdminService.snapshotOffline(id).cards().get("limit_break"));
    }

    @Test void limitBreakWriteFailureAndOverflowPreserveBalance() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.setLimitBreakCards(id, Integer.MAX_VALUE));
        assertFalse(LocalBackpackStore.addLimitBreakCards(id, 1));
        assertEquals(Integer.MAX_VALUE, LocalBackpackStore.limitBreakCards(id));
        Path path = com.habitrain.lottery.storage.MetaFeaturePaths.backpackPlayer(id);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".bak"));
        Files.createDirectory(path.resolveSibling(path.getFileName() + ".bak"));
        assertFalse(PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 0).ok());
        assertEquals(Integer.MAX_VALUE, LocalBackpackStore.limitBreakCards(id));
    }

    @Test void corruptLimitBreakStorageRejectsAdminWrites() throws Exception {
        UUID id = UUID.randomUUID();
        Path path = com.habitrain.lottery.storage.MetaFeaturePaths.backpackPlayer(id);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken");
        var result = PlayerCardAdminService.mutateLimitBreak(id, CardOperation.SET, 2);
        assertFalse(result.ok());
        assertEquals(PlayerCardAdminModels.CardStoreStatus.CORRUPT, result.status());
    }

    @Test void quotaWriteFailureRestoresCounter() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(DailySelfSelectService.recordSuccessfulUse(id));
        Path file = WorldLotteryPaths.playerFile(id);
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".bak"));
        assertFalse(DailySelfSelectService.recordSuccessfulUse(id));
        assertEquals(3, DailySelfSelectService.remaining(id));
    }

    @Test void quotaServiceRejectsFifthUseAndUnreadableAccount() throws Exception {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 4; i++) assertTrue(DailySelfSelectService.recordSuccessfulUse(id));
        assertFalse(DailySelfSelectService.recordSuccessfulUse(id));
        assertEquals(0, DailySelfSelectService.remaining(id));
        UUID broken = UUID.randomUUID();
        Path file = WorldLotteryPaths.playerFile(broken);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken");
        assertEquals(0, DailySelfSelectService.remaining(broken));
        assertFalse(DailySelfSelectService.recordSuccessfulUse(broken));
    }
}
