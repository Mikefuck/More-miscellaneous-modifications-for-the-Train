package com.habitrain.lottery.card;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.grant.SelfSelectRoleHook;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SelfSelectActivationTest {
    @TempDir Path temp;
    @BeforeEach void setup() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(temp);
        SelfSelectForces.clear();
        ActiveCardForces.clear();
        CardUseService.clearRefundState();
    }
    @AfterEach void cleanup() {
        SelfSelectForces.clear();
        ActiveCardForces.clear();
        CardUseService.clearRefundState();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test void selfSelectReservationAndShutdownRefundNeverCreateFactionForces() {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, 2));
        assertTrue(CardUseService.consumeSelfSelect(id, ResourceLocation.parse("test:vigilante")));
        assertEquals(1, LocalBackpackStore.selfSelectCards(id));
        assertTrue(ActiveCardForces.isEmpty());
        assertEquals(3, DailySelfSelectService.remaining(id));
        assertEquals(0, PlayerLotteryStore.get().getOrLoad(id).factionCardUsesToday);
        SelfSelectRoleHook.finishSelections();
        SelfSelectRoleHook.finishSelections();
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
        assertEquals(4, DailySelfSelectService.remaining(id));
        assertTrue(SelfSelectForces.isEmpty());
        assertTrue(LocalBackpackStore.load(id).values().stream().allMatch(n -> n == 0));
    }

    @Test void factionCardCannotPayForSelfSelectionAndRepeatedReservationCannotChargeTwice() {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.addCards(id, FactionCardType.KILLER, 10));
        var role = ResourceLocation.parse("test:killer");
        assertFalse(CardUseService.consumeSelfSelect(id, role));
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, 2));
        assertTrue(CardUseService.consumeSelfSelect(id, role));
        assertFalse(CardUseService.consumeSelfSelect(id, role));
        assertEquals(1, LocalBackpackStore.selfSelectCards(id));
        assertEquals(10, LocalBackpackStore.load(id).get("killer"));
    }

    @Test void failedQuotaDebitReturnsCardWithoutReservingRole() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, 2));
        assertTrue(DailySelfSelectService.recordSuccessfulUse(id));
        Path file = WorldLotteryPaths.playerFile(id);
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".bak"));
        assertFalse(CardUseService.consumeSelfSelect(id, ResourceLocation.parse("test:civilian")));
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
        assertEquals(3, DailySelfSelectService.remaining(id));
        assertFalse(SelfSelectForces.has(id));
    }

    @Test void failedBalanceRefundRemainsPendingAndRetriesOnceStorageRecovers() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, 2));
        assertTrue(CardUseService.consumeSelfSelect(id, ResourceLocation.parse("test:neutral")));
        Path file = MetaFeaturePaths.backpackPlayer(id);
        Path blocked = AtomicJsonFiles.bakPath(file);
        // 审核 S-01：临时文件名现在是唯一的（<name>.<jvm>-<seq>.tmp），
        // 过去靠「在 <file>.tmp 上创建目录」来强制写失败的做法已经失效。
        // 改为阻塞 .bak：这些写入都是 backup=true，且目标文件已存在，
        // AtomicJsonFiles 在 .bak 复制失败时会明确拒绝替换主文件（并删掉临时文件）。
        // 注意 .bak 可能已被上一步写入创建，必须先删掉再建目录。
        Files.deleteIfExists(blocked);
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("blocker"), "prevent atomic write");
        SelfSelectRoleHook.finishSelections();
        assertTrue(SelfSelectForces.has(id));
        assertTrue(SelfSelectForces.refundPending(id));
        assertEquals(1, LocalBackpackStore.selfSelectCards(id));
        Files.delete(blocked.resolve("blocker"));
        Files.delete(blocked);
        SelfSelectRoleHook.finishSelections();
        assertFalse(SelfSelectForces.has(id));
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
    }

    @Test void quotaRefundRetryDoesNotIssueAnotherCard() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, 2));
        assertTrue(CardUseService.consumeSelfSelect(id, ResourceLocation.parse("test:neutral")));
        Path file = WorldLotteryPaths.playerFile(id);
        // 审核 S-01：见上一条注释——改为阻塞 .bak 来强制写失败。
        Path blocked = AtomicJsonFiles.bakPath(file);
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("blocker"), "prevent atomic write");
        SelfSelectRoleHook.finishSelections();
        assertTrue(SelfSelectForces.has(id));
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
        Files.delete(blocked.resolve("blocker"));
        Files.delete(blocked);
        SelfSelectRoleHook.finishSelections();
        assertFalse(SelfSelectForces.has(id));
        assertEquals(2, LocalBackpackStore.selfSelectCards(id));
        assertEquals(4, DailySelfSelectService.remaining(id));
    }
}
