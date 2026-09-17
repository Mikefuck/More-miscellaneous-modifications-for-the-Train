package com.habitrain.lottery.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLotteryStoreSkinStateTest {

    @Test
    void nonSkinUpdatesDoNotAffectUnlockedOrEquippedMaps() {
        PlayerLotteryData data = new PlayerLotteryData();
        data.lootChance = 5;
        data.coinNum = 100;
        data.unlocked.computeIfAbsent("knife", k -> new HashMap<>()).put("gold_knife", true);
        data.unlocked.computeIfAbsent("revolver", k -> new HashMap<>()).put("vintage_gun", true);
        data.equipped.put("knife", "gold_knife");
        data.equipped.put("revolver", "vintage_gun");

        // Simulate currency and login streak mutations
        data.coinNum += 50;
        data.lootChance -= 1;
        data.consecutiveLoginDays = 3;
        data.lastLoginEpochDay = 20000L;

        // Assert skin maps are untouched
        assertEquals(2, data.unlocked.size());
        assertTrue(Boolean.TRUE.equals(data.unlocked.get("knife").get("gold_knife")));
        assertTrue(Boolean.TRUE.equals(data.unlocked.get("revolver").get("vintage_gun")));
        assertEquals("gold_knife", data.equipped.get("knife"));
        assertEquals("vintage_gun", data.equipped.get("revolver"));
    }

    @Test
    void playerLotteryDataCopyPreservesAllFieldsDeeply() {
        PlayerLotteryData original = new PlayerLotteryData();
        original.lootChance = 10;
        original.coinNum = 500;
        original.unlocked.computeIfAbsent("bat", k -> new HashMap<>()).put("zombie_bat", true);
        original.equipped.put("bat", "zombie_bat");
        original.recentGrants.add("grant:1");

        PlayerLotteryData copied = original.copy();
        assertEquals(original.lootChance, copied.lootChance);
        assertEquals(original.coinNum, copied.coinNum);
        assertEquals("zombie_bat", copied.equipped.get("bat"));
        assertTrue(Boolean.TRUE.equals(copied.unlocked.get("bat").get("zombie_bat")));
        assertTrue(copied.recentGrants.contains("grant:1"));

        // Mutate original and ensure copied does not change
        original.equipped.put("bat", "default");
        original.unlocked.get("bat").put("zombie_bat", false);
        assertEquals("zombie_bat", copied.equipped.get("bat"));
        assertTrue(Boolean.TRUE.equals(copied.unlocked.get("bat").get("zombie_bat")));
    }
}
