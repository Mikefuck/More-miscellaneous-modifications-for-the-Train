package com.habitrain.lottery.api.skin;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.testutil.TestRegistryResetExtension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Concurrency stress coverage for Audit B-17: lock-free Copy-on-Write snapshots on
 * {@link SkinItems} and {@link HabiSystemItemApi} plus unified {@link SkinRegistrationTransaction}
 * rollback locking under {@code HabiSkinApi.REGISTRY_LOCK}.
 */
@ExtendWith(TestRegistryResetExtension.class)
class SkinRegistryConcurrencyStressTest {

    @Test
    @Timeout(20)
    void concurrentReadersAndTransactionalRollbacksNeverRaceOrThrowCme() throws Exception {
        ResourceLocation stableItem = ResourceLocation.parse("stress_test:stable_sword");
        SkinItems.bindItem(stableItem, "knife");

        ResourceLocation stableSysId = ResourceLocation.parse("stress_test:perm_token");
        HabiSystemItemApi.register(new HabiSystemItemApi.Definition(
                stableSysId, "Perm Token", "Permanent fixture", ResourceLocation.parse("minecraft:paper"), 0xFFAA00));

        int readerCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 2);
        CountDownLatch ready = new CountDownLatch(readerCount + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int r = 0; r < readerCount; r++) {
            final int readerIdx = r;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (running.get()) {
                        assertEquals("knife", SkinItems.boundType(stableItem));
                        assertNull(SkinItems.typeOf(null));
                        for (Map.Entry<ResourceLocation, String> entry : SkinItems.bindings().entrySet()) {
                            assertNotNull(entry.getKey());
                            assertNotNull(entry.getValue());
                        }

                        assertNotNull(HabiSystemItemApi.definition(stableSysId.toString()));
                        for (Map.Entry<String, HabiSystemItemApi.Definition> entry : HabiSystemItemApi.definitions().entrySet()) {
                            assertNotNull(entry.getKey());
                            assertNotNull(entry.getValue());
                        }

                        for (SkinDefinition def : HabiSkinApi.registrations()) {
                            assertNotNull(def.id());
                        }
                        if ((readerIdx & 1) == 0) {
                            SkinEffects.entries().forEach(k -> assertNotNull(k));
                        } else {
                            SkinCardText.find("knife", "temp_skin_" + (readerIdx % 4));
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        // Writer 1: skin registrations + item bindings + transactional rollbacks
        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                for (int i = 0; i < 600; i++) {
                    final int index = i;
                    String provider = "stress_provider_" + (i % 8);
                    String skinId = "stress_skin_" + (i % 8);
                    ResourceLocation tempItem = ResourceLocation.parse("stress_test:temp_item_" + (i % 8));
                    boolean shouldRollback = (i % 2 == 1);
                    try {
                        SkinRegistrationTransaction.run(provider, () -> {
                            HabiSkinApi.removeProvider(provider);
                            SkinItems.bindItem(tempItem, "revolver");
                            HabiSkinApi.register(SkinDefinition.builder("revolver", skinId, 0xFF0000 + index)
                                    .model("habitrain_lottery", "item/test/" + skinId)
                                    .build());
                            SkinSounds.register("revolver", skinId);
                            SkinCardText.register("revolver", skinId, "Tag" + index);
                            SkinEffects.registerImpact("revolver", skinId, ctx -> { });
                            if (shouldRollback) {
                                throw new IllegalStateException("intentional rollback");
                            }
                        });
                        // Clean up committed binding via explicit removal of provider skin so next loop stays clean
                        HabiSkinApi.removeProvider(provider);
                    } catch (IllegalStateException expected) {
                        if (!"intentional rollback".equals(expected.getMessage())) {
                            throw expected;
                        }
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        // Writer 2: HabiSystemItemApi register + replaceWorldDefinitions
        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                ResourceLocation icon = ResourceLocation.parse("minecraft:emerald");
                for (int i = 0; i < 600; i++) {
                    if (i < 64) {
                        ResourceLocation dyn = ResourceLocation.parse("stress_test:dyn_" + i);
                        HabiSystemItemApi.register(new HabiSystemItemApi.Definition(
                                dyn, "Dyn " + i, "Desc", icon, 0x123456));
                    }
                    Map<String, HabiSystemItemApi.Definition> worldDefs = new LinkedHashMap<>();
                    for (int c = 0; c < 4; c++) {
                        String key = "habitrain_lottery:crate_stress_" + ((i + c) % 6);
                        worldDefs.put(key, new HabiSystemItemApi.Definition(
                                ResourceLocation.parse(key), "Crate " + c, "World crate", icon, 0xABCDEF));
                    }
                    HabiSystemItemApi.replaceWorldDefinitions(worldDefs);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        pool.shutdown();
        // Wait for both writers to complete by polling task count, then stop readers.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            Thread.sleep(25);
            // Once writers finish, active count drops to <= readerCount
            if (((java.util.concurrent.ThreadPoolExecutor) pool).getActiveCount() <= readerCount) {
                break;
            }
        }
        running.set(false);
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        List<Throwable> captured = new ArrayList<>(errors);
        assertEquals(List.of(), captured, "Concurrent stress run must produce zero exceptions");
        assertEquals("knife", SkinItems.boundType(stableItem));
        assertNotNull(HabiSystemItemApi.definition(stableSysId.toString()));
    }
}
