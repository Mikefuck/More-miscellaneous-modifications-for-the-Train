package com.habitrain.lottery.api.skin;



import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public API for registering skins supplied by other Fabric mods. */
public final class HabiSkinApi {
    public static final int API_VERSION = 2;
    public static final String ENTRYPOINT = "habitrain_lottery_skins";

    private static final Map<String, SkinDefinition> DEFINITIONS = new LinkedHashMap<>();
    /**
     * Which entrypoint provider contributed each catalogue key. Used to roll one
     * provider's registrations back when its registrar throws halfway through.
     */
    private static final Map<String, String> PROVIDERS = new LinkedHashMap<>();
    private static final ThreadLocal<String> CURRENT_PROVIDER = new ThreadLocal<>();

    private HabiSkinApi() {
    }

    /**
     * Marks the provider whose entrypoint is currently running. Every successful
     * {@link #register(SkinDefinition)} while this is set is attributed to it, so
     * {@link #removeProvider(String)} can undo a half-finished registrar.
     *
     * @return the previously active provider, to be passed back to
     * {@link #endProvider(String)} (nesting safe)
     */
    public static String beginProvider(String providerId) {
        String previous = CURRENT_PROVIDER.get();
        CURRENT_PROVIDER.set(providerId);
        return previous;
    }

    /** Restores the provider set by {@link #beginProvider(String)}. */
    public static void endProvider(String previousProvider) {
        if (previousProvider == null) {
            CURRENT_PROVIDER.remove();
        } else {
            CURRENT_PROVIDER.set(previousProvider);
        }
    }

    /**
     * The entrypoint provider whose {@code registerSkins()} is currently running, or
     * {@code null} outside a registrar run.
     *
     * <p>Exposed so the sibling registries ({@link SkinEffects}) can attribute their own
     * entries to the same provider and be rolled back together with it.</p>
     */
    public static String currentProvider() {
        return CURRENT_PROVIDER.get();
    }

    /**
     * Registers a skin in the independent local catalog.
     *
     * @return {@code true} for a new registration, or {@code false} when the
     * exact same definition was already registered
     * @throws IllegalStateException when another definition already owns the same type/id
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
        if (DEFINITIONS.size() >= 4096) throw new IllegalStateException("Skin catalog limit is 4096");
        DEFINITIONS.put(key, definition);
        String provider = CURRENT_PROVIDER.get();
        if (provider != null) {
            PROVIDERS.put(key, provider);
        }
        org.slf4j.LoggerFactory.getLogger("habitrain_lottery_skins").debug("Registered skin API entry {} model={}", key, definition.model());
        return true;
    }

    /**
     * Removes one catalogue entry. Used to roll back a registrar that failed
     * halfway through, so a broken provider never leaves a half-registered state.
     *
     * @return {@code true} when the entry existed and was removed
     */
    public static synchronized boolean unregister(String type, String id) {
        try {
            String normalizedType = SkinDefinition.normalizeType(type, false);
            String normalizedId = SkinDefinition.normalizeSkinId(id);
            if (normalizedId == null) {
                return false;
            }
            String key = key(normalizedType, normalizedId);
            PROVIDERS.remove(key);
            return DEFINITIONS.remove(key) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Removes every catalogue entry contributed by one entrypoint provider. */
    public static synchronized int removeProvider(String providerId) {
        if (providerId == null) {
            return 0;
        }
        java.util.List<String> owned = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : PROVIDERS.entrySet()) {
            if (providerId.equals(entry.getValue())) {
                owned.add(entry.getKey());
            }
        }
        for (String key : owned) {
            PROVIDERS.remove(key);
            DEFINITIONS.remove(key);
        }
        return owned.size();
    }

    /** The entrypoint provider that contributed {@code type/id}, if any. */
    public static synchronized java.util.Optional<String> providerOf(String type, String id) {
        try {
            String normalizedType = SkinDefinition.normalizeType(type, false);
            String normalizedId = SkinDefinition.normalizeSkinId(id);
            if (normalizedId == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(PROVIDERS.get(key(normalizedType, normalizedId)));
        } catch (IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
    }

    public static synchronized Optional<SkinDefinition> find(String type, String id) {
        try {
            String normalizedType = SkinDefinition.normalizeType(type, false);
            String normalizedId = SkinDefinition.normalizeSkinId(id);
            if (normalizedId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(DEFINITIONS.get(key(normalizedType, normalizedId)));
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

    /**
     * Compatibility count of the current catalogue. Registrations are already
     * applied in-process; use {@code SkinRegistrar} replay (the
     * {@code /hlt skins reregister} command) when the entrypoints must be run again.
     */
    public static synchronized int reRegisterAll() {
        return DEFINITIONS.size();
    }

    /** Used by the client model bridge; external mods may also inspect the resolved model id. */
    public static Optional<ResourceLocation> model(String type, String id, boolean inHand) {
        return find(type, id).map(definition -> definition.model(inHand));
    }

    public static synchronized Map<String, SkinDefinition> getSkins(String type) {
        Map<String, SkinDefinition> result = new LinkedHashMap<>();
        for (SkinDefinition skin : DEFINITIONS.values()) {
            if (skin.type().equals(com.habitrain.lottery.storage.SkinTypeKeys.canonical(type))) result.put(skin.id(), skin);
        }
        return Map.copyOf(result);
    }

    public static java.util.Set<String> types() { return SkinDefinition.SUPPORTED_TYPES; }

    public static Optional<SkinDefinition> fromEntry(String entry) {
        if (entry == null) return Optional.empty();
        String[] parts = entry.split("/", -1);
        return parts.length == 2 ? find(parts[0], parts[1]) : Optional.empty();
    }

    private static String key(String type, String id) {
        return type + "/" + id;
    }
}
