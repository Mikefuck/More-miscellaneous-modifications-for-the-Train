package com.habitrain.lottery.bridge;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.api.skin.SkinSounds;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@code I-13}: server-side {@link InventorySkinApplier} must mirror
 * {@link DisguiseIdentityBridge} (impersonated target's skin/sound on {@code DISGUISED_AS},
 * stripped components on {@code HIDDEN_ANONYMOUS}) so disguised or fixed-skin killers never
 * leak their personal weapon skins or custom sounds to observers.
 */
class InventorySkinApplierDisguiseTest {
    private static final String PROVIDER = "test_disguise_skin_provider";

    @TempDir
    Path temp;

    private final UUID killerUuid = UUID.randomUUID();
    private final UUID victimUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(temp);

        String prev = HabiSkinApi.beginProvider(PROVIDER);
        try {
            HabiSkinApi.register(SkinDefinition.builder("knife", "killer_signature", SkinQuality.RED).build());
            SkinSounds.register("knife", "killer_signature");
            HabiSkinApi.register(SkinDefinition.builder("knife", "victim_blade", SkinQuality.BLUE).build());
            SkinSounds.register("knife", "victim_blade");
        } finally {
            HabiSkinApi.endProvider(prev);
        }

        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(killerUuid, d -> {
            d.unlocked.computeIfAbsent("knife", k -> new HashMap<>()).put("killer_signature", true);
            d.equipped.put("knife", "killer_signature");
        }));
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(victimUuid, d -> {
            d.unlocked.computeIfAbsent("knife", k -> new HashMap<>()).put("victim_blade", true);
            d.equipped.put("knife", "victim_blade");
            d.disabledSkinSounds.add("knife/victim_blade");
        }));
    }

    @AfterEach
    void tearDown() {
        HabiSkinApi.removeProvider(PROVIDER);
        SkinSounds.removeProvider(PROVIDER);
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void selfAppliesRealOwnerSkinWhileAnonymousStripsAndDisguiseMirrorsVictim() {
        var self = InventorySkinApplier.resolveTargetComponents(
                "knife", null, killerUuid, DisguiseIdentityBridge.Resolution.self());
        assertEquals("knife/killer_signature", self.skinEntry());
        assertNull(self.customSound());

        var hidden = InventorySkinApplier.resolveTargetComponents(
                "knife", null, killerUuid, DisguiseIdentityBridge.Resolution.hidden());
        assertNull(hidden.skinEntry(), "HIDDEN_ANONYMOUS must strip equipped skin component");
        assertNull(hidden.customSound(), "HIDDEN_ANONYMOUS must strip custom sound component");

        var disguised = InventorySkinApplier.resolveTargetComponents(
                "knife", null, killerUuid, DisguiseIdentityBridge.Resolution.disguisedAs(victimUuid));
        assertEquals("knife/victim_blade", disguised.skinEntry(),
                "DISGUISED_AS must mirror the impersonated target's equipped skin");
        assertEquals(Boolean.FALSE, disguised.customSound(),
                "DISGUISED_AS must mirror the impersonated target's sound preference");

        UUID skinlessVictim = UUID.randomUUID();
        var disguisedSkinless = InventorySkinApplier.resolveTargetComponents(
                "knife", null, killerUuid, DisguiseIdentityBridge.Resolution.disguisedAs(skinlessVictim));
        assertNull(disguisedSkinless.skinEntry(),
                "DISGUISED_AS targeting a player with no skin must strip the killer's own skin");
    }
}
