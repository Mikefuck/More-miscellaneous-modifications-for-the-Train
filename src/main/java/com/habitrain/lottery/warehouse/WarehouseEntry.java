package com.habitrain.lottery.warehouse;

import com.habitrain.lottery.api.skin.SkinQuality;

/** Only account rewards are represented here: no inventory slots, NBT or client-supplied balances. */
public record WarehouseEntry(String kind, String id, String name, String description, String icon,
                             int count, int color, boolean equipped, SkinQuality quality) {
    public WarehouseEntry {
        kind = bounded(kind, 24); id = bounded(id, 128); name = bounded(name, 256);
        description = bounded(description, 1024); icon = bounded(icon, 128);
        count = Math.max(0, count);
        quality = java.util.Objects.requireNonNull(quality, "quality");
    }
    public WarehouseEntry(String kind, String id, String name, String description, String icon,
                          int count, int color, boolean equipped) {
        this(kind, id, name, description, icon, count, color, equipped, SkinQuality.WHITE);
    }
    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(max, value.length()));
    }
}
