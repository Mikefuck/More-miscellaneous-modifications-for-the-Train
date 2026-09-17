package com.habitrain.lottery.backpack;

import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalBackpackStoreLoadTest {

    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void missingFileIsNotAuthoritativeEmpty() {
        Path missing = temp.resolve("nope.json");
        LocalBackpackStore.LoadResult result = LocalBackpackStore.loadFromPath(missing);
        assertEquals(LocalBackpackStore.LoadResult.Status.MISSING, result.status());
        assertTrue(result.seedFromSre());
        assertFalse(result.applyNegativeDeltas());
        assertTrue(result.writeBack());
    }

    @Test
    void presentEmptyCardsIsAuthoritativeZero() {
        LocalBackpackStore.FileRoot root = new LocalBackpackStore.FileRoot();
        root.cards.clear();
        LocalBackpackStore.LoadResult result = LocalBackpackStore.parseRoot(root);
        assertEquals(LocalBackpackStore.LoadResult.Status.OK, result.status());
        assertTrue(result.applyNegativeDeltas());
        assertTrue(result.cards().values().stream().allMatch(v -> v == 0));
    }

    @Test
    void corruptJsonDoesNotWriteBackZeros() throws Exception {
        Path file = temp.resolve("bad.json");
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
        LocalBackpackStore.LoadResult result = LocalBackpackStore.loadFromPath(file);
        assertEquals(LocalBackpackStore.LoadResult.Status.CORRUPT, result.status());
        assertFalse(result.applyNegativeDeltas());
        assertFalse(result.writeBack());
        assertFalse(result.seedFromSre());
    }

    @Test
    void nullCardsFieldIsCorruptNotEmpty() {
        LocalBackpackStore.FileRoot root = new LocalBackpackStore.FileRoot();
        root.cards = null;
        LocalBackpackStore.LoadResult result = LocalBackpackStore.parseRoot(root);
        assertEquals(LocalBackpackStore.LoadResult.Status.CORRUPT, result.status());
        assertFalse(result.applyNegativeDeltas());
    }

    @Test
    void readableFileKeepsCounts() {
        LocalBackpackStore.FileRoot root = new LocalBackpackStore.FileRoot();
        root.cards.put("killer", 3);
        LocalBackpackStore.LoadResult result = LocalBackpackStore.parseRoot(root);
        assertEquals(LocalBackpackStore.LoadResult.Status.OK, result.status());
        assertEquals(3, result.cards().get("killer"));
    }

    @Test
    void addCardsIncrementsExistingAndSeedsMissing() {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.addCards(id, FactionCardType.KILLER, 5));
        String key = FactionCardType.KILLER.questKey.toLowerCase();
        assertEquals(5, LocalBackpackStore.load(id).get(key));
        assertTrue(LocalBackpackStore.addCards(id, FactionCardType.KILLER, 2));
        assertEquals(7, LocalBackpackStore.load(id).get(key));
    }
}
