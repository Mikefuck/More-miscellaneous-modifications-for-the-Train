package com.habitrain.lottery.client.gui.config;

import com.habitrain.lottery.network.PlayerAdminModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure player filtering and stable UUID selection for the assets section. */
public final class PlayerAssetFilter {
    private PlayerAssetFilter() {
    }

    public static List<PlayerAdminModels.PlayerRow> filter(
            List<PlayerAdminModels.PlayerRow> source,
            String query
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.copyOf(source);
        }
        List<PlayerAdminModels.PlayerRow> result = new ArrayList<>();
        for (PlayerAdminModels.PlayerRow row : source) {
            if (row == null) {
                continue;
            }
            String name = row.name == null ? "" : row.name.toLowerCase(Locale.ROOT);
            String uuid = row.uuid == null ? "" : row.uuid.toLowerCase(Locale.ROOT);
            if (name.contains(needle) || uuid.contains(needle)) {
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    public static int indexOfUuid(List<PlayerAdminModels.PlayerRow> rows, String uuid) {
        if (rows == null || uuid == null || uuid.isBlank()) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            PlayerAdminModels.PlayerRow row = rows.get(i);
            if (row != null && uuid.equals(row.uuid)) {
                return i;
            }
        }
        return -1;
    }
}
