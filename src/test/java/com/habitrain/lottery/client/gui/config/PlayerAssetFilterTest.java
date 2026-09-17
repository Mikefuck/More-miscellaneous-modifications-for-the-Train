package com.habitrain.lottery.client.gui.config;

import com.habitrain.lottery.network.PlayerAdminModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerAssetFilterTest {
    @Test
    void filterMatchesNameOrUuidCaseInsensitively() {
        PlayerAdminModels.PlayerRow mike = row("Mike", "11111111-1111-1111-1111-111111111111");
        PlayerAdminModels.PlayerRow alex = row("Alex", "22222222-2222-2222-2222-222222222222");

        assertEquals(List.of(mike), PlayerAssetFilter.filter(List.of(mike, alex), "mIk"));
        assertEquals(List.of(mike), PlayerAssetFilter.filter(List.of(mike, alex), "1111"));
        assertEquals(List.of(mike, alex), PlayerAssetFilter.filter(List.of(mike, alex), "  "));
    }

    @Test
    void selectedIndexIsResolvedByUuidAfterFiltering() {
        PlayerAdminModels.PlayerRow mike = row("Mike", "11111111-1111-1111-1111-111111111111");
        PlayerAdminModels.PlayerRow alex = row("Alex", "22222222-2222-2222-2222-222222222222");
        List<PlayerAdminModels.PlayerRow> rows = PlayerAssetFilter.filter(List.of(mike, alex), "alex");

        assertEquals(0, PlayerAssetFilter.indexOfUuid(rows, alex.uuid));
        assertEquals(-1, PlayerAssetFilter.indexOfUuid(rows, mike.uuid));
    }

    private static PlayerAdminModels.PlayerRow row(String name, String uuid) {
        return new PlayerAdminModels.PlayerRow(name, UUID.fromString(uuid), 0, 0, 0, false);
    }
}
