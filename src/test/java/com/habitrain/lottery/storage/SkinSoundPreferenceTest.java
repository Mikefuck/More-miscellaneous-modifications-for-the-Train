package com.habitrain.lottery.storage;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinSounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkinSoundPreferenceTest {
    private static final String ID = "sound_preference_test";
    @TempDir Path temp;

    @BeforeEach void prepare() {
        WorldLotteryPaths.initForTests(temp);
        HabiSkinApi.register(SkinDefinition.builder("revolver", ID, 0).build());
        SkinSounds.register("revolver", ID);
    }

    @AfterEach void cleanup() {
        PlayerLotteryStore.get().reset(); WorldLotteryPaths.clear();
        HabiSkinApi.unregister("revolver", ID);
    }

    @Test void oldSavesDefaultOnAndShooterChoicesSurviveReloadAndCopies() throws Exception {
        UUID shooter = UUID.randomUUID(), other = UUID.randomUUID();
        Files.writeString(WorldLotteryPaths.playerFile(shooter), "{\"greenApples\":10}");
        var store = PlayerLotteryStore.get();
        assertTrue(store.isCustomSoundEnabled(shooter, "gun", ID));
        assertTrue(store.commitCustomSound(shooter, "gun", ID, false));
        assertTrue(store.isCustomSoundEnabled(other, "revolver", ID));
        assertFalse(store.isCustomSoundEnabled(shooter, "revolver", ID));
        var copy = store.getOrLoad(shooter).copy();
        assertTrue(store.commitCustomSound(shooter, "revolver", ID, true));
        assertTrue(copy.disabledSkinSounds.contains("revolver/" + ID));
        assertTrue(store.commitCustomSound(shooter, "revolver", ID, false));
        store.reset();
        assertFalse(store.isCustomSoundEnabled(shooter, "gun", ID));
        assertEquals(10, store.getGreenApples(shooter));
    }

    @Test void failedAndDeferredWritesDoNotApplyPreferences() throws Exception {
        UUID shooter = UUID.randomUUID();
        var store = PlayerLotteryStore.get();
        store.getOrLoad(shooter);
        store.beginDeferredFlush();
        try { assertFalse(store.commitCustomSound(shooter, "revolver", ID, false)); }
        finally { store.endDeferredFlush(); }
        assertTrue(store.isCustomSoundEnabled(shooter, "revolver", ID));
        Files.createDirectory(WorldLotteryPaths.playerFile(shooter));
        assertFalse(store.commitCustomSound(shooter, "revolver", ID, false));
        assertTrue(store.isCustomSoundEnabled(shooter, "revolver", ID));
        assertFalse(store.commitCustomSound(shooter, "revolver", "unregistered_sound", false));
    }

    @Test void soundDeclarationsNormalizeAliasesAndRollBackWithProvider() {
        String provider = "sound_provider_test";
        String previous = HabiSkinApi.beginProvider(provider);
        try {
            HabiSkinApi.register(SkinDefinition.builder("revolver", "sound_rollback_test", 0).build());
            SkinSounds.register(" Gun ", " SOUND_ROLLBACK_TEST ");
            SkinSounds.register("revolver", "sound_rollback_test");
        } finally { HabiSkinApi.endProvider(previous); }
        assertTrue(SkinSounds.supports("gun", "sound_rollback_test"));
        assertEquals(1, SkinSounds.removeProvider(provider));
        assertFalse(SkinSounds.supports("revolver", "sound_rollback_test"));
        HabiSkinApi.removeProvider(provider);
    }

    @Test void removingARegisteredSkinAlsoRemovesItsSoundDeclaration() {
        assertTrue(SkinSounds.supports("revolver", ID));
        HabiSkinApi.unregister("revolver", ID);
        HabiSkinApi.register(SkinDefinition.builder("revolver", ID, 0).build());
        assertFalse(SkinSounds.supports("revolver", ID));
    }
}
