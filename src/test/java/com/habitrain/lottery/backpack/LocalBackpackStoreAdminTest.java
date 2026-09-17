package com.habitrain.lottery.backpack;

import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation.ADD;
import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation.SET;
import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus.CORRUPT;
import static org.junit.jupiter.api.Assertions.*;

class LocalBackpackStoreAdminTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void setOnMissingCreatesCompleteCardRecord() {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();

        var result = LocalBackpackStore.mutate(id, FactionCardType.KILLER, SET, 7);

        assertTrue(result.ok());
        assertEquals(7, result.newCount());
        var saved = LocalBackpackStore.load(id);
        assertEquals(7, saved.get("killer"));
        assertTrue(saved.containsKey("civilian"));
        assertTrue(saved.containsKey("neutral"));
        assertTrue(saved.containsKey("neutral_for_killer"));
    }

    @Test
    void addChangesOnlySelectedCardAndPreservesOtherCounts() {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        var initial = LocalBackpackStore.defaultCards();
        initial.put("civilian", 6);
        initial.put("killer", 3);
        assertTrue(LocalBackpackStore.save(id, initial));

        var result = LocalBackpackStore.mutate(id, FactionCardType.KILLER, ADD, -2);

        assertTrue(result.ok());
        var saved = LocalBackpackStore.load(id);
        assertEquals(1, saved.get("killer"));
        assertEquals(6, saved.get("civilian"));
    }

    @Test
    void corruptBackpackIsNeverOverwritten() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        Path file = MetaFeaturePaths.backpackPlayer(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{", StandardCharsets.UTF_8);

        var result = LocalBackpackStore.mutate(id, FactionCardType.KILLER, SET, 7);

        assertFalse(result.ok());
        assertEquals(CORRUPT, result.status());
        assertFalse(Files.exists(file));
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().contains(".corrupt-")));
        }
    }
}
