package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSelectCandidateTest {
    private static final Gson GSON = new Gson();

    @Test
    void deserializesRoleCandidatesFromJson() {
        String json = """
                [
                  {"id":"starrailexpress:sheriff","name":"announcement.star.role.sheriff","color":16766720,"bound":""},
                  {"id":"starrailexpress:deputy","name":"announcement.star.role.deputy","color":16766720,"bound":"starrailexpress:sheriff"}
                ]
                """;

        Type type = new TypeToken<List<WarehouseRole>>() {}.getType();
        List<WarehouseRole> candidates = GSON.fromJson(json, type);

        assertNotNull(candidates);
        assertEquals(2, candidates.size());

        WarehouseRole sheriff = candidates.get(0);
        assertEquals("starrailexpress:sheriff", sheriff.id);
        assertNotNull(sheriff.displayName());
        assertTrue(!sheriff.displayName().isBlank());

        WarehouseRole deputy = candidates.get(1);
        assertEquals("starrailexpress:deputy", deputy.id);
        assertTrue(deputy.isBound());
        assertEquals("starrailexpress:sheriff", deputy.bound);
    }

    @Test
    void resolvesRoleDisplayNameFallbackWhenNoLanguage() {
        // When running in headless test environment without language loaded,
        // it gracefully falls back without crashing and returns a readable name.
        String name = WarehouseRole.resolveRoleDisplayName("starrailexpress:sheriff", "announcement.star.role.sheriff");
        assertNotNull(name);
        assertTrue(!name.isBlank());

        String boundName = WarehouseRole.resolveBoundRoleDisplayName("starrailexpress:deputy");
        assertNotNull(boundName);
        assertTrue(!boundName.isBlank());
    }
}
