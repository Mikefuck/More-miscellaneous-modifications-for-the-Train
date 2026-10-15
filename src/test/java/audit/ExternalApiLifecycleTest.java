package audit;

import com.google.gson.JsonParser;
import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.*;
import com.habitrain.lottery.api.skin.*;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.TitleCatalog;
import com.habitrain.lottery.title.TitlePaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Public facades called from a foreign package; storage hooks only establish isolated worlds. */
class ExternalApiLifecycleTest {
    @TempDir Path root;
    private final UUID player = UUID.randomUUID();
    private static final String SKIN = "api_lifecycle_test";
    private static final ResourceLocation ITEM = ResourceLocation.parse("audit:lifecycle_ticket");

    @BeforeEach void setup() {
        WorldLotteryPaths.clear();
        start(root);
        HabiSkinApi.register(SkinDefinition.builder("gun", SKIN, SkinQuality.PURPLE)
                .model("audit", "item/lifecycle_test").build());
        SkinSounds.register("gun", SKIN);
    }

    @AfterEach void cleanup() {
        WorldLotteryPaths.clear();
        HabiSkinApi.unregister("gun", SKIN);
    }

    private void start(Path world) {
        WorldLotteryPaths.initForTests(world);
        PlayerLotteryStore.get().onServerStarted(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"currency", "skin_access", "skin_list", "all_skins",
            "equipped", "all_equipped", "sound", "system_items", "snapshot", "store"})
    void preWorldReadsDoNotReplaceStoredAssets(String query) throws Exception {
        assertTrue(HabiLotteryApi.setGreenApples(player, 100).ok());
        assertTrue(HabiSkinPlayerApi.unlock(player, "gun", SKIN).ok());
        assertTrue(HabiSkinPlayerApi.equip(player, "gun", SKIN).ok());
        assertTrue(HabiSkinPlayerApi.setCustomSoundEnabled(player, "gun", SKIN, false).ok());
        assertTrue(HabiSystemItemApi.grant(player, ITEM, 3).ok());
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, data -> {
            data.rewardReceipts.add("old-reward");
            data.dailyTaskProgress.put("audit:old_task", 4);
        }));
        WorldLotteryPaths.clear();

        switch (query) {
            case "currency" -> assertEquals(0, HabiLotteryApi.getGreenApples(player));
            case "skin_access" -> assertFalse(HabiSkinPlayerApi.isUnlocked(player, "gun", SKIN));
            case "skin_list" -> assertTrue(HabiSkinPlayerApi.unlocked(player, "gun").isEmpty());
            case "all_skins" -> assertTrue(HabiSkinPlayerApi.unlockedAll(player).isEmpty());
            case "equipped" -> assertEquals("default", HabiSkinPlayerApi.equipped(player, "gun"));
            case "all_equipped" -> assertTrue(HabiSkinPlayerApi.equippedAll(player).isEmpty());
            case "sound" -> assertTrue(HabiSkinPlayerApi.isCustomSoundEnabled(player, "gun", SKIN));
            case "system_items" -> assertTrue(HabiSystemItemApi.balances(player).isEmpty());
            case "snapshot" -> assertEquals(0, HabiLotteryApi.snapshot(player).greenApples());
            case "store" -> {
                var transientData = PlayerLotteryStore.get().getOrLoad(player);
                transientData.greenApples = 777;
                assertEquals(0, PlayerLotteryStore.get().getGreenApples(player), "No pre-world cache");
                assertFalse(PlayerLotteryStore.get().applyToPlayerWithRollback(player,
                        data -> fail("An unbound world cannot run a transaction")));
            }
            default -> fail(query);
        }
        assertEquals(HabiFailure.NOT_READY, HabiLotteryApi.addGreenApples(player, 25).failure());
        start(root);
        assertEquals(100, HabiLotteryApi.getGreenApples(player));
        assertEquals(125, HabiLotteryApi.addGreenApples(player, 25).newValue());
        WorldLotteryPaths.clear();
        start(root);
        assertEquals(125, HabiLotteryApi.getGreenApples(player));
        assertTrue(HabiSkinPlayerApi.isUnlocked(player, "gun", SKIN));
        assertEquals(SKIN, HabiSkinPlayerApi.equipped(player, "gun"));
        assertFalse(HabiSkinPlayerApi.isCustomSoundEnabled(player, "gun", SKIN));
        assertEquals(3, HabiSystemItemApi.balances(player).get(ITEM.toString()));
        var stored = JsonParser.parseString(Files.readString(WorldLotteryPaths.playerFile(player))).getAsJsonObject();
        assertEquals(125, stored.get("greenApples").getAsInt());
        assertTrue(stored.getAsJsonArray("rewardReceipts").toString().contains("old-reward"));
        assertEquals(4, stored.getAsJsonObject("dailyTaskProgress").get("audit:old_task").getAsInt());
    }

    @Test void earlyTitleReadsPreserveOwnershipCurrentAndReceiptsAfterGrant() throws Exception {
        assertTrue(HabiTitleApi.grant(player, "old-title").ok());
        assertTrue(HabiTitleApi.setCurrent(player, "old-title").ok());
        assertTrue(LocalTitleStore.get().grantOnce(player, "old-title-reward", List.of("old-title")));
        WorldLotteryPaths.clear();
        assertTrue(HabiTitleApi.owned(player).isEmpty());
        assertEquals("", HabiTitleApi.current(player));
        var temporary = LocalTitleStore.get().loadPlayer(player);
        temporary.owned.add("temporary");
        assertTrue(LocalTitleStore.get().loadPlayer(player).owned.isEmpty());
        assertEquals(HabiFailure.NOT_READY, HabiTitleApi.grant(player, "new-title").failure());
        start(root);
        assertEquals(List.of("old-title"), HabiTitleApi.owned(player));
        assertEquals("old-title", HabiTitleApi.current(player));
        assertTrue(HabiTitleApi.grant(player, "new-title").ok());
        LocalTitleStore.get().reset();
        assertEquals(List.of("old-title", "new-title"), HabiTitleApi.owned(player));
        assertEquals("old-title", HabiTitleApi.current(player));
        assertTrue(Files.readString(TitlePaths.playerFile(player)).contains("old-title-reward"));
    }

    @Test void preWorldCatalogReadDoesNotHideSavedDefinitions() {
        var catalog = new TitleCatalog();
        catalog.titles.add(new TitleCatalog.TitleEntry("old", "Old", true));
        assertTrue(LocalTitleStore.get().saveCatalogChecked(catalog));
        WorldLotteryPaths.clear();
        assertTrue(LocalTitleStore.get().loadCatalog().titles.isEmpty());
        start(root);
        assertEquals("old", LocalTitleStore.get().loadCatalog().titles.getFirst().id);
    }

    @Test void rebindingWorldDropsAssetsDirtyFlagsAndTitleCatalog() {
        assertTrue(HabiLotteryApi.setGreenApples(player, 100).ok());
        assertTrue(HabiTitleApi.grant(player, "world-a").ok());
        var catalog = new TitleCatalog();
        catalog.titles.add(new TitleCatalog.TitleEntry("a", "A", true));
        assertTrue(LocalTitleStore.get().saveCatalogChecked(catalog));
        PlayerLotteryStore.get().setGreenApples(player, 999); // old-world unflushed cache

        start(root.resolve("second-world"));
        assertFalse(PlayerLotteryStore.get().isDirty(player));
        assertEquals(0, HabiLotteryApi.getGreenApples(player));
        assertTrue(HabiTitleApi.owned(player).isEmpty());
        assertTrue(LocalTitleStore.get().loadCatalog().titles.isEmpty());
        assertTrue(HabiLotteryApi.setGreenApples(player, 7).ok());
        assertTrue(HabiTitleApi.grant(player, "world-b").ok());

        start(root);
        assertEquals(100, HabiLotteryApi.getGreenApples(player));
        assertEquals(List.of("world-a"), HabiTitleApi.owned(player));
        assertEquals("a", LocalTitleStore.get().loadCatalog().titles.getFirst().id);
    }

    @Test void unregisteredSystemItemsCanBeGrantedReloadedAndConsumed() {
        assertNull(HabiSystemItemApi.definition(ITEM.toString()));
        assertTrue(HabiSystemItemApi.grant(player, ITEM, 3).ok());
        start(root);
        assertEquals(3, HabiSystemItemApi.balances(player).get(ITEM.toString()));
        assertTrue(HabiSystemItemApi.consume(player, ITEM, 3).ok());
        assertTrue(HabiSystemItemApi.balances(player).isEmpty());
    }

    @Test void publishedDailyApiVersionMatchesImplementation() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
            assertNotNull(stream);
            var metadata = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("habitrain_lottery", metadata.get("id").getAsString());
            assertEquals(HabiDailyTaskApi.API_VERSION,
                    metadata.getAsJsonObject("custom").getAsJsonObject("habitrain_lottery:daily_api")
                            .get("version").getAsInt());
        }
    }
}
