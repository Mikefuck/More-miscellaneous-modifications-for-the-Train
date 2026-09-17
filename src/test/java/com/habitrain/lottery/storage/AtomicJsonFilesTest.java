package com.habitrain.lottery.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AtomicJsonFilesTest {

    private static final Gson GSON = new GsonBuilder().create();

    @TempDir
    Path tmp;

    @Test
    void writeJsonReplacesViaTmpAndKeepsBak() throws Exception {
        Path file = tmp.resolve("pools.json");
        Files.writeString(file, "{\"v\":1}", StandardCharsets.UTF_8);

        Map<String, Integer> next = new LinkedHashMap<>();
        next.put("v", 2);
        assertTrue(AtomicJsonFiles.writeJson(file, next, GSON, true));

        assertEquals("{\"v\":2}", Files.readString(file, StandardCharsets.UTF_8).trim());
        assertEquals("{\"v\":1}", Files.readString(AtomicJsonFiles.bakPath(file), StandardCharsets.UTF_8).trim());
        assertFalse(Files.exists(file.resolveSibling("pools.json.tmp")));
    }

    @Test
    void bakCopyFailureAbortsReplace() throws Exception {
        Path file = tmp.resolve("player.json");
        Files.writeString(file, "{\"lootChance\":9}", StandardCharsets.UTF_8);
        Files.createDirectory(AtomicJsonFiles.bakPath(file));

        Map<String, Integer> next = new LinkedHashMap<>();
        next.put("lootChance", 0);
        assertFalse(AtomicJsonFiles.writeJson(file, next, GSON, true));
        assertEquals("{\"lootChance\":9}", Files.readString(file, StandardCharsets.UTF_8).trim());
    }

    @Test
    void truncatedPrimaryRestoresBakAndQuarantines() throws Exception {
        Path file = tmp.resolve("u.json");
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{\"lootChance\":7}", StandardCharsets.UTF_8);

        AtomicJsonFiles.JsonLoad<PlayerLotteryData> load =
                AtomicJsonFiles.readJson(file, PlayerLotteryData.class, GSON);
        assertTrue(load.ok());
        assertTrue(load.usedBackup());
        assertEquals(7, load.value().lootChance);
        assertFalse(Files.isRegularFile(file));
        assertNotNull(load.quarantined());
        assertTrue(Files.isRegularFile(load.quarantined()));
    }

    @Test
    void missingPrimaryStillReadsBak() throws Exception {
        Path file = tmp.resolve("missing.json");
        Files.writeString(AtomicJsonFiles.bakPath(file), "{\"coinNum\":42}", StandardCharsets.UTF_8);

        AtomicJsonFiles.JsonLoad<PlayerLotteryData> load =
                AtomicJsonFiles.readJson(file, PlayerLotteryData.class, GSON);
        assertTrue(load.ok());
        assertTrue(load.usedBackup());
        assertEquals(42, load.value().coinNum);
        assertFalse(load.corrupt());
    }

    @Test
    void bothUnreadableIsCorruptNotEmptyObject() throws Exception {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{", StandardCharsets.UTF_8);

        AtomicJsonFiles.JsonLoad<PlayerLotteryData> load =
                AtomicJsonFiles.readJson(file, PlayerLotteryData.class, GSON);
        assertTrue(load.corrupt());
        assertFalse(load.ok());
        assertNull(load.value());
    }

    @Test
    void neitherFileIsMissing() {
        Path file = tmp.resolve("nope.json");
        AtomicJsonFiles.JsonLoad<PlayerLotteryData> load =
                AtomicJsonFiles.readJson(file, PlayerLotteryData.class, GSON);
        assertTrue(load.isMissing());
        assertFalse(load.corrupt());
        assertNull(load.value());
    }

    @Test
    void writeBytesRoundTrip() throws Exception {
        Path file = tmp.resolve("placeholder.png");
        byte[] payload = new byte[]{1, 2, 3, 4};
        assertTrue(AtomicJsonFiles.writeBytes(file, payload, true, true));
        assertArrayEquals(payload, Files.readAllBytes(file));
    }
}
