package com.habitrain.lottery.api.skin;

import com.habitrain.lottery.HabiLotteryMod;
import io.wifi.starrailexpress.util.ItemSkinManager;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public API for registering skins supplied by other Fabric mods. */
public final class HabiSkinApi {
    public static final int API_VERSION = 1;
    public static final String ENTRYPOINT = "habitrain_lottery_skins";

    private static final Map<String, SkinDefinition> DEFINITIONS = new LinkedHashMap<>();

    private HabiSkinApi() {
    }

    /**
     * Registers a skin immediately with SRE and records its model/pool metadata.
     *
     * @return {@code true} for a new registration, or {@code false} when the
     * exact same definition was already registered
     * @throws IllegalStateException when another definition or a direct SRE
     * registration already owns the same type/id
     */
    public static synchronized boolean register(SkinDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Skin definition must not be null");
        }
        String key = key(definition.type(), definition.id());
        SkinDefinition current = DEFINITIONS.get(key);
        if (current != null) {
            if (current.equals(definition)) {
                return false;
            }
            throw new IllegalStateException("Conflicting skin registration for " + key);
        }
        if (ItemSkinManager.getSkins(definition.type()).containsKey(definition.id())) {
            throw new IllegalStateException("SRE already contains a skin registered outside this API: " + key);
        }

        ItemSkinManager.registerACustomSkin(definition.type(), definition.id(), definition.color());
        DEFINITIONS.put(key, definition);
        HabiLotteryMod.LOGGER.debug("Registered skin API entry {} model={}", key, definition.model());
        return true;
    }

    public static synchronized Optional<SkinDefinition> find(String type, String id) {
        try {
            String normalizedType = SkinDefinition.normalizeType(type, false);
            if (id == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(DEFINITIONS.get(key(normalizedType, id.trim().toLowerCase(java.util.Locale.ROOT))));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Returns a stable immutable snapshot in registration order. */
    public static synchronized List<SkinDefinition> registrations() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static synchronized int size() {
        return DEFINITIONS.size();
    }

    /** Replays every API registration into SRE without changing API ordering or pool metadata. */
    public static synchronized int reRegisterAll() {
        for (SkinDefinition definition : DEFINITIONS.values()) {
            ItemSkinManager.registerACustomSkin(definition.type(), definition.id(), definition.color());
        }
        return DEFINITIONS.size();
    }

    /** Used by the client model bridge; external mods may also inspect the resolved model id. */
    public static Optional<ResourceLocation> model(String type, String id, boolean inHand) {
        return find(type, id).map(definition -> definition.model(inHand));
    }

    private static String key(String type, String id) {
        return type + "/" + id;
    }
}
