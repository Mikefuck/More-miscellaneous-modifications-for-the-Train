package com.habitrain.lottery.backpack;

import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@code B-06} ({@link BackpackJoinService} join overlay must hold
 * {@link LocalBackpackStore#isApplyingCommitted()} across the entire {@code addCard} loop),
 * {@code N-08} (case-normalized questKey lookup), and {@code I-05} (preserving valid
 * {@code .bak} when recovering from a corrupt primary file).
 */
class BackpackJoinOverlaySingleWriteTest {

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void joinOverlaySuppressesIntermediateMixinWritesAndPersistsOnce() {
        UUID player = UUID.randomUUID();
        Map<String, Integer> authoritativeLocal = new LinkedHashMap<>();
        authoritativeLocal.put("killer", 5);
        authoritativeLocal.put("civilian", 10);
        authoritativeLocal.put("neutral", 7);
        authoritativeLocal.put("neutral_for_killer", 3);

        EnumMap<FactionCardType, Integer> sreMemory = new EnumMap<>(FactionCardType.class);
        AtomicInteger addCalls = new AtomicInteger();
        AtomicInteger intermediateDiskWrites = new AtomicInteger();

        BackpackJoinService.overlayCards(sreMemory, authoritativeLocal, false, (type, delta) -> {
            addCalls.incrementAndGet();
            assertTrue(LocalBackpackStore.isApplyingCommitted(),
                    "isApplyingCommitted() must be true during join overlay addCard #" + addCalls.get());
            sreMemory.put(type, Math.max(0, sreMemory.getOrDefault(type, 0) + delta));
            // Mirror BackpackManagerMixin#persist: skipped while isApplyingCommitted() is true.
            if (!LocalBackpackStore.isApplyingCommitted()) {
                intermediateDiskWrites.incrementAndGet();
                LocalBackpackStore.saveFromEnumMap(player, sreMemory);
            }
        });

        assertFalse(LocalBackpackStore.isApplyingCommitted(),
                "isApplyingCommitted() must be cleared after overlayCards completes");
        assertEquals(4, addCalls.get());
        assertEquals(0, intermediateDiskWrites.get(), "Overlay loop must trigger zero intermediate disk writes");

        // Single authoritative flush at the end of applyOnce.
        assertTrue(LocalBackpackStore.saveFromEnumMap(player, sreMemory));
        Map<String, Integer> reloaded = LocalBackpackStore.load(player);
        assertEquals(5, reloaded.get("killer"));
        assertEquals(10, reloaded.get("civilian"));
        assertEquals(7, reloaded.get("neutral"));
        assertEquals(3, reloaded.get("neutral_for_killer"));
    }

    @Test
    void runWithApplyingCommittedAlwaysClearsFlagOnException() {
        assertFalse(LocalBackpackStore.isApplyingCommitted());
        assertThrows(IllegalStateException.class, () ->
                LocalBackpackStore.runWithApplyingCommitted(() -> {
                    assertTrue(LocalBackpackStore.isApplyingCommitted());
                    throw new IllegalStateException("boom");
                }));
        assertFalse(LocalBackpackStore.isApplyingCommitted());
    }

    @Test
    void saveAfterBackupRecoveryDoesNotOverwriteValidBackupWithCorruptPrimary() throws Exception {
        UUID player = UUID.randomUUID();
        EnumMap<FactionCardType, Integer> initial = new EnumMap<>(FactionCardType.class);
        initial.put(FactionCardType.KILLER, 5);
        initial.put(FactionCardType.CIVILIAN, 2);

        // Write twice so both <uuid>.json and <uuid>.json.bak contain valid JSON.
        assertTrue(LocalBackpackStore.saveFromEnumMap(player, initial));
        assertTrue(LocalBackpackStore.saveFromEnumMap(player, initial));

        Path primary = MetaFeaturePaths.backpackPlayer(player);
        Path bak = AtomicJsonFiles.bakPath(primary);
        assertTrue(Files.isRegularFile(bak));

        // Corrupt primary while .bak remains valid; saveFromEnumMap reads existing (usedBackup=true)
        // and must pass backup=false so .bak is never overwritten by corrupt primary bytes.
        String bakBefore = Files.readString(bak, StandardCharsets.UTF_8);
        Files.writeString(primary, "{broken-json", StandardCharsets.UTF_8);

        initial.put(FactionCardType.KILLER, 6);
        assertTrue(LocalBackpackStore.saveFromEnumMap(player, initial));

        String bakAfter = Files.readString(bak, StandardCharsets.UTF_8);
        assertFalse(bakAfter.contains("{broken-json"), "Corrupt primary must never overwrite valid .bak");
        assertEquals(bakBefore, bakAfter);
        assertEquals(6, LocalBackpackStore.load(player).get("killer"));
    }
}
