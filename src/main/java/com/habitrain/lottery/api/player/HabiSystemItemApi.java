package com.habitrain.lottery.api.player;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread API for persistent virtual rewards. No physical items are created. */
public final class HabiSystemItemApi {
    public static final int API_VERSION = 1;
    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
    private HabiSystemItemApi() {}

    /** Name/description accept either translation keys or literal text; icon is an item registry id. */
    public record Definition(ResourceLocation id, String name, String description, ResourceLocation icon, int color) {
        public Definition {
            if (id == null || id.toString().length() > 128 || name == null || name.isBlank()
                    || name.length() > 256 || description == null || description.length() > 1024 || icon == null)
                throw new IllegalArgumentException("Invalid system item definition");
        }
    }

    /** Register once during mod initialization. Duplicate ids are rejected. */
    public static void register(Definition definition) {
        if (DEFINITIONS.putIfAbsent(definition.id().toString(), definition) != null)
            throw new IllegalArgumentException("Duplicate system item: " + definition.id());
    }

    public static Definition definition(String id) { return DEFINITIONS.get(id); }

    public static Map<String, Integer> balances(UUID uuid) {
        if (uuid == null || !WorldLotteryPaths.ready()) return Map.of();
        var store = PlayerLotteryStore.get();
        var data = store.getOrLoad(uuid);
        return store.isLoadFailed(uuid) ? Map.of() : Map.copyOf(data.systemItems);
    }

    /** Grants a positive quantity and writes it before returning success. Unknown ids retain their balances. */
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
        if (!WorldLotteryPaths.ready() || !PlayerLotteryStore.get().isTakeoverActive())
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        var server = HabiLotteryApi.server();
        if (server != null && !server.isSameThread()) return HabiAssetResult.fail(HabiFailure.INVALID_OPERATION);
        var store = PlayerLotteryStore.get();
        if (store.isFlushDeferred()) return HabiAssetResult.fail(HabiFailure.NOT_READY);
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
