package com.habitrain.lottery.api.player;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.testutil.TestRegistryResetExtension;
import com.habitrain.lottery.title.TitlePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for Audit I-19, I-20, N-05, and N-11:
 * positive mail reward amounts, server-thread & deferred-flush guards, {@code CORRUPT_STORAGE}
 * on {@link HabiTitleApi#setCurrent} when clearing, and unmodifiable collection returns.
 */
@ExtendWith(TestRegistryResetExtension.class)
class PublicApiContractAndImmutabilityTest {

    @TempDir
    Path temp;

    @BeforeEach
    void setup() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTest(temp);
        PlayerLotteryStore.get().onServerStarted(null);
    }

    @AfterEach
    void cleanup() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void mailApiRejectsNonPositiveRewardAmountsAndDraftFiltersRawNonPositiveRewards() {
        for (int bad : new int[] {0, -1, -500}) {
            assertThrows(IllegalArgumentException.class, () -> HabiMailApi.greenApples(bad));
            assertThrows(IllegalArgumentException.class,
                    () -> HabiMailApi.greenApples("System", "Title", "Body", bad));
            assertThrows(IllegalArgumentException.class,
                    () -> HabiMailApi.factionCard(HabiCardKind.CIVILIAN, bad));
            assertThrows(IllegalArgumentException.class, () -> HabiMailApi.selfSelectCard(bad));
            assertThrows(IllegalArgumentException.class, () -> HabiMailApi.limitBreakCard(bad));
            assertThrows(IllegalArgumentException.class, () -> HabiMailApi.crate("woodland", bad));
            assertThrows(IllegalArgumentException.class, () -> HabiMailApi.key("woodland", bad));
        }

        // Virtual kinds return null on factionCard without throwing
        assertNull(HabiMailApi.factionCard(HabiCardKind.SELF_SELECT, 1));
        assertNull(HabiMailApi.factionCard(null, 1));

        // Direct MailReward with <= 0 passed into HabiMailApi.draft is filtered out
        MailDraft filtered = HabiMailApi.draft("Admin", "Test", "Body", List.of(
                new MailReward(MailReward.Kind.GREEN_APPLES, 0, null),
                new MailReward(MailReward.Kind.GREEN_APPLES, -10, null),
                HabiMailApi.greenApples(25)
        ));
        assertEquals(1, filtered.rewards().size());
        assertEquals(25, filtered.rewards().getFirst().amount());
    }

    @Test
    void titleApiSetCurrentClearingReportsCorruptStorageInsteadOfWriteFailed() throws Exception {
        UUID player = UUID.randomUUID();
        TitlePaths.ensureDirs();
        Path playerTitleFile = TitlePaths.playerFile(player);
        Files.writeString(playerTitleFile, "{corrupt json!!!");

        assertEquals(HabiFailure.CORRUPT_STORAGE, HabiTitleApi.setCurrent(player, "").failure());
        assertEquals(HabiFailure.CORRUPT_STORAGE, HabiTitleApi.setCurrent(player, null).failure());
        assertEquals(HabiFailure.CORRUPT_STORAGE, HabiTitleApi.setCurrent(player, "Champion").failure());
    }

    @Test
    void offThreadAndDeferredFlushGuardsProtectPublicPlayerApis() {
        UUID player = UUID.randomUUID();
        HabiSkinApi.register(SkinDefinition.builder("back", "cape_one", 0xFF123456)
                .model("habitrain_lottery", "item/test/cape_one")
                .build());
        assertTrue(HabiLotteryApi.setGreenApples(player, 50).ok());

        // 1. Off-thread invocations are rejected with INVALID_OPERATION (and safe empty reads)
        Thread fakeServerThread = new Thread(() -> { }, "fake-server-thread");
        ApiGuards.setServerThreadForTest(fakeServerThread);
        try {
            assertEquals(0, HabiLotteryApi.getGreenApples(player));
            assertEquals(HabiFailure.INVALID_OPERATION, HabiLotteryApi.addGreenApples(player, 10).failure());
            assertEquals(HabiFailure.INVALID_OPERATION,
                    HabiCardApi.add(player, HabiCardKind.SELF_SELECT, 1).failure());
            assertEquals(HabiFailure.INVALID_OPERATION,
                    HabiSkinPlayerApi.unlock(player, "back", "cape_one").failure());
            assertEquals(HabiFailure.INVALID_OPERATION, HabiTitleApi.grant(player, "Hero").failure());
            assertFalse(HabiMailApi.send(player, HabiMailApi.greenApples("Sys", "Hi", "Body", 5)));
        } finally {
            ApiGuards.setServerThreadForTest(null);
        }

        // Original balance untouched
        assertEquals(50, HabiLotteryApi.getGreenApples(player));

        // 2. Deferred flush window rejects durable API mutations with NOT_READY
        PlayerLotteryStore.get().beginDeferredFlush();
        try {
            assertEquals(HabiFailure.NOT_READY, HabiLotteryApi.addGreenApples(player, 10).failure());
            assertEquals(HabiFailure.NOT_READY,
                    HabiCardApi.add(player, HabiCardKind.SELF_SELECT, 1).failure());
            assertEquals(HabiFailure.NOT_READY,
                    HabiSkinPlayerApi.unlock(player, "back", "cape_one").failure());
            assertEquals(HabiFailure.NOT_READY, HabiTitleApi.grant(player, "Hero").failure());
            assertFalse(HabiMailApi.send(player, HabiMailApi.greenApples("Sys", "Hi", "Body", 5)));
        } finally {
            PlayerLotteryStore.get().endDeferredFlush();
        }
    }

    @Test
    void returnedMapsAndListsAreStrictlyUnmodifiable() {
        UUID player = UUID.randomUUID();
        HabiSkinApi.register(SkinDefinition.builder("hat", "crown", 0xFFFFD700)
                .model("habitrain_lottery", "item/test/crown")
                .build());
        assertTrue(HabiSkinPlayerApi.unlock(player, "hat", "crown").ok());
        assertTrue(HabiSkinPlayerApi.equip(player, "hat", "crown").ok());
        assertTrue(HabiTitleApi.grant(player, "Pioneer").ok());

        SkinItems.bindItem(ResourceLocation.parse("immut_test:dagger"), "knife");
        HabiSystemItemApi.register(new HabiSystemItemApi.Definition(
                ResourceLocation.parse("immut_test:coin"),
                "Coin", "Test coin", ResourceLocation.parse("minecraft:gold_nugget"), 0xFFCC00));
        HabiDailyTaskApi.register(new HabiDailyTaskApi.Task(
                ResourceLocation.parse("immut_test:login"),
                "Login", "Daily login", 1, "10 Apples", (p, id, day) -> true));

        Map<String, Integer> cards = HabiCardApi.all(player);
        assertThrows(UnsupportedOperationException.class, () -> cards.put("civilian", 999));

        List<String> unlockedHats = HabiSkinPlayerApi.unlocked(player, "hat");
        assertThrows(UnsupportedOperationException.class, () -> unlockedHats.add("hacked"));

        Map<String, List<String>> unlockedAll = HabiSkinPlayerApi.unlockedAll(player);
        assertThrows(UnsupportedOperationException.class, () -> unlockedAll.put("knife", List.of("x")));
        assertThrows(UnsupportedOperationException.class, () -> unlockedAll.get("hat").add("x"));

        Map<String, String> equippedAll = HabiSkinPlayerApi.equippedAll(player);
        assertThrows(UnsupportedOperationException.class, () -> equippedAll.put("knife", "x"));

        List<String> titles = HabiTitleApi.owned(player);
        assertThrows(UnsupportedOperationException.class, () -> titles.add("Admin"));

        assertThrows(UnsupportedOperationException.class,
                () -> SkinItems.bindings().put(ResourceLocation.parse("immut_test:other"), "bat"));
        assertThrows(UnsupportedOperationException.class,
                () -> HabiSystemItemApi.definitions().remove("immut_test:coin"));
        assertThrows(UnsupportedOperationException.class,
                () -> HabiDailyTaskApi.tasksFor(null).clear());
    }
}
