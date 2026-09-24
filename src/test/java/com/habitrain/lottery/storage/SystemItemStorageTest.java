package com.habitrain.lottery.storage;

import com.google.gson.Gson;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class SystemItemStorageTest {
    @TempDir Path temp;
    @Test void rewardsSurviveReloadAndCopiesDoNotMutateBalances() throws Exception {
        var data = new PlayerLotteryData(); data.coinNum = 83;
        assertTrue(SystemItemBalances.change(data.systemItems, "events:ticket", 7));
        var copy = data.copy();
        assertTrue(SystemItemBalances.change(copy.systemItems, "events:ticket", -2));
        assertEquals(7, data.systemItems.get("events:ticket"));
        Path file = temp.resolve("player.json");
        Files.writeString(file, new Gson().toJson(copy), StandardCharsets.UTF_8);
        var loaded = PlayerLotteryStore.loadFromDiskForTest(file);
        assertTrue(loaded.ok()); assertEquals(5, loaded.data.systemItems.get("events:ticket"));
        assertEquals(83, loaded.data.coinNum);
    }
    @Test void oldAccountsAndExplicitNullHaveAnEmptySystemInventory() throws Exception {
        for (String json : new String[]{"{\"coinNum\":9}", "{\"coinNum\":9,\"systemItems\":null}"}) {
            Path file = temp.resolve("old.json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
            var loaded = PlayerLotteryStore.loadFromDiskForTest(file);
            assertTrue(loaded.data.systemItems.isEmpty()); assertEquals(9, loaded.data.coinNum);
        }
    }
    @Test void insufficientBalanceOverflowAndNewTypeLimitLeaveTheAccountUntouched() {
        var items = new HashMap<String,Integer>(); items.put("test:full", Integer.MAX_VALUE);
        assertFalse(SystemItemBalances.change(items, "test:full", 1));
        assertEquals(Integer.MAX_VALUE, items.get("test:full"));
        assertFalse(SystemItemBalances.change(items, "test:missing", -1));
        for (int i = 1; i < SystemItemBalances.MAX_TYPES; i++) items.put("test:i" + i, 1);
        assertFalse(SystemItemBalances.change(items, "test:extra", 1));
        assertTrue(SystemItemBalances.change(items, "test:i1", -1));
        assertFalse(items.containsKey("test:i1"));
        assertTrue(SystemItemBalances.change(items, "test:extra", 1));
    }
}
