package com.habitrain.lottery.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GrantKeysTest {
    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
    }

    @Test
    void consumeIsIdempotent() {
        PlayerLotteryData data = new PlayerLotteryData();
        assertTrue(GrantKeys.tryConsume(data, "sre_participate:minecraft:overworld:10"));
        assertFalse(GrantKeys.tryConsume(data, "sre_participate:minecraft:overworld:10"));
        assertTrue(GrantKeys.contains(data, "sre_participate:minecraft:overworld:10"));
    }

    @Test
    void capDropsOldestSoRestartKeySpaceDoesNotGrowForever() {
        PlayerLotteryData data = new PlayerLotteryData();
        String first = "k0";
        assertTrue(GrantKeys.tryConsume(data, first));
        for (int i = 1; i <= GrantKeys.MAX_RECENT; i++) {
            assertTrue(GrantKeys.tryConsume(data, "k" + i));
        }
        assertEquals(GrantKeys.MAX_RECENT, data.recentGrants.size());
        assertFalse(GrantKeys.contains(data, first));
        assertTrue(GrantKeys.tryConsume(data, first));
    }

    @Test
    void blankKeyIsRejected() {
        PlayerLotteryData data = new PlayerLotteryData();
        assertFalse(GrantKeys.tryConsume(data, ""));
        assertFalse(GrantKeys.tryConsume(data, null));
    }

    @Test
    void storeConsumeRejectsDuplicate() {
        UUID id = UUID.randomUUID();
        assertTrue(PlayerLotteryStore.get().tryConsumeGrantKey(id, "match:1"));
        assertFalse(PlayerLotteryStore.get().tryConsumeGrantKey(id, "match:1"));
        assertTrue(PlayerLotteryStore.get().tryConsumeGrantKey(id, "match:2"));
    }

    @Test
    void restartStyleUuidKeyIsNotDuplicateOfSeqOne() {
        UUID id = UUID.randomUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertTrue(store.tryConsumeGrantKey(id, "sre_participate:1"));
        String uuidKey = "sre_participate:" + UUID.randomUUID();
        assertTrue(store.tryConsumeGrantKey(id, uuidKey));
        assertFalse(store.tryConsumeGrantKey(id, "sre_participate:1"));
    }

    @Test
    void sixtyFifthDistinctKeyEvictsOldest() {
        UUID id = UUID.randomUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertTrue(store.tryConsumeGrantKey(id, "k0"));
        for (int i = 1; i <= 64; i++) {
            assertTrue(store.tryConsumeGrantKey(id, "k" + i));
        }
        assertEquals(64, store.getOrLoad(id).recentGrants.size());
        assertFalse(store.getOrLoad(id).recentGrants.contains("k0"));
        assertTrue(store.tryConsumeGrantKey(id, "k0"));
    }

    @Test
    void loginKeysAreNotStoredInRecentGrants() {
        UUID id = UUID.randomUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertTrue(store.tryConsumeGrantKey(id, "login:20000"));
        assertFalse(store.getOrLoad(id).recentGrants.contains("login:20000"));
        assertFalse(store.isDirty(id));
    }
}
