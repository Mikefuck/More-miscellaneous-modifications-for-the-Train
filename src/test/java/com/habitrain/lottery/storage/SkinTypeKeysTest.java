package com.habitrain.lottery.storage;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkinTypeKeysTest {

    @Test
    void canonicalResolvesStandardTypes() {
        assertEquals("knife", SkinTypeKeys.canonical("knife"));
        assertEquals("revolver", SkinTypeKeys.canonical("revolver"));
        assertEquals("revolver", SkinTypeKeys.canonical("gun"));
        assertEquals("bat", SkinTypeKeys.canonical("bat"));
        assertEquals("grenade", SkinTypeKeys.canonical("grenade"));
        assertEquals("hat", SkinTypeKeys.canonical("hat"));
    }

    @Test
    void canonicalStripsNamespacesAndNormalizesCase() {
        assertEquals("knife", SkinTypeKeys.canonical("trainmurdermystery:knife"));
        assertEquals("revolver", SkinTypeKeys.canonical("starrailexpress:revolver"));
        assertEquals("revolver", SkinTypeKeys.canonical("starrailexpress:gun"));
        assertEquals("bat", SkinTypeKeys.canonical("  BAT  "));
        assertEquals("knife", SkinTypeKeys.canonical("KNIFE"));
        assertEquals("default", SkinTypeKeys.canonical(null));
        assertEquals("default", SkinTypeKeys.canonical("   "));
    }

    @Test
    void writeKeysIncludesCanonicalAndAliases() {
        Set<String> gunKeys = SkinTypeKeys.writeKeys("gun");
        assertTrue(gunKeys.contains("revolver"));
        assertTrue(gunKeys.contains("gun"));

        Set<String> revolverKeys = SkinTypeKeys.writeKeys("starrailexpress:revolver");
        assertTrue(revolverKeys.contains("revolver"));
        assertTrue(revolverKeys.contains("gun"));

        Set<String> knifeKeys = SkinTypeKeys.writeKeys("trainmurdermystery:knife");
        assertTrue(knifeKeys.contains("knife"));
    }
}
