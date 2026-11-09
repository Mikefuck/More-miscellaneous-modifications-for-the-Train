package com.habitrain.lottery.daily;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.testutil.TestRegistryResetExtension;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression coverage for {@link HabiDailyTaskApi}: null-id safety (Audit I-19), unmodifiable
 * board snapshots (Audit N-05), configured task shadowing, and test isolation (Audit I-25).
 */
@ExtendWith(TestRegistryResetExtension.class)
class DailyTaskApiTest {

    @Test
    void nullPlayerOrNullTaskIdReturnsSafeDefaultsWithoutThrowingNpe() {
        ResourceLocation taskId = ResourceLocation.parse("habitrain_lottery:test_task");
        assertEquals(0, HabiDailyTaskApi.progress(null, null));
        assertEquals(0, HabiDailyTaskApi.progress(null, taskId));
        assertFalse(HabiDailyTaskApi.claimed(null, null));
        assertFalse(HabiDailyTaskApi.claimed(null, taskId));
        assertFalse(HabiDailyTaskApi.advance(null, null, 1));
        assertFalse(HabiDailyTaskApi.advance(null, taskId, 1));
        assertFalse(HabiDailyTaskApi.claim(null, null));
    }

    @Test
    void configuredTasksShadowStaticOnesAndReturnedCollectionsAreImmutable() {
        ResourceLocation sharedId = ResourceLocation.parse("habitrain_lottery:shared_daily");
        ResourceLocation staticOnlyId = ResourceLocation.parse("habitrain_lottery:static_daily");
        ResourceLocation inactiveCfgId = ResourceLocation.parse("habitrain_lottery:inactive_daily");

        HabiDailyTaskApi.Task staticShared = new HabiDailyTaskApi.Task(
                sharedId, "Static Title", "Static Desc", 3, "5 Apples", (p, id, day) -> true);
        HabiDailyTaskApi.Task staticOnly = new HabiDailyTaskApi.Task(
                staticOnlyId, "Static Only", "Desc", 1, "1 Apple", (p, id, day) -> true);
        HabiDailyTaskApi.register(staticShared);
        HabiDailyTaskApi.register(staticOnly);

        HabiDailyTaskApi.Task configuredOverride = new HabiDailyTaskApi.Task(
                sharedId, "Configured Override", "Override Desc", 10, "50 Apples", (p, id, day) -> true);
        HabiDailyTaskApi.Task configuredInactive = new HabiDailyTaskApi.Task(
                inactiveCfgId, "Inactive Pool Task", "Pool Desc", 5, "20 Apples", (p, id, day) -> true);

        HabiDailyTaskApi.setConfiguredTasks(
                List.of(configuredOverride, configuredInactive),
                (player, id) -> !inactiveCfgId.equals(id)
        );

        Map<ResourceLocation, HabiDailyTaskApi.Task> allTasks = HabiDailyTaskApi.tasks();
        assertEquals(3, allTasks.size());
        assertEquals("Configured Override", allTasks.get(sharedId).title());
        assertThrows(UnsupportedOperationException.class, () -> allTasks.remove(sharedId));

        List<HabiDailyTaskApi.Task> board = HabiDailyTaskApi.tasksFor(null);
        assertEquals(List.of(staticOnly, configuredOverride), board);
        assertThrows(UnsupportedOperationException.class, () -> board.add(configuredInactive));
    }
}
