package com.habitrain.lottery.api.player;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-thread API for persistent virtual rewards. No physical items are created. */
public final class HabiSystemItemApi {
    public static final int API_VERSION = 1;
    private static final Object LOCK = new Object();
    private static volatile Map<String, Definition> DEFINITIONS = Map.of();
    private HabiSystemItemApi() {}

    /** Name/description accept either translation keys or literal text; icon is an item registry id. */
    public record Definition(ResourceLocation id, String name, String description, ResourceLocation icon, int color) {
        public Definition {
            if (id == null || id.toString().length() > 128 || name == null || name.isBlank()
                    || name.length() > 256 || description == null || description.length() > 1024 || icon == null)
                throw new IllegalArgumentException("Invalid system item definition");
        }
    }

    /** Register display metadata once during mod initialization. Duplicate ids are rejected. */
    public static void register(Definition definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (LOCK) {
            String key = definition.id().toString();
            if (DEFINITIONS.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate system item: " + definition.id());
            }
            Map<String, Definition> next = new LinkedHashMap<>(DEFINITIONS);
            next.put(key, definition);
            DEFINITIONS = Collections.unmodifiableMap(next);
        }
    }

    /** World-owned definitions may change after initialization; balances keep their stable ids. */
    public static void replaceWorldDefinitions(Map<String, Definition> definitions) {
        synchronized (LOCK) {
            Map<String, Definition> next = new LinkedHashMap<>(DEFINITIONS);
            next.keySet().removeIf(id -> id.startsWith("habitrain_lottery:crate_")
                    || id.startsWith("habitrain_lottery:key_"));
            if (definitions != null) {
                for (Map.Entry<String, Definition> entry : definitions.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        next.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            DEFINITIONS = Collections.unmodifiableMap(next);
        }
    }

    public static Definition definition(String id) {
        return id == null ? null : DEFINITIONS.get(id);
    }

    /** Immutable snapshot of registered system item definitions in registration order. */
    public static Map<String, Definition> definitions() {
        return DEFINITIONS;
    }

    public static void clearForTest() {
        synchronized (LOCK) {
            DEFINITIONS = Map.of();
        }
    }

    public static Map<String, Integer> balances(UUID uuid) {
        if (uuid == null || !ApiGuards.isOnServerThread() || !WorldLotteryPaths.ready()) return Map.of();
        var store = PlayerLotteryStore.get();
        var data = store.getOrLoad(uuid);
        return store.isLoadFailed(uuid) ? Map.of() : Map.copyOf(data.systemItems);
    }

    /**
     * Grants a positive quantity and writes it before returning success, including unregistered ids.
     * Registration supplies display metadata; it is not an issuance allowlist.
     */
    public static HabiAssetResult grant(UUID uuid, ResourceLocation id, int amount) {
        return amount <= 0 ? HabiAssetResult.fail(HabiFailure.INVALID_VALUE) : change(uuid, id, amount);
    }

    /** Consumes exactly amount, failing without mutation if insufficient. Call from the owning reward service. */
    public static HabiAssetResult consume(UUID uuid, ResourceLocation id, int amount) {
        return amount <= 0 ? HabiAssetResult.fail(HabiFailure.INVALID_VALUE) : change(uuid, id, -amount);
    }

    private static HabiAssetResult change(UUID uuid, ResourceLocation id, int delta) {
        if (uuid == null) return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        if (id == null || id.toString().length() > 128) return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        HabiFailure guard = ApiGuards.checkServerThreadAndReady();
        if (guard != null) return HabiAssetResult.fail(guard);
        var store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive()) return HabiAssetResult.fail(HabiFailure.NOT_READY);
        var data = store.getOrLoad(uuid);
        if (store.isLoadFailed(uuid)) return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        var next = new LinkedHashMap<>(data.systemItems);
        String key = id.toString();
        if (!SystemItemBalances.change(next, key, delta)) return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        boolean saved = store.applyToPlayerWithRollback(uuid, d -> d.systemItems = next);
        return saved ? HabiAssetResult.success(next.getOrDefault(key, 0))
                : HabiAssetResult.fail(HabiFailure.WRITE_FAILED);
    }
}
