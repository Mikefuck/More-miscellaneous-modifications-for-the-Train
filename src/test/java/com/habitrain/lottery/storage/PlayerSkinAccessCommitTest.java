package com.habitrain.lottery.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSkinAccessCommitTest {
    @TempDir
    Path temp;
    private final PlayerLotteryStore store = PlayerLotteryStore.get();
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store.reset();
        WorldLotteryPaths.initForTest(temp);
    }

    @AfterEach
    void tearDown() {
        store.reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void unlockPersistsAliasesWithoutEquippingOrChangingCurrency() {
        store.update(id, data -> {
            data.coinNum = 75;
            data.lootChance = 4;
        });
        assertTrue(store.commitSkinAccess(id, "trainmurdermystery:gun", " Gold_Gun ", true));
        store.reset();
        assertTrue(store.isSkinUnlocked(id, "gun", "gold_gun"));
        assertTrue(store.isSkinUnlocked(id, "revolver", "gold_gun"));
        assertNull(store.getEquipped(id, "gun"));
        assertEquals(75, store.getCoinNum(id));
        assertEquals(4, store.getLootChance(id));
    }

    @Test
    void lockRemovesEquippedSkinAndAllHistoricalAliasesDurably() {
        store.update(id, data -> {
            for (String key : new String[]{"gun", "revolver", "trainmurdermystery:gun"}) {
                data.unlocked.computeIfAbsent(key, ignored -> new HashMap<>()).put("gold_gun", true);
                data.equipped.put(key, "gold_gun");
            }
            data.unlocked.computeIfAbsent("knife", ignored -> new HashMap<>()).put("gold_gun", true);
            data.equipped.put("knife", "gold_gun");
        });
        assertTrue(store.commitSkinAccess(id, "gun", "gold_gun", false));
        store.reset();
        for (String key : new String[]{"gun", "revolver", "trainmurdermystery:gun"}) {
            assertFalse(store.isSkinUnlocked(id, key, "gold_gun"));
            assertNull(store.getOrLoad(id).equipped.get(key));
        }
        assertTrue(store.isSkinUnlocked(id, "knife", "gold_gun"));
        assertEquals("gold_gun", store.getEquipped(id, "knife"));
    }

    @Test
    void lockingUnequippedSkinPreservesOtherSkinAndRepeatedCommandsAreSafe() {
        assertTrue(store.commitSkinAccess(id, "bat", "anvil", true));
        assertTrue(store.commitSkinAccess(id, "bat", "anvil", true));
        assertTrue(store.commitSkinAccess(id, "bat", "bamboo", true));
        assertTrue(store.commitEquipped(id, "bat", "bamboo").committed());
        assertTrue(store.commitSkinAccess(id, "bat", "anvil", false));
        assertTrue(store.commitSkinAccess(id, "bat", "anvil", false));
        store.reset();
        assertFalse(store.isSkinUnlocked(id, "bat", "anvil"));
        assertTrue(store.isSkinUnlocked(id, "bat", "bamboo"));
        assertEquals("bamboo", store.getEquipped(id, "bat"));
    }

    @Test
    void failedLockRestoresEquipmentUnlocksAndPreexistingDirtyChanges() throws Exception {
        assertTrue(store.commitSkinAccess(id, "bat", "anvil", true));
        assertTrue(store.commitEquipped(id, "bat", "anvil").committed());
        store.update(id, data -> data.coinNum = 91);
        // Block only this test account's atomic temporary file.
        Path blocked = WorldLotteryPaths.playerFile(id).resolveSibling(id + ".json.tmp");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied"), "force write failure");
        assertFalse(store.commitSkinAccess(id, "bat", "anvil", false));
        assertTrue(store.isSkinUnlocked(id, "bat", "anvil"));
        assertEquals("anvil", store.getEquipped(id, "bat"));
        assertEquals(91, store.getCoinNum(id));
        assertTrue(store.isDirty(id));
        assertTrue(PlayerLotteryStore.loadFromDiskForTest(WorldLotteryPaths.playerFile(id))
                .data.unlocked.get("bat").get("anvil"));
    }

    @Test
    void failedUnlockRestoresCleanState() throws Exception {
        store.update(id, data -> data.coinNum = 30);
        assertTrue(store.flush(id));
        Path blocked = WorldLotteryPaths.playerFile(id).resolveSibling(id + ".json.tmp");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied"), "force write failure");
        assertFalse(store.commitSkinAccess(id, "bat", "anvil", true));
        assertFalse(store.isSkinUnlocked(id, "bat", "anvil"));
        assertEquals(30, store.getCoinNum(id));
        assertFalse(store.isDirty(id));
    }

    @Test
    void rejectsDefaultAndDeferredWritesWithoutMutation() {
        assertFalse(store.commitSkinAccess(id, "bat", "default", false));
        assertFalse(store.commitSkinAccess(id, "bat", " DEFAULT ", true));
        store.beginDeferredFlush();
        try {
            assertFalse(store.commitSkinAccess(id, "bat", "anvil", true));
            assertFalse(store.isSkinUnlocked(id, "bat", "anvil"));
            assertFalse(Files.exists(WorldLotteryPaths.playerFile(id)));
        } finally {
            store.endDeferredFlush();
        }
    }

    @Test
    void rejectsCorruptAccountWithoutCreatingReplacement() throws Exception {
        Path file = WorldLotteryPaths.playerFile(id);
        Files.writeString(file, "{broken");
        Files.writeString(AtomicJsonFiles.bakPath(file), "{broken");
        assertFalse(store.commitSkinAccess(id, "bat", "anvil", true));
        assertTrue(store.isLoadFailed(id));
        assertFalse(store.isDirty(id));
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectsMissingWorldOrPlayer() {
        assertFalse(store.commitSkinAccess(null, "bat", "anvil", true));
        WorldLotteryPaths.clear();
        assertFalse(store.commitSkinAccess(id, "bat", "anvil", true));
    }
}
