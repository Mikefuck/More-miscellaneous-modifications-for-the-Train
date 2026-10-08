package com.habitrain.lottery.storage;

import com.habitrain.lottery.api.player.HabiSkinPlayerApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.mail.MailCommandsCodec;
import com.habitrain.lottery.mail.MailReward;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackSkinStateTest {
    private static final String ID = "back_state_test";
    @TempDir Path temp;

    @BeforeEach void prepare() {
        WorldLotteryPaths.initForTests(temp);
        HabiSkinApi.register(SkinDefinition.builder("back", ID, 0)
                .model("example", "item/skins/back/test").build());
    }

    @AfterEach void cleanup() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        HabiSkinApi.unregister("back", ID);
    }

    @Test void publicApiEnforcesOwnershipAndPersistsAnIndependentBackSlot() {
        UUID player = UUID.randomUUID();
        assertFalse(HabiSkinPlayerApi.equip(player, "back", ID).ok());
        assertTrue(HabiSkinPlayerApi.unlock(player, "back", ID).ok());
        assertTrue(HabiSkinPlayerApi.equip(player, "habitrain_lottery:back", ID).ok());
        PlayerLotteryStore.get().reset();
        assertEquals(ID, HabiSkinPlayerApi.equipped(player, "back"));
        assertTrue(HabiSkinPlayerApi.isUnlocked(player, "back", ID));
        assertEquals("default", HabiSkinPlayerApi.equipped(player, "hat"));
        assertTrue(HabiSkinPlayerApi.clearEquipped(player, "back").ok());
        assertEquals("default", HabiSkinPlayerApi.equipped(player, "back"));
        assertTrue(HabiSkinPlayerApi.isUnlocked(player, "back", ID));
        assertTrue(HabiSkinPlayerApi.equip(player, "back", ID).ok());
        assertTrue(HabiSkinPlayerApi.lock(player, "back", ID).ok());
        PlayerLotteryStore.get().reset();
        assertEquals("default", HabiSkinPlayerApi.equipped(player, "back"));
        assertFalse(HabiSkinPlayerApi.isUnlocked(player, "back", ID));
    }

    @Test void failedWriteDoesNotApplyBackAppearance() throws Exception {
        UUID player = UUID.randomUUID();
        assertTrue(HabiSkinPlayerApi.unlock(player, "back", ID).ok());
        Path file = WorldLotteryPaths.playerFile(player);
        Files.delete(file);
        Files.createDirectory(file);
        assertFalse(HabiSkinPlayerApi.equip(player, "back", ID).ok());
        assertEquals("default", HabiSkinPlayerApi.equipped(player, "back"));
        assertTrue(HabiSkinPlayerApi.isUnlocked(player, "back", ID));
    }

    @Test void backSkinsUseExistingMailAttachments() {
        var rewards = List.of(MailReward.skin("back", ID));
        assertEquals(rewards, MailCommandsCodec.decode(MailCommandsCodec.encode(rewards)));
    }
}
