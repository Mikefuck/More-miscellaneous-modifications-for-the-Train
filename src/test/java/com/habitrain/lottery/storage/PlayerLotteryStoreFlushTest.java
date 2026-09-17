package com.habitrain.lottery.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLotteryStoreFlushTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTest(temp);
    }

    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void flushIsNoOpWhenNotDirty() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.getOrLoad(id);
        assertFalse(store.isDirty(id));
        store.flush(id);
        assertFalse(Files.isRegularFile(WorldLotteryPaths.playerFile(id)));
    }

    @Test
    void flushWritesOnceThenSecondFlushDoesNotRewrite() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.update(id, d -> d.coinNum = 7);
        store.flush(id);

        Path file = WorldLotteryPaths.playerFile(id);
        assertTrue(Files.isRegularFile(file));
        byte[] first = Files.readAllBytes(file);
        FileTime mtime = Files.getLastModifiedTime(file);
        assertFalse(store.isDirty(id));

        Thread.sleep(30);
        store.flush(id);
        assertArrayEquals(first, Files.readAllBytes(file));
        assertEquals(mtime, Files.getLastModifiedTime(file));
    }

    @Test
    void compactJsonHasNoPrettyIndent() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.update(id, d -> d.lootChance = 2);
        store.flush(id);
        String json = Files.readString(WorldLotteryPaths.playerFile(id), StandardCharsets.UTF_8);
        assertFalse(json.contains("\n  "), json);
        assertFalse(json.contains("\n"), json);
    }

    @Test
    void recentGrantsCapDropsOldest() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        int cap = PlayerLotteryData.RECENT_GRANTS_CAP;
        assertTrue(cap > 0);
        for (int i = 0; i < cap + 44; i++) {
            assertTrue(store.tryConsumeGrantKey(id, "g" + i));
        }
        PlayerLotteryData data = store.getOrLoad(id);
        assertEquals(cap, data.recentGrants.size());
        assertFalse(data.recentGrants.contains("g0"));
        assertFalse(data.recentGrants.contains("g43"));
        assertTrue(data.recentGrants.contains("g44"));
        assertTrue(data.recentGrants.contains("g" + (cap + 43)));
    }

    @Test
    void deferredFlushSkipsWriteUntilEnded() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.update(id, d -> d.lootChance = 5);
        assertTrue(store.flush(id));
        Path file = WorldLotteryPaths.playerFile(id);
        String before = Files.readString(file, StandardCharsets.UTF_8);

        store.beginDeferredFlush();
        store.update(id, d -> d.lootChance = 4);
        assertTrue(store.flush(id));
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(store.isDirty(id));
        store.endDeferredFlush();
        assertTrue(store.flush(id));
        assertFalse(store.isDirty(id));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"lootChance\":4"));
    }

    @Test
    void adoptJoinKeepsDirtyCacheInsteadOfDiskClobber() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.update(id, d -> d.lootChance = 3);
        assertTrue(store.flush(id));
        assertEquals(3, store.getOrLoad(id).lootChance);

        // Simulate quit-failed path: dirty memory ahead of disk.
        store.update(id, d -> d.lootChance = 99);
        assertTrue(store.isDirty(id));
        assertEquals(3, PlayerLotteryStore.loadFromDiskForTest(WorldLotteryPaths.playerFile(id)).data.lootChance);

        // Force adoptJoin to keep dirty by making flush a no-op write failure path:
        // keep dirty without flushing, then adoptJoin retries flush (succeeds here) —
        // still must not replace with stale disk when dirty remains.
        store.beginDeferredFlush();
        PlayerLotteryData adopted = store.adoptJoin(id);
        assertNotNull(adopted);
        assertEquals(99, adopted.lootChance);
        assertTrue(store.isDirty(id));
        store.endDeferredFlush();
        assertTrue(store.flush(id));
        assertEquals(99, store.getOrLoad(id).lootChance);
    }

    @Test
    void adoptJoinAfterSuccessfulFlushUsesCache() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.update(id, d -> d.coinNum = 12);
        assertTrue(store.isDirty(id));
        PlayerLotteryData adopted = store.adoptJoin(id);
        assertNotNull(adopted);
        assertEquals(12, adopted.coinNum);
        assertFalse(store.isDirty(id));
    }

    @Test
    void restoreSnapshotRestoresGrantKeyForRetry() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        PlayerLotteryData snap = store.getOrLoad(id).copy();
        boolean wasDirty = store.isDirty(id);
        assertTrue(store.tryConsumeGrantKey(id, "login:test"));
        store.update(id, d -> d.lootChance = 5);
        store.restoreSnapshot(id, snap, wasDirty);
        assertFalse(store.isDirty(id));
        assertEquals(0, store.getOrLoad(id).lootChance);
        assertTrue(store.tryConsumeGrantKey(id, "login:test"));
    }

    @Test
    void equippedCommitIsDurableBeforeItReportsSuccess() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();

        PlayerLotteryStore.EquippedCommitResult result =
                store.commitEquipped(id, "trainmurdermystery:knife", " Gold_Knife ");

        assertTrue(result.committed());
        assertEquals("knife", result.type());
        assertEquals("gold_knife", result.skin());
        assertEquals("gold_knife", store.getEquipped(id, "knife"));
        String json = Files.readString(WorldLotteryPaths.playerFile(id), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"knife\":\"gold_knife\""), json);
        assertFalse(store.isDirty(id));
    }

    @Test
    void equippedCommitRollsBackWholeSnapshotWhenWriteFails() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        store.update(id, data -> {
            data.coinNum = 23;
            data.unlocked.computeIfAbsent("knife", ignored -> new java.util.HashMap<>())
                    .put("old_skin", true);
        });
        assertTrue(store.commitEquipped(id, "knife", "old_skin").committed());

        Path playerFile = WorldLotteryPaths.playerFile(id);
        Files.delete(playerFile);
        Files.createDirectory(playerFile);

        PlayerLotteryStore.EquippedCommitResult result =
                store.commitEquipped(id, "knife", "new_skin");

        assertFalse(result.committed());
        assertEquals("write_failed", result.failure());
        assertEquals("old_skin", store.getEquipped(id, "knife"));
        assertEquals(23, store.getCoinNum(id));
        assertTrue(Boolean.TRUE.equals(store.getOrLoad(id).unlocked.get("knife").get("old_skin")));
        assertFalse(store.isDirty(id));
    }

    @Test
    void equippedCommitRejectsDeferredFlushInsteadOfFalseSuccess() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        assertTrue(store.commitEquipped(id, "bat", "old_bat").committed());

        store.beginDeferredFlush();
        try {
            PlayerLotteryStore.EquippedCommitResult result =
                    store.commitEquipped(id, "bat", "new_bat");
            assertFalse(result.committed());
            assertEquals("flush_deferred", result.failure());
            assertEquals("old_bat", store.getEquipped(id, "bat"));
        } finally {
            store.endDeferredFlush();
        }
    }
}
