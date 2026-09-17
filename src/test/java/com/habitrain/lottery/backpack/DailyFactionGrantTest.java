package com.habitrain.lottery.backpack;

import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DailyFactionGrantTest {
    @TempDir Path temp;
    @BeforeEach void setup() { PlayerLotteryStore.get().reset(); WorldLotteryPaths.initForTests(temp); }
    @AfterEach void cleanup() { PlayerLotteryStore.get().reset(); WorldLotteryPaths.clear(); }

    @Test void grantsFourFactionsOncePerDayWithoutAddingSelfSelectCards() {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.setSelfSelectCards(id, 8));
        FakeCards access = new FakeCards(id);
        assertTrue(DailyFactionCardService.grantLoginCards(id, access));
        assertFalse(DailyFactionCardService.grantLoginCards(id, access));
        assertEquals(4, access.cards.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(8, LocalBackpackStore.selfSelectCards(id));
        PlayerLotteryStore.get().reset();
        assertFalse(DailyFactionCardService.grantLoginCards(id, access));
        assertEquals(LoginRewardService.todayEpochDayUtc(), PlayerLotteryStore.get().getOrLoad(id).lastFactionCardGrantEpochDay);
    }

    @Test void partialAddFailureRestoresEarlierCardsAndAllowsOneCleanRetry() {
        UUID id = UUID.randomUUID();
        FakeCards access = new FakeCards(id);
        access.failOnAdd = 3;
        assertFalse(DailyFactionCardService.grantLoginCards(id, access));
        assertTrue(access.cards.values().stream().allMatch(n -> n == 0));
        assertEquals(-1, PlayerLotteryStore.get().getOrLoad(id).lastFactionCardGrantEpochDay);
        assertTrue(DailyFactionCardService.grantLoginCards(id, access));
        assertEquals(4, access.cards.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void dayWriteFailureRestoresPersistedFactionBalances() throws Exception {
        UUID id = UUID.randomUUID();
        FakeCards access = new FakeCards(id);
        PlayerLotteryStore.get().update(id, data -> data.coinNum = 12);
        assertTrue(PlayerLotteryStore.get().flush(id));
        Path file = WorldLotteryPaths.playerFile(id);
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".bak"));
        assertFalse(DailyFactionCardService.grantLoginCards(id, access));
        assertTrue(access.cards.values().stream().allMatch(n -> n == 0));
        assertTrue(LocalBackpackStore.load(id).values().stream().allMatch(n -> n == 0));
        assertEquals(-1, PlayerLotteryStore.get().getOrLoad(id).lastFactionCardGrantEpochDay);
    }

    private static final class FakeCards implements PlayerCardAdminService.OnlineCardAccess {
        final UUID id;
        final Map<FactionCardType, Integer> cards = new EnumMap<>(FactionCardType.class);
        int adds;
        int failOnAdd = -1;
        FakeCards(UUID id) { this.id = id; }
        public int count(FactionCardType type) { return cards.getOrDefault(type, 0); }
        public void add(FactionCardType type, int delta) {
            if (++adds == failOnAdd) throw new IllegalStateException("simulated add failure");
            cards.put(type, count(type) + delta);
        }
        public boolean persist() { return LocalBackpackStore.saveFromEnumMap(id, cards); }
        public void resend() {}
        public Map<FactionCardType, Integer> cards() { return new EnumMap<>(cards); }
        public boolean storageCorrupt() { return false; }
    }
}
